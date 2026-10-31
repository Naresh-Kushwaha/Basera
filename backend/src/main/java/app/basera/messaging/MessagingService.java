package app.basera.messaging;

import static app.basera.common.db.Db.p;

import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.notification.NotificationService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app chat between students (roommate posts) and between students and owners (listing inquiries).
 * RLS keeps conversations inside the college; participation inside the college is enforced HERE (participant check on
 * every read/write). Contact details never leave the app: there are no outbound links or redirects.
 * Delivery model: clients poll /messages?after=... (simple and works across replicas; no broker needed).
 */
@Service
public class MessagingService {

    public record Start(String kind, UUID roommatePostId, UUID listingId, String message) {}

    private final Db db;
    private final NotificationService notifications;

    public MessagingService(Db db, NotificationService notifications) {
        this.db = db;
        this.notifications = notifications;
    }

    @Transactional
    public UUID start(Start s) {
        UUID counterpart;
        String context;
        if ("ROOMMATE".equals(s.kind()) && s.roommatePostId() != null) {
            Map<String, Object> post = db.require("SELECT author_membership_id AS author, title FROM roommate_post WHERE id = :id AND status = 'ACTIVE'",
                    p("id", s.roommatePostId()), "POST_NOT_FOUND", "Post not found");
            counterpart = (UUID) post.get("author");
            context = (String) post.get("title");
        } else if ("LISTING_INQUIRY".equals(s.kind()) && s.listingId() != null) {
            Map<String, Object> l = db.require("""
                    SELECT m.id AS owner, l.title FROM listing l
                    JOIN listing_tenant_visibility v ON v.listing_id = l.id AND v.tenant_id = app_current_tenant() AND v.status = 'APPROVED'
                    JOIN membership m ON m.user_id = l.owner_user_id AND m.tenant_id = v.tenant_id AND m.status = 'ACTIVE'
                    WHERE l.id = :id""", p("id", s.listingId()), "LISTING_NOT_FOUND", "Listing not found or owner unavailable");
            counterpart = (UUID) l.get("owner");
            context = (String) l.get("title");
        } else {
            throw ApiException.badRequest("INVALID_CONVERSATION", "Provide kind ROOMMATE with roommatePostId, or LISTING_INQUIRY with listingId");
        }
        UUID me = Ctx.membershipId();
        if (me.equals(counterpart)) {
            throw ApiException.badRequest("SELF_MESSAGE", "You cannot message yourself");
        }
        String body = cleanBody(s.message());

        Map<String, Object> reuse = db.one("""
                SELECT c.id FROM conversation c
                JOIN conversation_participant a ON a.conversation_id = c.id AND a.membership_id = :me
                JOIN conversation_participant b ON b.conversation_id = c.id AND b.membership_id = :other
                WHERE c.kind = :kind AND c.roommate_post_id IS NOT DISTINCT FROM CAST(:post AS uuid)
                  AND c.listing_id IS NOT DISTINCT FROM CAST(:listing AS uuid)""",
                p("me", me, "other", counterpart, "kind", s.kind(), "post", s.roommatePostId(), "listing", s.listingId())).orElse(null);
        UUID conversationId;
        if (reuse != null) {
            conversationId = (UUID) reuse.get("id");
        } else {
            conversationId = db.id("""
                    INSERT INTO conversation (tenant_id, kind, roommate_post_id, listing_id)
                    VALUES (app_current_tenant(), :kind, CAST(:post AS uuid), CAST(:listing AS uuid)) RETURNING id""",
                    p("kind", s.kind(), "post", s.roommatePostId(), "listing", s.listingId()));
            for (UUID member : List.of(me, counterpart)) {
                db.exec("INSERT INTO conversation_participant (conversation_id, membership_id, tenant_id) VALUES (:c, :m, app_current_tenant())",
                        p("c", conversationId, "m", member));
            }
        }
        insertMessage(conversationId, counterpart, body, context);
        return conversationId;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return db.rows("""
                SELECT c.id, c.kind, c.roommate_post_id AS "roommatePostId", c.listing_id AS "listingId",
                       (SELECT m2.display_name FROM conversation_participant cp2 JOIN membership m2 ON m2.id = cp2.membership_id AND m2.tenant_id = cp2.tenant_id
                        WHERE cp2.conversation_id = c.id AND cp2.membership_id <> :me LIMIT 1) AS "counterpartName",
                       (SELECT m2.photo_url FROM conversation_participant cp2 JOIN membership m2 ON m2.id = cp2.membership_id AND m2.tenant_id = cp2.tenant_id
                        WHERE cp2.conversation_id = c.id AND cp2.membership_id <> :me LIMIT 1) AS "counterpartPhoto",
                       (SELECT body FROM message WHERE conversation_id = c.id ORDER BY created_at DESC LIMIT 1) AS "lastMessage",
                       (SELECT created_at FROM message WHERE conversation_id = c.id ORDER BY created_at DESC LIMIT 1) AS "lastMessageAt",
                       (SELECT count(*) FROM message x WHERE x.conversation_id = c.id AND x.sender_membership_id <> :me
                        AND x.created_at > COALESCE(cp.last_read_at, 'epoch'::timestamptz)) AS unread,
                       COALESCE((SELECT title FROM roommate_post rp WHERE rp.id = c.roommate_post_id),
                                (SELECT l.title FROM listing l WHERE l.id = c.listing_id)) AS context
                FROM conversation c
                JOIN conversation_participant cp ON cp.conversation_id = c.id AND cp.membership_id = :me
                ORDER BY "lastMessageAt" DESC NULLS LAST""", p("me", Ctx.membershipId()));
    }

    @Transactional
    public List<Map<String, Object>> messages(UUID conversationId, Instant after) {
        requireParticipant(conversationId);
        List<Map<String, Object>> rows = db.rows("""
                SELECT id, body, created_at AS "createdAt", (sender_membership_id = :me) AS mine
                FROM message WHERE conversation_id = :c AND created_at > :after ORDER BY created_at LIMIT 100""",
                p("c", conversationId, "me", Ctx.membershipId(),
                        "after", OffsetDateTime.ofInstant(after == null ? Instant.EPOCH : after, ZoneOffset.UTC)));
        db.exec("UPDATE conversation_participant SET last_read_at = now() WHERE conversation_id = :c AND membership_id = :me",
                p("c", conversationId, "me", Ctx.membershipId()));
        return rows;
    }

    @Transactional
    public void send(UUID conversationId, String message) {
        requireParticipant(conversationId);
        UUID other = (UUID) db.require("SELECT membership_id AS m FROM conversation_participant WHERE conversation_id = :c AND membership_id <> :me",
                p("c", conversationId, "me", Ctx.membershipId()), "CONVERSATION_NOT_FOUND", "Conversation not found").get("m");
        insertMessage(conversationId, other, cleanBody(message), null);
    }

    private void insertMessage(UUID conversationId, UUID counterpart, String body, String context) {
        long unreadBefore = db.count("""
                SELECT count(*) FROM message x JOIN conversation_participant cp ON cp.conversation_id = x.conversation_id AND cp.membership_id = :other
                WHERE x.conversation_id = :c AND x.sender_membership_id <> :other AND x.created_at > COALESCE(cp.last_read_at, 'epoch'::timestamptz)""",
                p("c", conversationId, "other", counterpart));
        db.exec("INSERT INTO message (tenant_id, conversation_id, sender_membership_id, body) VALUES (app_current_tenant(), :c, :me, :body)",
                p("c", conversationId, "me", Ctx.membershipId(), "body", body));
        db.exec("UPDATE conversation SET updated_at = now() WHERE id = :c", p("c", conversationId));
        if (unreadBefore == 0) {   // one notification per burst of messages, not one per message
            notifications.notifyMember(counterpart, "NEW_MESSAGE", "New message" + (context == null ? "" : " about \"" + context + "\""),
                    null, Map.of("conversationId", conversationId.toString()), unreadBefore == 0 && context != null);
        }
    }

    private void requireParticipant(UUID conversationId) {
        if (db.count("SELECT count(*) FROM conversation_participant WHERE conversation_id = :c AND membership_id = :me",
                p("c", conversationId, "me", Ctx.membershipId())) == 0) {
            throw ApiException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found");
        }
    }

    private static String cleanBody(String message) {
        String body = TextSanitizer.clean(message);
        if (body == null || body.isBlank() || body.length() > 4000) {
            throw ApiException.badRequest("INVALID_MESSAGE", "Messages must be 1 to 4000 characters");
        }
        return body;
    }
}

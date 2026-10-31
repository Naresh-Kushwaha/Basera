-- V5: tenant-owned feature tables. Every table has tenant_id and composite foreign keys, so a row can
-- never reference another tenant's data (even when written through the RLS-bypassing platform role).
-- A listing can only be referenced if it has a visibility row in the SAME tenant.

CREATE TABLE listing_interaction (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid NOT NULL REFERENCES tenant (id),
    listing_id            uuid NOT NULL,
    student_membership_id uuid NOT NULL,
    kind                  text NOT NULL CHECK (kind IN ('STAY','MESS_SUBSCRIPTION','TRIAL_MEAL')),
    status                text NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','CONFIRMED','REJECTED','ENDED')),
    start_date            date,
    end_date              date,
    confirmed_by          uuid,
    confirmed_at          timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_by            uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, listing_id)            REFERENCES listing_tenant_visibility (tenant_id, listing_id),
    FOREIGN KEY (tenant_id, student_membership_id) REFERENCES membership (tenant_id, id)
);
CREATE INDEX interaction_student_idx ON listing_interaction (tenant_id, student_membership_id);
CREATE INDEX interaction_listing_idx ON listing_interaction (tenant_id, listing_id);

CREATE TABLE review (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid NOT NULL REFERENCES tenant (id),
    listing_id           uuid NOT NULL,
    interaction_id       uuid NOT NULL,
    author_membership_id uuid NOT NULL,
    rating               smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    hygiene_rating       smallint CHECK (hygiene_rating BETWEEN 1 AND 5),
    body                 text NOT NULL CHECK (char_length(body) <= 4000),
    status               text NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED','HIDDEN')),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_by           uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (interaction_id),   -- one review per confirmed interaction
    FOREIGN KEY (tenant_id, listing_id)            REFERENCES listing_tenant_visibility (tenant_id, listing_id),
    FOREIGN KEY (tenant_id, interaction_id)        REFERENCES listing_interaction (tenant_id, id),
    FOREIGN KEY (tenant_id, author_membership_id)  REFERENCES membership (tenant_id, id)
);
CREATE INDEX review_listing_idx ON review (tenant_id, listing_id) WHERE status = 'PUBLISHED';

CREATE TABLE review_report (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               uuid NOT NULL REFERENCES tenant (id),
    review_id               uuid NOT NULL,
    reporter_membership_id  uuid NOT NULL,
    reason                  text NOT NULL,
    status                  text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACTIONED','DISMISSED')),
    resolved_by             uuid,
    resolved_at             timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_by              uuid,
    UNIQUE (review_id, reporter_membership_id),
    FOREIGN KEY (tenant_id, review_id)              REFERENCES review (tenant_id, id),
    FOREIGN KEY (tenant_id, reporter_membership_id) REFERENCES membership (tenant_id, id)
);
CREATE INDEX review_report_open_idx ON review_report (tenant_id) WHERE status = 'OPEN';

CREATE TABLE scam_report (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               uuid NOT NULL REFERENCES tenant (id),
    listing_id              uuid NOT NULL,
    reporter_membership_id  uuid NOT NULL,
    reason                  text NOT NULL CHECK (reason IN ('FAKE_LISTING','ADVANCE_PAYMENT_DEMAND','MISLEADING_PHOTOS','PRICE_MISMATCH','OTHER')),
    details                 text,
    status                  text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CONFIRMED','DISMISSED')),
    resolved_by             uuid,
    resolved_at             timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_by              uuid,
    UNIQUE (listing_id, reporter_membership_id, tenant_id),
    FOREIGN KEY (tenant_id, listing_id)             REFERENCES listing_tenant_visibility (tenant_id, listing_id),
    FOREIGN KEY (tenant_id, reporter_membership_id) REFERENCES membership (tenant_id, id)
);
CREATE INDEX scam_report_open_idx ON scam_report (tenant_id, listing_id) WHERE status = 'OPEN';

CREATE TABLE utility_report (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               uuid NOT NULL REFERENCES tenant (id),
    listing_id              uuid,                       -- optional: may be an area-wide issue
    reporter_membership_id  uuid NOT NULL,
    kind                    text NOT NULL CHECK (kind IN ('POWER_OUTAGE','WATER_QUALITY','WATER_SUPPLY','WIFI_OUTAGE','OTHER')),
    description             text NOT NULL,
    status                  text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_by              uuid,
    FOREIGN KEY (tenant_id, listing_id)             REFERENCES listing_tenant_visibility (tenant_id, listing_id),
    FOREIGN KEY (tenant_id, reporter_membership_id) REFERENCES membership (tenant_id, id)
);
CREATE INDEX utility_report_tenant_idx ON utility_report (tenant_id, status, created_at DESC);

CREATE TABLE roommate_post (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid NOT NULL REFERENCES tenant (id),
    author_membership_id  uuid NOT NULL,
    looking_for           text NOT NULL CHECK (looking_for IN ('ROOM_WITH_ROOMMATE','ROOMMATE_FOR_MY_PLACE')),
    title                 text NOT NULL,
    body                  text,
    budget_min            integer CHECK (budget_min >= 0),
    budget_max            integer CHECK (budget_max >= 0),
    move_in_date          date,
    preferences           jsonb NOT NULL DEFAULT '{}'::jsonb,
    status                text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED','HIDDEN')),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_by            uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, author_membership_id) REFERENCES membership (tenant_id, id),
    CHECK (budget_max IS NULL OR budget_min IS NULL OR budget_max >= budget_min)
);
CREATE INDEX roommate_post_active_idx ON roommate_post (tenant_id, created_at DESC) WHERE status = 'ACTIVE';

CREATE TABLE conversation (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenant (id),
    kind             text NOT NULL CHECK (kind IN ('ROOMMATE','LISTING_INQUIRY')),
    roommate_post_id uuid,
    listing_id       uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_by       uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, roommate_post_id) REFERENCES roommate_post (tenant_id, id),
    FOREIGN KEY (tenant_id, listing_id)       REFERENCES listing_tenant_visibility (tenant_id, listing_id)
);

CREATE TABLE conversation_participant (
    conversation_id uuid NOT NULL,
    membership_id   uuid NOT NULL,
    tenant_id       uuid NOT NULL REFERENCES tenant (id),
    last_read_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    PRIMARY KEY (conversation_id, membership_id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, membership_id)   REFERENCES membership (tenant_id, id)
);
CREATE INDEX conversation_participant_member_idx ON conversation_participant (tenant_id, membership_id);

CREATE TABLE message (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid NOT NULL REFERENCES tenant (id),
    conversation_id       uuid NOT NULL,
    sender_membership_id  uuid NOT NULL,
    body                  text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 4000),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_by            uuid,
    FOREIGN KEY (tenant_id, conversation_id)      REFERENCES conversation (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, sender_membership_id) REFERENCES membership (tenant_id, id)
);
CREATE INDEX message_conversation_idx ON message (conversation_id, created_at);

CREATE TABLE notification (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                uuid NOT NULL REFERENCES tenant (id),
    recipient_membership_id  uuid NOT NULL,
    type                     text NOT NULL,
    title                    text NOT NULL,
    body                     text,
    data                     jsonb NOT NULL DEFAULT '{}'::jsonb,
    read_at                  timestamptz,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_by               uuid,
    FOREIGN KEY (tenant_id, recipient_membership_id) REFERENCES membership (tenant_id, id)
);
CREATE INDEX notification_inbox_idx ON notification (tenant_id, recipient_membership_id, created_at DESC);

CREATE TABLE email_outbox (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    to_email    text NOT NULL,
    subject     text NOT NULL,
    body        text NOT NULL,
    status      text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts    integer NOT NULL DEFAULT 0,
    last_error  text,
    send_after  timestamptz NOT NULL DEFAULT now(),
    sent_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid
);
CREATE INDEX email_outbox_pending_idx ON email_outbox (send_after) WHERE status = 'PENDING';

CREATE TABLE expense (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    membership_id  uuid NOT NULL,
    amount         numeric(12,2) NOT NULL CHECK (amount >= 0),
    category       text NOT NULL CHECK (category IN ('RENT','FOOD','TRANSPORT','UTILITIES','HEALTH','EDUCATION','ENTERTAINMENT','OTHER')),
    spent_on       date NOT NULL,
    note           text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    FOREIGN KEY (tenant_id, membership_id) REFERENCES membership (tenant_id, id)
);
CREATE INDEX expense_member_idx ON expense (tenant_id, membership_id, spent_on DESC);

CREATE TABLE cost_guide_item (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    category     text NOT NULL,
    label        text NOT NULL,
    typical_min  integer CHECK (typical_min >= 0),
    typical_max  integer CHECK (typical_max >= 0),
    note         text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_by   uuid,
    CHECK (typical_max IS NULL OR typical_min IS NULL OR typical_max >= typical_min)
);
CREATE INDEX cost_guide_item_tenant_idx ON cost_guide_item (tenant_id, category);

CREATE TABLE checklist_item (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    title       text NOT NULL,
    description text,
    sort_order  integer NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    UNIQUE (tenant_id, id)
);

CREATE TABLE checklist_progress (
    membership_id uuid NOT NULL,
    item_id       uuid NOT NULL,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    completed_at  timestamptz NOT NULL DEFAULT now(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_by    uuid,
    PRIMARY KEY (membership_id, item_id),
    FOREIGN KEY (tenant_id, membership_id) REFERENCES membership (tenant_id, id),
    FOREIGN KEY (tenant_id, item_id)       REFERENCES checklist_item (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE sos_contact (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    membership_id uuid NOT NULL,
    name          text NOT NULL,
    phone         text NOT NULL,
    relationship  text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_by    uuid,
    FOREIGN KEY (tenant_id, membership_id) REFERENCES membership (tenant_id, id)
);
CREATE INDEX sos_contact_member_idx ON sos_contact (tenant_id, membership_id);

CREATE TABLE issue_report (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               uuid NOT NULL REFERENCES tenant (id),
    reporter_membership_id  uuid NOT NULL,
    category                text NOT NULL CHECK (category IN ('SAFETY','HARASSMENT','THEFT','HEALTH','INFRASTRUCTURE','OTHER')),
    description             text NOT NULL,
    location_text           text,
    status                  text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_REVIEW','RESOLVED')),
    assigned_to             uuid,
    resolution_note         text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_by              uuid,
    FOREIGN KEY (tenant_id, reporter_membership_id) REFERENCES membership (tenant_id, id)
);
CREATE INDEX issue_report_tenant_idx ON issue_report (tenant_id, status, created_at DESC);

CREATE TABLE announcement (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    title         text NOT NULL,
    body          text NOT NULL,
    pinned        boolean NOT NULL DEFAULT false,
    published_at  timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_by    uuid
);
CREATE INDEX announcement_tenant_idx ON announcement (tenant_id, published_at DESC);

-- Append-only: basera_app is granted INSERT and SELECT only.
CREATE TABLE audit_log (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    actor_user_id  uuid,
    action         text NOT NULL,
    target_type    text NOT NULL,
    target_id      uuid,
    details        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid
);
CREATE INDEX audit_log_tenant_idx ON audit_log (tenant_id, created_at DESC);

-- V6: Row Level Security.
-- FORCE ROW LEVEL SECURITY makes policies apply even to the table owner. basera_app additionally has
-- no BYPASSRLS attribute. Only basera_platform (BYPASSRLS) can see across tenants, and it is reachable
-- only from the platform/auth/tenancy packages (enforced by an ArchUnit test).

-- 6.1 Standard tenant-isolation policy for every table that carries tenant_id and nothing special.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'membership', 'invitation', 'refresh_token', 'auth_code',
        'verification_record', 'listing_interaction', 'review', 'review_report', 'scam_report',
        'utility_report', 'roommate_post', 'conversation', 'conversation_participant', 'message',
        'notification', 'email_outbox', 'expense', 'cost_guide_item', 'checklist_item',
        'checklist_progress', 'sos_contact', 'issue_report', 'announcement', 'audit_log'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I
                 USING      (tenant_id = app_current_tenant())
                 WITH CHECK (tenant_id = app_current_tenant())', t);
    END LOOP;
END $$;

-- 6.2 user_account: a user sees themself; a TENANT_ADMIN also sees accounts that have a membership in
--     the current tenant. Ordinary members never see each other's email: tenant-visible profile data
--     (display_name, photo_url) lives on membership instead. Read-only for basera_app (no write policy).
ALTER TABLE user_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_account FORCE  ROW LEVEL SECURITY;
CREATE POLICY user_account_select ON user_account FOR SELECT USING (
    id = app_current_user()
    OR (app_current_role() = 'TENANT_ADMIN'
        AND EXISTS (SELECT 1 FROM membership m
                    WHERE m.user_id = user_account.id AND m.tenant_id = app_current_tenant()))
);

-- 6.3 listing_tenant_visibility. This policy must NOT reference `listing` (listing's policy references
--     this table; referencing back would raise "infinite recursion detected in policy").
ALTER TABLE listing_tenant_visibility ENABLE ROW LEVEL SECURITY;
ALTER TABLE listing_tenant_visibility FORCE  ROW LEVEL SECURITY;
CREATE POLICY vis_select ON listing_tenant_visibility FOR SELECT USING (
    tenant_id = app_current_tenant() OR owner_user_id = app_current_user());
-- an owner may only ever CREATE a PENDING request; approval is the tenant's decision
CREATE POLICY vis_insert ON listing_tenant_visibility FOR INSERT WITH CHECK (
    tenant_id = app_current_tenant()
    OR (owner_user_id = app_current_user() AND status = 'PENDING'));
CREATE POLICY vis_update ON listing_tenant_visibility FOR UPDATE
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
CREATE POLICY vis_delete ON listing_tenant_visibility FOR DELETE USING (
    tenant_id = app_current_tenant()
    OR (owner_user_id = app_current_user() AND status = 'PENDING'));

-- 6.4 listing: visible to its owner, or to a tenant that has a visibility row for it.
--     Which STATUS a student may see (APPROVED only) is enforced in the service layer + tests; RLS
--     guarantees the tenant boundary and ownership, not role semantics.
ALTER TABLE listing ENABLE ROW LEVEL SECURITY;
ALTER TABLE listing FORCE  ROW LEVEL SECURITY;
CREATE POLICY listing_select ON listing FOR SELECT USING (
    owner_user_id = app_current_user()
    OR EXISTS (SELECT 1 FROM listing_tenant_visibility v
               WHERE v.listing_id = listing.id AND v.tenant_id = app_current_tenant()));
-- a listing can only be created from a tenant context located in the listing's city
CREATE POLICY listing_insert ON listing FOR INSERT WITH CHECK (
    owner_user_id = app_current_user()
    AND city_id = (SELECT t.city_id FROM tenant t WHERE t.id = app_current_tenant()));
CREATE POLICY listing_update ON listing FOR UPDATE
    USING (owner_user_id = app_current_user()) WITH CHECK (owner_user_id = app_current_user());
CREATE POLICY listing_delete ON listing FOR DELETE USING (owner_user_id = app_current_user());

-- 6.5 Listing children inherit visibility through listing's own RLS (the subquery on `listing` is itself
--     filtered by listing_select). Writes are owner-only.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['stay_detail','food_detail','mess_menu_item','utility_info','listing_photo'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY child_select ON %I FOR SELECT
                 USING (EXISTS (SELECT 1 FROM listing l WHERE l.id = %I.listing_id))', t, t);
        EXECUTE format(
            'CREATE POLICY child_insert ON %I FOR INSERT
                 WITH CHECK (EXISTS (SELECT 1 FROM listing l
                                     WHERE l.id = %I.listing_id AND l.owner_user_id = app_current_user()))', t, t);
        EXECUTE format(
            'CREATE POLICY child_update ON %I FOR UPDATE
                 USING (EXISTS (SELECT 1 FROM listing l
                                WHERE l.id = %I.listing_id AND l.owner_user_id = app_current_user()))
                 WITH CHECK (EXISTS (SELECT 1 FROM listing l
                                     WHERE l.id = %I.listing_id AND l.owner_user_id = app_current_user()))', t, t, t);
        EXECUTE format(
            'CREATE POLICY child_delete ON %I FOR DELETE
                 USING (EXISTS (SELECT 1 FROM listing l
                                WHERE l.id = %I.listing_id AND l.owner_user_id = app_current_user()))', t, t);
    END LOOP;
END $$;

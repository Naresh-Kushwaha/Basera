-- V7: least-privilege grants + updated_at triggers.
-- Roles basera_app / basera_platform are created by deploy/db/init/01-roles.sh (outside Flyway because
-- they carry passwords and cluster-level attributes).
--
-- RULE FOR FUTURE MIGRATIONS: every new table needs (a) RLS enabled+forced with a policy, (b) explicit
-- grants here-style, (c) the touch trigger. The isolation test fails the build if (a) is forgotten.

GRANT USAGE ON SCHEMA public TO basera_app, basera_platform;

-- ---- basera_app: runtime role for all tenant-scoped requests -------------------------------------------
GRANT SELECT ON city, tenant,
                transport_route, health_facility, emergency_contact, safe_area_note, bank_kyc_guide,
                user_account
    TO basera_app;
GRANT SELECT, UPDATE        ON membership TO basera_app;              -- role grants / suspension by admin
GRANT SELECT, INSERT, DELETE ON invitation TO basera_app;             -- owner invitations
GRANT SELECT, INSERT, UPDATE, DELETE ON
        listing, stay_detail, food_detail, mess_menu_item, utility_info, listing_photo,
        listing_tenant_visibility, verification_record, listing_interaction, review, review_report,
        scam_report, utility_report, roommate_post, conversation, conversation_participant, message,
        notification, email_outbox, expense, cost_guide_item, checklist_item, checklist_progress,
        sos_contact, issue_report, announcement
    TO basera_app;
GRANT SELECT, INSERT ON audit_log TO basera_app;                      -- append-only
-- NO grants for basera_app on: platform_admin, refresh_token, auth_code.

-- ---- basera_platform: SUPER_ADMIN endpoints + pre-tenant auth flows (BYPASSRLS role) ----------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON
        city, tenant, transport_route, health_facility, emergency_contact, safe_area_note, bank_kyc_guide,
        user_account, platform_admin, membership, invitation, refresh_token, auth_code
    TO basera_platform;
GRANT SELECT, INSERT ON audit_log TO basera_platform;

-- ---- updated_at triggers on every table that has the column ---------------------------------------------
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT c.table_name
        FROM information_schema.columns c
        JOIN information_schema.tables tb
          ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name AND tb.table_type = 'BASE TABLE'
        WHERE c.table_schema = 'public' AND c.column_name = 'updated_at'
    LOOP
        EXECUTE format('CREATE TRIGGER trg_touch_updated_at BEFORE UPDATE ON %I
                        FOR EACH ROW EXECUTE FUNCTION touch_updated_at()', t);
    END LOOP;
END $$;

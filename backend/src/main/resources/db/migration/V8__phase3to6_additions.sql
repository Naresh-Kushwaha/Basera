-- V8: additions needed by the feature modules.

-- Emails are resolved at SEND time by the platform-side dispatcher (a student can never read other users' addresses),
-- so an outbox row may reference a membership instead of carrying an address.
ALTER TABLE email_outbox ALTER COLUMN to_email DROP NOT NULL;
ALTER TABLE email_outbox ADD COLUMN recipient_membership_id uuid;
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_recipient_fk
    FOREIGN KEY (tenant_id, recipient_membership_id) REFERENCES membership (tenant_id, id);
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_target
    CHECK (to_email IS NOT NULL OR recipient_membership_id IS NOT NULL);
GRANT SELECT, UPDATE ON email_outbox TO basera_platform;

-- Per-member switch for email notifications (in-app notifications are always on).
ALTER TABLE membership ADD COLUMN email_notifications boolean NOT NULL DEFAULT true;

-- A student can have only one open interaction of a kind per listing.
CREATE UNIQUE INDEX interaction_open_uq
    ON listing_interaction (tenant_id, listing_id, student_membership_id, kind)
    WHERE status IN ('REQUESTED', 'CONFIRMED');

CREATE INDEX notification_unread_idx ON notification (tenant_id, recipient_membership_id) WHERE read_at IS NULL;
CREATE INDEX utility_report_listing_idx ON utility_report (tenant_id, listing_id);

-- "Verified" must stop being true when the verified facts change. A verification only counts if it is newer than
-- the listing's last MATERIAL change (address, location, rent, deposit). Computed per query, so it works across tenants
-- without any cross-tenant write.
ALTER TABLE listing ADD COLUMN material_changed_at timestamptz NOT NULL DEFAULT now();

CREATE FUNCTION listing_touch_material() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.address_line IS DISTINCT FROM OLD.address_line OR NEW.lat IS DISTINCT FROM OLD.lat
       OR NEW.lng IS DISTINCT FROM OLD.lng THEN
        NEW.material_changed_at = now();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_listing_material BEFORE UPDATE ON listing
    FOR EACH ROW EXECUTE FUNCTION listing_touch_material();

CREATE FUNCTION stay_detail_touch_listing() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.rent_monthly IS DISTINCT FROM OLD.rent_monthly OR NEW.deposit IS DISTINCT FROM OLD.deposit THEN
        UPDATE listing SET material_changed_at = now() WHERE id = NEW.listing_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_stay_detail_material AFTER UPDATE ON stay_detail
    FOR EACH ROW EXECUTE FUNCTION stay_detail_touch_listing();

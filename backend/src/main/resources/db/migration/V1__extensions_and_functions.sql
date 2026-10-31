-- V1: extensions + helper functions used by RLS policies and audit triggers.
-- PostGIS must be installable by the migration role OR pre-created by deploy/db/init/01-roles.sh
-- (the init script does the latter; IF NOT EXISTS makes this a no-op then).
CREATE EXTENSION IF NOT EXISTS postgis;

-- Session context set by the application at the start of EVERY transaction:
--   SELECT set_config('app.current_tenant', '<uuid>', true)   -- true = transaction-local
-- If a value is missing or empty the functions return NULL, every policy comparison
-- becomes NULL, and no rows are visible: the design fails closed.
CREATE FUNCTION app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT NULLIF(current_setting('app.current_tenant', true), '')::uuid $$;

CREATE FUNCTION app_current_user() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT NULLIF(current_setting('app.current_user', true), '')::uuid $$;

CREATE FUNCTION app_current_role() RETURNS text
    LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT NULLIF(current_setting('app.current_role', true), '') $$;

CREATE FUNCTION touch_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$ BEGIN NEW.updated_at = now(); RETURN NEW; END $$;

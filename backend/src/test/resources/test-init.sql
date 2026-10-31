-- Executed once as the container superuser. Mirrors deploy/db/init/01-roles.sh (no DO blocks: the
-- Testcontainers script splitter cuts on semicolons).
CREATE ROLE basera_owner    LOGIN PASSWORD 'owner'    NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE basera_app      LOGIN PASSWORD 'app'      NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE basera_platform LOGIN PASSWORD 'platform' NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;
CREATE EXTENSION IF NOT EXISTS postgis;
ALTER DATABASE basera OWNER TO basera_owner;

-- V2: platform-level tables (cities, tenants, identity, memberships, auth plumbing).
-- Audit columns on every table: created_at, updated_at, created_by, updated_by.
-- created_by / updated_by hold a user_account id but are deliberately NOT foreign keys:
-- they are audit trail values and must never block deletes or cross RLS boundaries.

CREATE TABLE city (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text    NOT NULL,
    state        text    NOT NULL,
    country_code char(2) NOT NULL DEFAULT 'IN',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_by   uuid,
    UNIQUE (name, state, country_code)
);

CREATE TABLE tenant (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id                 uuid NOT NULL REFERENCES city (id),
    slug                    text NOT NULL UNIQUE,   -- also the subdomain: <slug>.basera.app
    name                    text NOT NULL,
    campus_lat              double precision NOT NULL CHECK (campus_lat BETWEEN -90 AND 90),
    campus_lng              double precision NOT NULL CHECK (campus_lng BETWEEN -180 AND 180),
    campus_geog             geography(Point, 4326) GENERATED ALWAYS AS
                                (ST_SetSRID(ST_MakePoint(campus_lng, campus_lat), 4326)::geography) STORED,
    email_domain            text,
    default_language        text NOT NULL DEFAULT 'en',
    languages               text[] NOT NULL DEFAULT ARRAY['en'],
    branding                jsonb NOT NULL DEFAULT '{}'::jsonb,
    enabled_modules         text[] NOT NULL DEFAULT ARRAY['STAY','FOOD','UTILITIES','REVIEWS','ROOMMATES',
                                                          'BUDGET','GUIDE','SAFETY','NOTIFICATIONS','SCAM_PROTECTION'],
    join_policy             text NOT NULL DEFAULT 'OPEN' CHECK (join_policy IN ('OPEN', 'DOMAIN_ONLY')),
    require_verified_student boolean NOT NULL DEFAULT false,
    active                  boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_by              uuid,
    UNIQUE (id, city_id),
    CONSTRAINT tenant_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
    CONSTRAINT tenant_slug_not_reserved CHECK (slug NOT IN ('www','api','auth','admin','app','static','mail','assets','cdn')),
    CONSTRAINT tenant_email_domain_lower CHECK (email_domain IS NULL OR email_domain = lower(email_domain)),
    CONSTRAINT tenant_modules_known CHECK (enabled_modules <@ ARRAY['STAY','FOOD','UTILITIES','REVIEWS','ROOMMATES',
                                           'BUDGET','GUIDE','SAFETY','NOTIFICATIONS','SCAM_PROTECTION']::text[]),
    CONSTRAINT domain_only_needs_domain CHECK (join_policy <> 'DOMAIN_ONLY' OR email_domain IS NOT NULL)
);
CREATE INDEX tenant_city_idx ON tenant (city_id);

-- Global identity. Holds Google identity only; tenant-specific data lives in membership.
CREATE TABLE user_account (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    google_sub     text NOT NULL UNIQUE,
    email          text NOT NULL,
    email_verified boolean NOT NULL DEFAULT false,
    full_name      text NOT NULL,
    photo_url      text,
    hosted_domain  text,
    last_login_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid
);
CREATE INDEX user_account_email_idx ON user_account (lower(email));

-- SUPER_ADMIN lives here, not in membership. Platform-only: basera_app has no grants on it.
CREATE TABLE platform_admin (
    user_id    uuid PRIMARY KEY REFERENCES user_account (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_by uuid
);

CREATE TABLE membership (
    id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  uuid NOT NULL REFERENCES tenant (id),
    user_id                    uuid NOT NULL REFERENCES user_account (id),
    role                       text NOT NULL CHECK (role IN ('TENANT_ADMIN', 'OWNER', 'STUDENT')),
    status                     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    display_name               text NOT NULL,   -- tenant-visible profile (copied from Google at login)
    photo_url                  text,
    college_email              text,
    college_email_verified_at  timestamptz,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    updated_at                 timestamptz NOT NULL DEFAULT now(),
    created_by                 uuid,
    updated_by                 uuid,
    UNIQUE (tenant_id, user_id),
    UNIQUE (tenant_id, id)      -- target for composite foreign keys from tenant-owned tables
);
CREATE INDEX membership_user_idx ON membership (user_id);

-- Pre-authorised roles: consumed when a Google account with a matching VERIFIED email logs in.
CREATE TABLE invitation (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    email       text NOT NULL,
    role        text NOT NULL CHECK (role IN ('TENANT_ADMIN', 'OWNER')),
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    consumed_by uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    CONSTRAINT invitation_email_lower CHECK (email = lower(email))
);
CREATE UNIQUE INDEX invitation_open_uq ON invitation (tenant_id, email, role) WHERE consumed_at IS NULL;

-- Auth plumbing: only basera_platform touches these two tables.
CREATE TABLE refresh_token (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    tenant_id   uuid REFERENCES tenant (id),          -- NULL = platform (SUPER_ADMIN) session
    family_id   uuid NOT NULL,
    token_hash  text NOT NULL UNIQUE,                 -- SHA-256 of the opaque token
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid
);
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
CREATE INDEX refresh_token_expiry_idx ON refresh_token (expires_at);

CREATE TABLE auth_code (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash   text NOT NULL UNIQUE,
    user_id     uuid NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    tenant_id   uuid REFERENCES tenant (id),
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid
);
CREATE INDEX auth_code_expiry_idx ON auth_code (expires_at);

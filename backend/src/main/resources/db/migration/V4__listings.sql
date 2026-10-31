-- V4: listings are OWNER-scoped (not tenant-scoped) and made visible to tenants through
-- listing_tenant_visibility. Verification, status and ratings are therefore PER TENANT.
-- Distance from a campus is computed at query time with PostGIS (ST_DWithin / ST_Distance on the
-- GiST-indexed geog column) rather than stored: a stored snapshot would need a cross-tenant write
-- path whenever a listing moves, which conflicts with strict RLS isolation.

CREATE TABLE listing (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id             uuid NOT NULL REFERENCES user_account (id),
    city_id                   uuid NOT NULL REFERENCES city (id),
    kind                      text NOT NULL CHECK (kind IN ('ROOM','PG','HOSTEL','MESS','TIFFIN','SERVICE')),
    title                     text NOT NULL,
    description               text,
    address_line              text NOT NULL,
    locality                  text,
    lat                       double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng                       double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
    geog                      geography(Point, 4326) GENERATED ALWAYS AS
                                  (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED,
    contact_phone             text,
    availability_status       text NOT NULL DEFAULT 'AVAILABLE' CHECK (availability_status IN ('AVAILABLE','LIMITED','FULL')),
    available_from            date,
    availability_confirmed_at timestamptz NOT NULL DEFAULT now(),
    active                    boolean NOT NULL DEFAULT true,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    created_by                uuid,
    updated_by                uuid,
    UNIQUE (id, city_id),
    UNIQUE (id, owner_user_id)
);
CREATE INDEX listing_geog_gix  ON listing USING gist (geog);
CREATE INDEX listing_owner_idx ON listing (owner_user_id);
CREATE INDEX listing_city_kind_idx ON listing (city_id, kind) WHERE active;

CREATE TABLE stay_detail (
    listing_id         uuid PRIMARY KEY REFERENCES listing (id) ON DELETE CASCADE,
    rent_monthly       integer NOT NULL CHECK (rent_monthly >= 0),
    deposit            integer NOT NULL DEFAULT 0 CHECK (deposit >= 0),
    room_type          text CHECK (room_type IN ('SINGLE','DOUBLE','TRIPLE','SHARED','FLAT')),
    gender_policy      text NOT NULL DEFAULT 'ANY' CHECK (gender_policy IN ('ANY','MALE','FEMALE')),
    curfew_time        time,
    visitors_allowed   boolean,
    cooking_allowed    boolean,
    other_rules        text,
    amenities          text[] NOT NULL DEFAULT '{}',
    agreement_provided boolean NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_by         uuid
);
CREATE INDEX stay_detail_rent_idx ON stay_detail (rent_monthly);

-- Hygiene is intentionally NOT an owner-entered field: it will be a sub-rating on student reviews.
CREATE TABLE food_detail (
    listing_id           uuid PRIMARY KEY REFERENCES listing (id) ON DELETE CASCADE,
    monthly_price        integer NOT NULL CHECK (monthly_price >= 0),
    diet_options         text[] NOT NULL CHECK (cardinality(diet_options) > 0
                                                AND diet_options <@ ARRAY['VEG','NON_VEG','JAIN']::text[]),
    meals_included       text[] NOT NULL DEFAULT ARRAY['LUNCH','DINNER']
                                CHECK (meals_included <@ ARRAY['BREAKFAST','LUNCH','DINNER']::text[]),
    trial_meal_available boolean NOT NULL DEFAULT false,
    trial_meal_price     integer CHECK (trial_meal_price >= 0),
    menu_updated_at      timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_by           uuid
);

CREATE TABLE mess_menu_item (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id  uuid NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    meal        text NOT NULL CHECK (meal IN ('BREAKFAST','LUNCH','DINNER')),
    items       text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    UNIQUE (listing_id, day_of_week, meal)
);

-- Everything here is owner self-reported; the UI must label it that way.
CREATE TABLE utility_info (
    listing_id     uuid PRIMARY KEY REFERENCES listing (id) ON DELETE CASCADE,
    water_source   text CHECK (water_source IN ('MUNICIPAL','BOREWELL','TANKER','MIXED','OTHER')),
    ro_available   boolean,
    power_backup   text CHECK (power_backup IN ('NONE','INVERTER','GENERATOR')),
    wifi_available boolean,
    wifi_mbps      integer CHECK (wifi_mbps >= 0),
    laundry        text CHECK (laundry IN ('NONE','MACHINE','PAID_SERVICE','MANUAL')),
    notes          text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid
);

CREATE TABLE listing_photo (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id   uuid NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    storage_key  text NOT NULL UNIQUE,
    content_type text NOT NULL,
    width        integer,
    height       integer,
    sort_order   integer NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_by   uuid
);
CREATE INDEX listing_photo_listing_idx ON listing_photo (listing_id, sort_order);

-- One row per (listing, tenant). Composite FKs guarantee:
--   * the tenant is in the same city as the listing
--   * owner_user_id is the listing's real owner (denormalised so this table's RLS policy never has to
--     query `listing`, which would make the two policies recurse into each other)
CREATE TABLE listing_tenant_visibility (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id     uuid NOT NULL,
    tenant_id      uuid NOT NULL,
    city_id        uuid NOT NULL,
    owner_user_id  uuid NOT NULL,
    status         text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','SUSPENDED')),
    status_reason  text,
    decided_by     uuid,
    decided_at     timestamptz,
    rating_avg     numeric(3,2) CHECK (rating_avg BETWEEN 1 AND 5),
    rating_count   integer NOT NULL DEFAULT 0 CHECK (rating_count >= 0),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_by     uuid,
    UNIQUE (tenant_id, listing_id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (listing_id, city_id)       REFERENCES listing (id, city_id),
    FOREIGN KEY (listing_id, owner_user_id) REFERENCES listing (id, owner_user_id),
    FOREIGN KEY (tenant_id, city_id)        REFERENCES tenant (id, city_id)
);
CREATE INDEX visibility_tenant_status_idx ON listing_tenant_visibility (tenant_id, status);
CREATE INDEX visibility_owner_idx ON listing_tenant_visibility (owner_user_id);

-- "Verified" = a recorded checklist completed by that tenant's admin. Expires (default 12 months).
CREATE TABLE verification_record (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL,
    visibility_id uuid NOT NULL,
    checklist     jsonb NOT NULL,
    notes         text,
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUPERSEDED','REVOKED')),
    verified_by   uuid NOT NULL,
    verified_at   timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_by    uuid,
    FOREIGN KEY (tenant_id, visibility_id) REFERENCES listing_tenant_visibility (tenant_id, id),
    CHECK (expires_at > verified_at)
);
CREATE INDEX verification_active_idx ON verification_record (visibility_id) WHERE status = 'ACTIVE';

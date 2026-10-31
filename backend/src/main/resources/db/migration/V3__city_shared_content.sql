-- V3: city-level content stored once per city and shared read-only by every tenant in that city.
-- basera_app has SELECT only. Writes go through basera_platform (SUPER_ADMIN).
-- Every row carries last_reviewed_at so the UI can show how fresh the information is.
-- Money amounts are in the city's local currency (INR for the initial deployment).

CREATE TABLE transport_route (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id           uuid NOT NULL REFERENCES city (id),
    mode              text NOT NULL CHECK (mode IN ('BUS','AUTO','E_RICKSHAW','TAXI','METRO','TRAIN','SHARED_CAB','OTHER')),
    name              text NOT NULL,
    from_place        text NOT NULL,
    to_place          text NOT NULL,
    fare_min          integer CHECK (fare_min >= 0),
    fare_max          integer CHECK (fare_max >= 0),
    frequency_note    text,
    notes             text,
    last_reviewed_at  timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid,
    CHECK (fare_max IS NULL OR fare_min IS NULL OR fare_max >= fare_min)
);
CREATE INDEX transport_route_city_idx ON transport_route (city_id);

CREATE TABLE health_facility (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id           uuid NOT NULL REFERENCES city (id),
    kind              text NOT NULL CHECK (kind IN ('HOSPITAL','CLINIC','PHARMACY','DIAGNOSTIC')),
    name              text NOT NULL,
    address           text,
    phone             text,
    lat               double precision CHECK (lat BETWEEN -90 AND 90),
    lng               double precision CHECK (lng BETWEEN -180 AND 180),
    geog              geography(Point, 4326) GENERATED ALWAYS AS
                          (CASE WHEN lat IS NOT NULL AND lng IS NOT NULL
                                THEN ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography END) STORED,
    open_24x7         boolean NOT NULL DEFAULT false,
    last_reviewed_at  timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid
);
CREATE INDEX health_facility_city_idx ON health_facility (city_id, kind);
CREATE INDEX health_facility_geog_gix ON health_facility USING gist (geog);

CREATE TABLE emergency_contact (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id           uuid NOT NULL REFERENCES city (id),
    label             text NOT NULL,
    phone             text NOT NULL,
    description       text,
    sort_order        integer NOT NULL DEFAULT 0,
    last_reviewed_at  timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid
);
CREATE INDEX emergency_contact_city_idx ON emergency_contact (city_id);

-- Opinions/observations, not guarantees. `source` is mandatory so the UI can attribute them.
CREATE TABLE safe_area_note (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id           uuid NOT NULL REFERENCES city (id),
    area_name         text NOT NULL,
    note              text NOT NULL,
    source            text NOT NULL,
    last_reviewed_at  timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid
);
CREATE INDEX safe_area_note_city_idx ON safe_area_note (city_id);

CREATE TABLE bank_kyc_guide (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id           uuid NOT NULL REFERENCES city (id),
    title             text NOT NULL,
    body_md           text NOT NULL,
    last_reviewed_at  timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_by        uuid
);
CREATE INDEX bank_kyc_guide_city_idx ON bank_kyc_guide (city_id);

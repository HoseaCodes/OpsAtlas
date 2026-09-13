-- Slice one, phase 1: the catalog tables.
--
-- CLAUDE.md section 7: database constraints back up every application-level
-- validation. Application validation exists to produce a good error message;
-- the constraints below exist so that a bug in that validation cannot corrupt
-- the data. Every rule the application enforces is also enforced here.
--
-- Tenancy: every table except `organization` carries org_id NOT NULL with a
-- foreign key, and every query filters on it. There is one seeded organization
-- and no authentication. This is single-tenant data modelled so that
-- multi-tenancy is possible later - see docs/adr/0003-org-scoping-stub.md.
--
-- Time: all timestamps are timestamptz and stored in UTC.
-- Identifiers: UUIDv7 for rows whose order is creation order (they double as
-- the keyset pagination sort key), UUIDv4 elsewhere. Generated application-side.

-- ---------------------------------------------------------------------------
-- organization: the scoping root. Not itself org-scoped.
-- ---------------------------------------------------------------------------
CREATE TABLE organization (
    id          uuid        PRIMARY KEY,
    slug        text        NOT NULL,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT organization_slug_unique UNIQUE (slug),
    CONSTRAINT organization_slug_format CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT organization_name_present CHECK (length(btrim(name)) > 0)
);

COMMENT ON TABLE organization IS
    'Tenancy root. Exactly one row exists until authentication is implemented.';

-- ---------------------------------------------------------------------------
-- team: who is accountable for a service.
-- ---------------------------------------------------------------------------
CREATE TABLE team (
    id          uuid        PRIMARY KEY,
    org_id      uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    slug        text        NOT NULL,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT team_slug_unique_per_org UNIQUE (org_id, slug),
    CONSTRAINT team_slug_format CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT team_name_present CHECK (length(btrim(name)) > 0),

    -- Redundant with the primary key for row identity. It exists so that
    -- PostgreSQL will accept (org_id, id) as the target of a composite foreign
    -- key, which is what keeps cross-organization references impossible at the
    -- database level rather than merely unlikely at the application level.
    CONSTRAINT team_org_id_key UNIQUE (org_id, id)
);

CREATE INDEX team_org_idx ON team (org_id);

-- ---------------------------------------------------------------------------
-- service: one row per registered service.yaml.
-- ---------------------------------------------------------------------------
CREATE TABLE service (
    id               uuid        PRIMARY KEY,
    org_id           uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,

    -- Nullable on purpose. An unowned service must be registerable, because the
    -- catalog's job includes showing that nobody is accountable for something.
    -- The owner-declared scorecard check is what reports it.
    team_id          uuid        NULL,

    slug             text        NOT NULL,
    display_name     text        NULL,
    repository       text        NOT NULL,
    tier             smallint    NOT NULL,
    runtime          text        NULL,
    lifecycle        text        NOT NULL DEFAULT 'active',

    -- The apiVersion this document was validated against, stored so a row can
    -- always be interpreted by the rules that admitted it (CLAUDE.md section 8).
    schema_version   text        NOT NULL,

    -- The normalized document as validated. Stored so that re-evaluating policy
    -- never requires re-reading the source repository.
    manifest         jsonb       NOT NULL,

    -- SHA-256 of the raw submitted bytes. Makes re-registering an unchanged
    -- manifest a no-op instead of a rewrite, which is what stands in for an
    -- idempotency key on this endpoint.
    manifest_digest  text        NOT NULL,

    source_path      text        NOT NULL DEFAULT 'service.yaml',
    source_ref       text        NULL,

    -- Optimistic locking (CLAUDE.md section 9). Surfaced as an ETag and
    -- required on PUT via If-Match.
    version          bigint      NOT NULL DEFAULT 0,

    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT service_slug_unique_per_org UNIQUE (org_id, slug),
    -- One service per manifest location. Two repositories cannot both claim to
    -- be the source of the same service, and one repository cannot register the
    -- same path twice.
    CONSTRAINT service_source_unique_per_org UNIQUE (org_id, repository, source_path),
    CONSTRAINT service_org_id_key UNIQUE (org_id, id),

    CONSTRAINT service_slug_format CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT service_repository_format CHECK (repository ~ '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$'),
    CONSTRAINT service_tier_range CHECK (tier BETWEEN 1 AND 3),
    CONSTRAINT service_lifecycle_known CHECK (lifecycle IN ('active', 'deprecated', 'retired')),
    CONSTRAINT service_digest_format CHECK (manifest_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT service_manifest_is_object CHECK (jsonb_typeof(manifest) = 'object'),
    CONSTRAINT service_version_not_negative CHECK (version >= 0),

    -- Composite rather than a plain reference to team(id): a service may only be
    -- owned by a team in its own organization. With a single-column foreign key,
    -- a scoping bug could attach a service to a team across the organization
    -- boundary while every individual constraint stayed satisfied.
    --
    -- MATCH SIMPLE (the default) means the constraint is not enforced when any
    -- referencing column is NULL, which is exactly the behaviour an unowned
    -- service needs. RESTRICT rather than SET NULL because a composite SET NULL
    -- would also null org_id, which is NOT NULL; reassigning a team's services
    -- before deleting the team is the correct workflow anyway.
    CONSTRAINT service_team_same_org FOREIGN KEY (org_id, team_id)
        REFERENCES team (org_id, id) ON DELETE RESTRICT
);

-- Keyset pagination: ids are UUIDv7, so ordering by id is ordering by creation
-- time, and (org_id, id) is exactly the scan this index serves.
CREATE INDEX service_org_id_idx ON service (org_id, id);
CREATE INDEX service_org_team_idx ON service (org_id, team_id);
CREATE INDEX service_org_tier_idx ON service (org_id, tier);

-- No GIN index on manifest. Nothing queries inside the document in slice one,
-- and an index added before there is a query to serve is a guess.

-- ---------------------------------------------------------------------------
-- environment: where a service runs.
-- ---------------------------------------------------------------------------
CREATE TABLE environment (
    id              uuid        PRIMARY KEY,
    org_id          uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    service_id      uuid        NOT NULL,
    name            text        NOT NULL,
    url             text        NULL,
    readiness_path  text        NULL,
    liveness_path   text        NULL,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT environment_name_unique_per_service UNIQUE (org_id, service_id, name),
    CONSTRAINT environment_name_format CHECK (name ~ '^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$'),
    CONSTRAINT environment_url_format CHECK (url IS NULL OR url ~ '^https?://'),
    CONSTRAINT environment_readiness_absolute CHECK (readiness_path IS NULL OR readiness_path ~ '^/'),
    CONSTRAINT environment_liveness_absolute CHECK (liveness_path IS NULL OR liveness_path ~ '^/'),
    CONSTRAINT environment_version_not_negative CHECK (version >= 0),

    -- Same reasoning as service_team_same_org: an environment cannot belong to a
    -- service in a different organization, and the database refuses it rather
    -- than trusting the application to have filtered correctly.
    CONSTRAINT environment_service_same_org FOREIGN KEY (org_id, service_id)
        REFERENCES service (org_id, id) ON DELETE CASCADE
);

CREATE INDEX environment_org_service_idx ON environment (org_id, service_id);

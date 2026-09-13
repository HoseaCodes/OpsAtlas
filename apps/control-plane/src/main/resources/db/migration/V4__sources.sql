-- Phase 6: repositories OpsAtlas watches.
--
-- A source is "go and read this manifest, on a schedule". It is deliberately a
-- separate row from the service it produces: a source can exist before it has
-- ever succeeded, can keep failing while its service stays registered and
-- current, and can be pointed at a repository that turns out to have no
-- manifest at all. Folding sync state onto `service` would make all three of
-- those unrepresentable.
--
-- See docs/adr/0008-polled-sources-not-webhooks.md.

CREATE TABLE source (
    id              uuid        PRIMARY KEY,
    org_id          uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,

    provider        text        NOT NULL,
    repository      text        NOT NULL,
    git_ref         text        NOT NULL DEFAULT 'HEAD',
    path            text        NOT NULL DEFAULT 'service.yaml',

    -- A source that is failing can be switched off without losing the record of
    -- what it was pointed at and why it stopped.
    enabled         boolean     NOT NULL DEFAULT true,

    -- ---------------------------------------------------------------------
    -- Sync state. Every field here is about the *source*, never the service.
    -- A service registered by a source that has since started failing is still
    -- correct as of last_success_at, and the console says so rather than
    -- implying the data is current.
    -- ---------------------------------------------------------------------
    last_attempt_at timestamptz NULL,
    last_success_at timestamptz NULL,
    last_outcome    text        NULL,
    last_detail     text        NULL,

    -- The ETag GitHub returned, replayed as If-None-Match on the next poll.
    -- A 304 then costs no transfer and no ingestion (ADR 0008).
    etag            text        NULL,

    -- Rises on every failed attempt and resets on success. Nothing throttles on
    -- it yet; it exists so that "failing since" is answerable and so backoff has
    -- something to read when it arrives.
    consecutive_failures integer NOT NULL DEFAULT 0,

    -- Set once a sync has produced a service. Null before that, and deliberately
    -- not cleared when a later sync fails.
    service_id      uuid        NULL,

    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    -- One source per manifest location, matching how registration identifies a
    -- service (ADR 0007). Two sources polling the same file would fight over the
    -- same service row.
    CONSTRAINT source_location_unique_per_org UNIQUE (org_id, provider, repository, path),

    CONSTRAINT source_provider_known CHECK (provider IN ('github')),
    CONSTRAINT source_repository_format CHECK (repository ~ '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$'),
    CONSTRAINT source_path_relative CHECK (path ~ '^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$'),
    -- No '..' segment, no absolute path, no leading dash. The path is
    -- interpolated into a URL, so the database refuses the shapes that would
    -- make that interesting as well as the application doing so.
    CONSTRAINT source_path_no_traversal CHECK (path !~ '(^|/)\.\.(/|$)'),
    CONSTRAINT source_ref_format CHECK (git_ref ~ '^[A-Za-z0-9._/-]{1,255}$'),
    CONSTRAINT source_ref_no_traversal CHECK (git_ref !~ '(^|/)\.\.(/|$)'),

    CONSTRAINT source_outcome_known CHECK (
        last_outcome IS NULL OR last_outcome IN (
            'REGISTERED',    -- a new service was created
            'UPDATED',       -- an existing service's manifest changed
            'UNCHANGED',     -- the manifest was byte-identical, or GitHub said 304
            'REJECTED',      -- the manifest was fetched and failed validation
            'CONFLICT',      -- the manifest clashes with something already registered
            'NOT_FOUND',     -- the repository, ref or path does not exist
            'UNAUTHORIZED',  -- credentials missing or rejected
            'RATE_LIMITED',  -- the provider refused for quota reasons
            'UNREACHABLE'    -- the provider could not be contacted
        )
    ),
    -- An outcome that is not a success has to say why, for the same reason a
    -- failing policy check does: "it failed" is a state, not something to act on.
    CONSTRAINT source_failure_is_explained CHECK (
        last_outcome IS NULL
            OR last_outcome IN ('REGISTERED', 'UPDATED', 'UNCHANGED')
            OR length(btrim(coalesce(last_detail, ''))) > 0
    ),
    CONSTRAINT source_failures_not_negative CHECK (consecutive_failures >= 0),
    CONSTRAINT source_version_not_negative CHECK (version >= 0),

    -- A source may only point at a service in its own organization.
    CONSTRAINT source_service_same_org FOREIGN KEY (org_id, service_id)
        REFERENCES service (org_id, id) ON DELETE SET NULL
);

CREATE INDEX source_org_id_idx ON source (org_id, id);
CREATE INDEX source_org_service_idx ON source (org_id, service_id);

-- The scheduler's query: enabled sources, oldest attempt first, so a source that
-- has never been tried is picked up before one polled a minute ago.
CREATE INDEX source_due_idx ON source (enabled, last_attempt_at NULLS FIRST);

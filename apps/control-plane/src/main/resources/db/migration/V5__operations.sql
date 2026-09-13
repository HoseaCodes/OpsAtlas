-- Phase 7: what the observer reports.
--
-- Two tables, both bounded, neither holding a single probe result. See
-- docs/adr/0009-observations-are-rolled-up-not-a-time-series.md for why, and
-- for what this deliberately cannot answer.
--
-- Storage is environments x retained days. It does not grow with probe
-- frequency, which is the property that lets the observer probe as often as is
-- useful without a storage conversation.

-- ---------------------------------------------------------------------------
-- environment gains (org_id, id) as a referenced key.
--
-- team and service got theirs in V1; environment did not, because nothing
-- pointed at an environment until now. It is added here rather than by editing
-- V1, because V1 has already been applied and Flyway validates its checksum -
-- editing an applied migration is how a schema silently diverges between a
-- developer's database and a fresh one.
-- ---------------------------------------------------------------------------
ALTER TABLE environment ADD CONSTRAINT environment_org_id_key UNIQUE (org_id, id);

-- ---------------------------------------------------------------------------
-- environment_state: what is true right now. One row per environment, forever,
-- updated in place. This is the catalog's hot read.
-- ---------------------------------------------------------------------------
CREATE TABLE environment_state (
    environment_id       uuid        PRIMARY KEY,
    org_id               uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    service_id           uuid        NOT NULL,

    -- HEALTHY, DEGRADED or DOWN. There is deliberately no "UNKNOWN": an
    -- environment nobody has probed has no row at all, so absence means
    -- never observed and cannot be confused with a measurement.
    status               text        NOT NULL,

    -- Why it is not healthy, in the words the console shows. Null when healthy,
    -- for the same reason a passing policy check carries no detail.
    detail               text        NULL,

    last_probe_at        timestamptz NOT NULL,
    last_healthy_at      timestamptz NULL,

    -- Rises on every failed probe, resets on success. This is what separates a
    -- blip from an outage without needing the probe history the rollups threw
    -- away.
    consecutive_failures integer     NOT NULL DEFAULT 0,

    -- The most recent successful probe's round trip. Null until one succeeds.
    response_ms          integer     NULL,

    -- The HTTP status of the last probe, where there was one. Null when the
    -- failure was a timeout or a connection refusal - which is itself the
    -- distinction between "it answered badly" and "it did not answer".
    last_status_code     integer     NULL,

    version              bigint      NOT NULL DEFAULT 0,
    updated_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT environment_state_status_known CHECK (status IN ('HEALTHY', 'DEGRADED', 'DOWN')),
    CONSTRAINT environment_state_unhealthy_is_explained
        CHECK (status = 'HEALTHY' OR length(btrim(coalesce(detail, ''))) > 0),
    CONSTRAINT environment_state_failures_not_negative CHECK (consecutive_failures >= 0),
    CONSTRAINT environment_state_response_not_negative CHECK (response_ms IS NULL OR response_ms >= 0),
    CONSTRAINT environment_state_version_not_negative CHECK (version >= 0),

    -- Composite, like every other cross-table reference here: an environment's
    -- state cannot belong to a different organization than the environment.
    CONSTRAINT environment_state_environment_same_org FOREIGN KEY (org_id, environment_id)
        REFERENCES environment (org_id, id) ON DELETE CASCADE,
    CONSTRAINT environment_state_service_same_org FOREIGN KEY (org_id, service_id)
        REFERENCES service (org_id, id) ON DELETE CASCADE
);

-- The catalog list asks "what is the state of every environment of these
-- services", and this index is that question.
CREATE INDEX environment_state_org_service_idx ON environment_state (org_id, service_id);

-- (org_id, environment_id) as a referenced key, so environment_day can point at
-- a state row without a second lookup path.
ALTER TABLE environment_state ADD CONSTRAINT environment_state_org_id_key UNIQUE (org_id, environment_id);

-- ---------------------------------------------------------------------------
-- environment_day: one row per environment per UTC day. Counters, not samples.
--
-- A batch of observations increments these; it never inserts a row per probe.
-- Thirty rows per environment is the 30-day ribbon.
-- ---------------------------------------------------------------------------
CREATE TABLE environment_day (
    environment_id  uuid        NOT NULL,
    day             date        NOT NULL,
    org_id          uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    service_id      uuid        NOT NULL,

    probes          integer     NOT NULL DEFAULT 0,
    successes       integer     NOT NULL DEFAULT 0,

    -- Sum, min and max rather than percentiles. A p95 needs the distribution,
    -- and these are not a distribution: computing one from them would be wrong
    -- in a way nobody could see. Real percentiles arrive with the telemetry
    -- pipeline (ADR 0009).
    response_ms_sum bigint      NOT NULL DEFAULT 0,
    response_ms_min integer     NULL,
    response_ms_max integer     NULL,

    updated_at      timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (environment_id, day),

    CONSTRAINT environment_day_counts_sane CHECK (probes >= 0 AND successes >= 0 AND successes <= probes),
    CONSTRAINT environment_day_sum_not_negative CHECK (response_ms_sum >= 0),

    CONSTRAINT environment_day_environment_same_org FOREIGN KEY (org_id, environment_id)
        REFERENCES environment (org_id, id) ON DELETE CASCADE,
    CONSTRAINT environment_day_service_same_org FOREIGN KEY (org_id, service_id)
        REFERENCES service (org_id, id) ON DELETE CASCADE
);

-- The ribbon's query: the last N days for one environment, newest first.
CREATE INDEX environment_day_recent_idx ON environment_day (org_id, environment_id, day DESC);

-- The retention job's query.
CREATE INDEX environment_day_pruning_idx ON environment_day (day);

-- ---------------------------------------------------------------------------
-- observation_batch: idempotency for ingestion.
--
-- CLAUDE.md section 9 requires idempotency keys so a retried POST does not
-- double-write. Counters are exactly the shape where a double-write is silent -
-- nothing looks broken, the numbers are just wrong - so this is what makes the
-- rollups above trustworthy rather than merely plausible.
-- ---------------------------------------------------------------------------
CREATE TABLE observation_batch (
    org_id          uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    idempotency_key text        NOT NULL,

    received_at     timestamptz NOT NULL DEFAULT now(),
    observations    integer     NOT NULL,

    -- The response the first attempt produced, replayed verbatim to a retry.
    -- Recomputing it could return something different if state has moved on,
    -- and a retry must not be able to observe that.
    response        jsonb       NOT NULL,

    PRIMARY KEY (org_id, idempotency_key),

    CONSTRAINT observation_batch_key_shape CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,200}$'),
    CONSTRAINT observation_batch_count_not_negative CHECK (observations >= 0)
);

-- The retention job prunes these too: an idempotency key only has to outlive
-- the retries of the request that carried it.
CREATE INDEX observation_batch_received_idx ON observation_batch (received_at);

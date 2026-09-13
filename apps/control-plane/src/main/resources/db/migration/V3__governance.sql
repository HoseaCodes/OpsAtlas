-- Slice one, phase 3: scorecards and audit.
--
-- Two append-only histories. Neither table is ever updated, which is why
-- neither carries an optimistic-lock version column: a lock protects against
-- concurrent modification, and nothing here is modified.

-- ---------------------------------------------------------------------------
-- policy_result: one row per evaluation of one service.
--
-- History, not current state. "The score now" is the newest row, which the
-- (org_id, service_id, evaluated_at desc) index answers directly. Storing only
-- the latest would make the compliance trend unrecoverable, and the trend is
-- the only honest measure of whether the platform is doing anything.
-- ---------------------------------------------------------------------------
CREATE TABLE policy_result (
    id                  uuid        PRIMARY KEY,
    org_id              uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    service_id          uuid        NOT NULL,

    -- The rule set this score was produced by. Raising the bar fleet-wide must
    -- not retroactively rewrite last month's results: a past score has to keep
    -- meaning what it meant when it was computed. See ADR 0004.
    policy_set_version  text        NOT NULL,

    evaluated_at        timestamptz NOT NULL,

    -- Both stored, never recomputed. The denominator moves: a tier 3 service
    -- has fewer applicable rules than a tier 1, so "7 passed" is meaningless
    -- without the 7-of-what it was measured against.
    checks_passed       smallint    NOT NULL,
    checks_applicable   smallint    NOT NULL,

    CONSTRAINT policy_result_counts_sane
        CHECK (checks_passed >= 0 AND checks_passed <= checks_applicable),
    CONSTRAINT policy_result_version_present
        CHECK (length(btrim(policy_set_version)) > 0),

    CONSTRAINT policy_result_service_same_org FOREIGN KEY (org_id, service_id)
        REFERENCES service (org_id, id) ON DELETE CASCADE
);

CREATE INDEX policy_result_latest_idx ON policy_result (org_id, service_id, evaluated_at DESC);

-- ---------------------------------------------------------------------------
-- policy_result_check: one row per check per evaluation.
--
-- Rows rather than a JSON blob on policy_result. The scorecard matrix is one
-- row per service and one column per rule, and "which services fail the runbook
-- check" is a question the fleet view asks constantly. As rows those are index
-- scans; as a blob they are a full scan and N document parses. ADR 0004.
-- ---------------------------------------------------------------------------
CREATE TABLE policy_result_check (
    policy_result_id  uuid   NOT NULL REFERENCES policy_result (id) ON DELETE CASCADE,
    check_id          text   NOT NULL,
    status            text   NOT NULL,

    -- Why it failed, in the words the console shows. Null when it passed or
    -- does not apply - there is nothing to act on in either case.
    detail            text   NULL,

    PRIMARY KEY (policy_result_id, check_id),

    CONSTRAINT policy_result_check_status_known
        CHECK (status IN ('PASS', 'FAIL', 'NOT_APPLICABLE')),
    -- A failure with no explanation is the scorecard equivalent of "Invalid
    -- YAML": it tells the owner that something is wrong and nothing about what
    -- to do. The database refuses to store one.
    CONSTRAINT policy_result_check_failure_is_explained
        CHECK (status <> 'FAIL' OR length(btrim(coalesce(detail, ''))) > 0)
);

CREATE INDEX policy_result_check_by_check_idx ON policy_result_check (check_id, status);

-- ---------------------------------------------------------------------------
-- audit_event: what happened, who did it, and which request it belonged to.
--
-- Append-only. In this phase that is enforced by the application only; the
-- database-level REVOKE UPDATE, DELETE for the application role is recorded in
-- docs/roadmap.md and is not done yet, so the table must not be described as
-- immutable.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_event (
    id              uuid        PRIMARY KEY,
    org_id          uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    occurred_at     timestamptz NOT NULL,

    -- TODO(auth): 'local-operator' until authentication exists. This is the
    -- field that becomes meaningful the moment the stub resolver is replaced.
    actor           text        NOT NULL,

    action          text        NOT NULL,
    subject_type    text        NOT NULL,
    subject_id      uuid        NOT NULL,

    -- Ties an audit entry to the request that caused it, and to every log line
    -- that request wrote (CLAUDE.md section 9).
    correlation_id  text        NOT NULL,

    payload         jsonb       NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT audit_event_actor_present CHECK (length(btrim(actor)) > 0),
    CONSTRAINT audit_event_action_format CHECK (action ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$'),
    CONSTRAINT audit_event_payload_is_object CHECK (jsonb_typeof(payload) = 'object')
);

-- No foreign key from subject_id to service(id). An audit entry must outlive
-- the thing it describes: "this service was deleted" is precisely the record
-- worth keeping, and a cascade would erase it.
CREATE INDEX audit_event_recent_idx ON audit_event (org_id, occurred_at DESC);
CREATE INDEX audit_event_subject_idx ON audit_event (org_id, subject_type, subject_id, occurred_at DESC);

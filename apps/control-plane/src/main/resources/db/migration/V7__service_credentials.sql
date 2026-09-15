-- ---------------------------------------------------------------------------
-- service_credential: how a machine belonging to this system authenticates.
--
-- ADR 0013. The observer is a component of OpsAtlas, not a person. Giving it an
-- account at the identity provider would make it indistinguishable from a human
-- in the audit log, and `actor` would name somebody who does not exist. So it
-- carries a credential this system issues, and the audit log calls it what it
-- is: `service:observer`.
--
-- Exactly one row per machine that needs in. There is no console for managing
-- these and no endpoint that mints them: a credential is configured, hashed on
-- startup, and revoked by removing the configuration.
-- ---------------------------------------------------------------------------
CREATE TABLE service_credential (
    id           uuid        PRIMARY KEY,
    org_id       uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,

    -- Appears in the audit log as `service:<name>`, so it has to read as a
    -- thing rather than a person.
    name         text        NOT NULL,

    -- SHA-256 of the key, hex. Deliberately a fast hash, which would be wrong
    -- for a password and is right here: the key is 256 bits from a CSPRNG, not
    -- something a human chose, so there is no dictionary to run and no work
    -- factor that would help. What matters is that the database never holds the
    -- key itself.
    secret_hash  text        NOT NULL,

    created_at   timestamptz NOT NULL DEFAULT now(),

    -- Answers "is this credential still in use, or can it be removed", which is
    -- the question nobody can answer about a key they are afraid to delete.
    last_used_at timestamptz NULL,

    CONSTRAINT service_credential_name_unique_per_org UNIQUE (org_id, name),
    CONSTRAINT service_credential_hash_unique UNIQUE (secret_hash),
    CONSTRAINT service_credential_name_present CHECK (length(btrim(name)) > 0),
    CONSTRAINT service_credential_hash_shape CHECK (secret_hash ~ '^[0-9a-f]{64}$')
);

-- The lookup every observer request makes.
CREATE INDEX service_credential_hash_idx ON service_credential (secret_hash);

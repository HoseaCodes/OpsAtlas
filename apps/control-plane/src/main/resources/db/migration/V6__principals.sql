-- ---------------------------------------------------------------------------
-- principal: who may act, and on whose behalf.
--
-- ADR 0013. Storm-Gate says a token is genuine and who presented it. It does
-- not say which organization they belong to, and no claim will be added to make
-- it say so: the issuer stays a generic authentication service, and the system
-- that models tenancy keeps modelling it. This table is that mapping.
--
-- A token that verifies but has no row here is authenticated and unauthorized.
-- That is the correct answer for a real person who has signed in to the right
-- identity provider and has no business in this catalog, and it is why a row
-- is required rather than created on first sight.
--
-- Deleting a row is how access is revoked. There is deliberately no `enabled`
-- column and no `role`: nothing today behaves differently per role, and a
-- column nobody reads is a promise nobody keeps (CLAUDE.md section 3 rule 3).
-- ---------------------------------------------------------------------------
CREATE TABLE principal (
    id           uuid        PRIMARY KEY,
    org_id       uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,

    -- The `iss` claim. Part of the identity because a subject is only unique
    -- within the issuer that minted it: two identity providers can and do hand
    -- out the same opaque id, and treating those as one person would hand one
    -- tenant's catalog to another.
    issuer       text        NOT NULL,

    -- Storm-Gate stamps this as `id` rather than `sub` (ADR 0013).
    subject      text        NOT NULL,

    -- A human-readable name for this caller. Stored rather than read from the
    -- token so it survives the account being removed from the provider.
    --
    -- The audit log deliberately does NOT use it: `audit_event.actor` carries
    -- the subject, because names are not unique and they change, and an entry
    -- has to say which account acted rather than which name was in use that
    -- week. Rendering a name beside an audit entry means joining to this row,
    -- which is possible precisely because it outlives the provider account.
    display_name text        NOT NULL,

    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT principal_identity_unique UNIQUE (issuer, subject),
    CONSTRAINT principal_issuer_present CHECK (length(btrim(issuer)) > 0),
    CONSTRAINT principal_subject_present CHECK (length(btrim(subject)) > 0),
    CONSTRAINT principal_display_name_present CHECK (length(btrim(display_name)) > 0)
);

-- The lookup every authenticated request makes.
CREATE INDEX principal_lookup_idx ON principal (issuer, subject);

-- The other direction: everyone in an organization.
CREATE INDEX principal_org_idx ON principal (org_id);

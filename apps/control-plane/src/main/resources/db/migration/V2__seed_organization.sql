-- The single organization.
--
-- This is schema, not sample data. CLAUDE.md section 7 fixes the tenancy model
-- as "one seeded organization resolved by a stub PrincipalResolver until
-- authentication exists", which means exactly one organization row is part of
-- the contract the application is written against - not a development
-- convenience that production would omit.
--
-- The id is fixed and matches SeededOrgPrincipalResolver.SEEDED_ORG_ID. A test
-- asserts that the two agree, because a mismatch would make every query filter
-- on an organization that does not exist and return empty results forever,
-- which looks exactly like "no services registered yet".
--
-- TODO(auth): when authentication lands, organizations are created through the
-- API and this migration becomes the bootstrap of the first one only.

INSERT INTO organization (id, slug, name)
VALUES (
    '00000000-0000-4000-8000-000000000001',
    'ambitious-concepts',
    'Ambitious Concepts'
);

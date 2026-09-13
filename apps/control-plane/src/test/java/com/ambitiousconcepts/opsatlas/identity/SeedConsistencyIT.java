package com.ambitiousconcepts.opsatlas.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The stub resolver's organization id and the seed migration's organization id
 * must be the same value.
 *
 * <p>If they drift, every query filters on an organization that has no rows and
 * the API returns empty pages forever - which is indistinguishable from "nothing
 * is registered yet" and would be found by a confused human rather than by the
 * build.
 */
@SpringBootTest
class SeedConsistencyIT extends PostgresTestBase {

    /** Must match SeededOrgPrincipalResolver.SEEDED_ORG_ID and V2__seed_organization.sql. */
    private static final UUID EXPECTED_ORG_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private DataSource dataSource;

    @Test
    void the_seeded_organization_exists_with_the_id_the_resolver_returns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String slug = jdbc.queryForObject(
                "select slug from organization where id = ?", String.class, EXPECTED_ORG_ID);

        assertThat(slug).isEqualTo("ambitious-concepts");
    }

    @Test
    void there_is_exactly_one_organization() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject("select count(*) from organization", Integer.class);

        // Not a style preference. The stub resolver returns a fixed organization
        // for every request, so a second one would be silently unreachable.
        assertThat(count).isEqualTo(1);
    }
}

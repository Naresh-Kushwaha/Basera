package app.basera.isolation;

import static app.basera.support.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.basera.support.TestData;
import app.basera.support.TestPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves tenant isolation at the DATABASE level, with no Spring involved: every assertion runs as the real
 * runtime role basera_app (no BYPASSRLS) after the same set_config() the application issues per transaction.
 */
class RlsIsolationIT {

    @FunctionalInterface
    interface Work<T> {
        T apply(Connection c) throws SQLException;
    }

    @BeforeEach
    void seed() throws SQLException {
        TestData.reset();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static <T> T as(UUID tenant, UUID user, String role, Work<T> work) throws SQLException {
        try (Connection c = TestPostgres.connect("basera_app", "app")) {
            c.setAutoCommit(false);
            setContext(c, tenant, user, role);
            try {
                return work.apply(c);
            } finally {
                c.rollback();
            }
        }
    }

    private static void setContext(Connection c, UUID tenant, UUID user, String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT set_config('app.current_tenant', ?, true), set_config('app.current_user', ?, true), "
                        + "set_config('app.current_role', ?, true)")) {
            ps.setString(1, tenant == null ? "" : tenant.toString());
            ps.setString(2, user == null ? "" : user.toString());
            ps.setString(3, role == null ? "" : role);
            ps.execute();
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM (" + sql + ") q")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String sqlState(SQLException e) {
        return e.getSQLState();
    }

    // ---- tests --------------------------------------------------------------------------------------------------

    @Test
    void failsClosedWhenNoTenantIsSet() throws SQLException {
        as(null, null, null, c -> {
            assertThat(count(c, "SELECT 1 FROM membership")).isZero();
            assertThat(count(c, "SELECT 1 FROM listing")).isZero();
            assertThat(count(c, "SELECT 1 FROM user_account")).isZero();
            assertThat(count(c, "SELECT 1 FROM listing_tenant_visibility")).isZero();
            return null;
        });
    }

    @Test
    void tenantACanNeverReadTenantBMembers() throws SQLException {
        as(TENANT_A, USER_STUDENT_A, "STUDENT", c -> {
            assertThat(count(c, "SELECT 1 FROM membership")).isEqualTo(3);
            assertThat(count(c, "SELECT 1 FROM membership WHERE tenant_id <> '" + TENANT_A + "'")).isZero();
            assertThat(count(c, "SELECT 1 FROM membership WHERE id = '" + MEMBER_STUDENT_B + "'")).isZero();
            return null;
        });
        as(TENANT_B, USER_STUDENT_B, "STUDENT", c -> {
            assertThat(count(c, "SELECT 1 FROM membership")).isEqualTo(1);
            return null;
        });
    }

    @Test
    void userAccountsAreVisibleOnlyToSelfAndToTheirTenantAdmin() throws SQLException {
        as(TENANT_A, USER_STUDENT_A, "STUDENT", c -> {
            assertThat(count(c, "SELECT 1 FROM user_account")).isEqualTo(1);   // classmates' emails stay private
            return null;
        });
        as(TENANT_A, USER_ADMIN_A, "TENANT_ADMIN", c -> {
            assertThat(count(c, "SELECT 1 FROM user_account")).isEqualTo(3);   // A's members only
            assertThat(count(c, "SELECT 1 FROM user_account WHERE email = 'student.b@example.in'")).isZero();
            return null;
        });
    }

    @Test
    void listingIsVisibleOnlyToTenantsThatHaveAVisibilityRow() throws SQLException {
        as(TENANT_A, USER_STUDENT_A, "STUDENT", c -> {
            assertThat(count(c, "SELECT 1 FROM listing")).isEqualTo(1);
            assertThat(count(c, "SELECT 1 FROM stay_detail")).isEqualTo(1);   // children inherit via listing RLS
            assertThat(count(c, "SELECT 1 FROM listing_tenant_visibility")).isEqualTo(1);
            return null;
        });
        as(TENANT_B, USER_STUDENT_B, "STUDENT", c -> {
            assertThat(count(c, "SELECT 1 FROM listing")).isZero();
            assertThat(count(c, "SELECT 1 FROM stay_detail")).isZero();
            assertThat(count(c, "SELECT 1 FROM listing_tenant_visibility")).isZero();
            return null;
        });
        as(TENANT_C, USER_STUDENT_B, "STUDENT", c -> {
            assertThat(count(c, "SELECT 1 FROM listing")).isZero();
            return null;
        });
    }

    @Test
    void distanceIsComputedAtQueryTimePerTenant() throws SQLException {
        as(TENANT_A, USER_STUDENT_A, "STUDENT", c -> {
            long metres;
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT round(ST_Distance(l.geog, t.campus_geog)) "
                         + "FROM listing l JOIN tenant t ON t.id = app_current_tenant()")) {
                rs.next();
                metres = rs.getLong(1);
            }
            assertThat(metres).isLessThan(100);   // the PG is ~30 m from campus A
            return null;
        });
    }

    @Test
    void ownerCanRequestPendingVisibilityButNeverApproveOrCrossCities() throws SQLException {
        // allowed: PENDING request for college B (same city), made from A's context
        as(TENANT_A, USER_OWNER, "OWNER", c -> {
            exec(c, "INSERT INTO listing_tenant_visibility (listing_id, tenant_id, city_id, owner_user_id, status) VALUES ('"
                    + LISTING_1 + "', '" + TENANT_B + "', '" + CITY_JABALPUR + "', '" + USER_OWNER + "', 'PENDING')");
            assertThat(count(c, "SELECT 1 FROM listing_tenant_visibility")).isEqualTo(2);   // owner sees own requests
            return null;
        });
        // forbidden: self-approving
        SQLException approve = assertThrows(SQLException.class, () -> as(TENANT_A, USER_OWNER, "OWNER", c -> {
            exec(c, "INSERT INTO listing_tenant_visibility (listing_id, tenant_id, city_id, owner_user_id, status) VALUES ('"
                    + LISTING_1 + "', '" + TENANT_B + "', '" + CITY_JABALPUR + "', '" + USER_OWNER + "', 'APPROVED')");
            return null;
        }));
        assertThat(sqlState(approve)).isEqualTo("42501");
        // forbidden: tenant in another city (composite foreign key)
        SQLException crossCity = assertThrows(SQLException.class, () -> as(TENANT_A, USER_OWNER, "OWNER", c -> {
            exec(c, "INSERT INTO listing_tenant_visibility (listing_id, tenant_id, city_id, owner_user_id, status) VALUES ('"
                    + LISTING_1 + "', '" + TENANT_C + "', '" + CITY_JABALPUR + "', '" + USER_OWNER + "', 'PENDING')");
            return null;
        }));
        assertThat(sqlState(crossCity)).isEqualTo("23503");
    }

    @Test
    void ownerCannotEditAnApprovedVisibilityRow() throws SQLException {
        as(TENANT_A, USER_OWNER, "OWNER", c -> {
            try (Statement s = c.createStatement()) {
                int changed = s.executeUpdate("UPDATE listing_tenant_visibility SET status = 'SUSPENDED'");
                assertThat(changed).isZero();   // UPDATE policy is tenant-only
            }
            return null;
        });
    }

    @Test
    void crossTenantWritesAreRejectedByWithCheck() throws SQLException {
        SQLException ex = assertThrows(SQLException.class, () -> as(TENANT_A, USER_STUDENT_A, "STUDENT", c -> {
            exec(c, "INSERT INTO announcement (tenant_id, title, body) VALUES ('" + TENANT_B + "', 'x', 'y')");
            return null;
        }));
        assertThat(sqlState(ex)).isEqualTo("42501");
        as(TENANT_A, USER_STUDENT_A, "STUDENT", c -> {
            exec(c, "INSERT INTO announcement (tenant_id, title, body) VALUES ('" + TENANT_A + "', 'ok', 'ok')");
            return null;
        });
    }

    @Test
    void listingsCanOnlyBeCreatedFromATenantInTheListingsCity() throws SQLException {
        String insert = "INSERT INTO listing (owner_user_id, city_id, kind, title, address_line, lat, lng) VALUES ('"
                + USER_OWNER + "', '" + CITY_JABALPUR + "', 'ROOM', 't', 'a', 23.2, 79.9)";
        SQLException wrongCity = assertThrows(SQLException.class, () -> as(TENANT_C, USER_OWNER, "OWNER", c -> {
            exec(c, insert);
            return null;
        }));
        assertThat(sqlState(wrongCity)).isEqualTo("42501");
        as(TENANT_A, USER_OWNER, "OWNER", c -> {
            exec(c, insert);
            return null;
        });
    }

    @Test
    void nonOwnersCannotModifyOrDeleteListings() throws SQLException {
        as(TENANT_A, USER_STUDENT_A, "STUDENT", c -> {
            try (Statement s = c.createStatement()) {
                assertThat(s.executeUpdate("UPDATE listing SET title = 'hacked'")).isZero();
                assertThat(s.executeUpdate("UPDATE stay_detail SET rent_monthly = 1")).isZero();
                assertThat(s.executeUpdate("DELETE FROM listing")).isZero();
            }
            return null;
        });
    }

    @Test
    void privilegedTablesAreUnreachableAndAuditLogIsAppendOnly() throws SQLException {
        for (String sql : List.of("SELECT 1 FROM refresh_token", "SELECT 1 FROM auth_code", "SELECT 1 FROM platform_admin",
                "UPDATE audit_log SET action = 'x'", "DELETE FROM audit_log",
                "INSERT INTO city (name, state) VALUES ('x', 'y')")) {
            SQLException ex = assertThrows(SQLException.class, () -> as(TENANT_A, USER_ADMIN_A, "TENANT_ADMIN", c -> {
                exec(c, sql);
                return null;
            }), sql);
            assertThat(sqlState(ex)).as(sql).isEqualTo("42501");
        }
    }

    @Test
    void tenantContextDoesNotSurviveTheTransaction() throws SQLException {
        try (Connection c = TestPostgres.connect("basera_app", "app")) {
            c.setAutoCommit(false);
            setContext(c, TENANT_A, USER_STUDENT_A, "STUDENT");
            assertThat(count(c, "SELECT 1 FROM membership")).isEqualTo(3);
            c.commit();                       // transaction-local setting ends here
            assertThat(count(c, "SELECT 1 FROM membership")).isZero();   // same pooled connection, no leak
            c.rollback();
        }
    }

    @Test
    void compositeForeignKeysBlockCrossTenantReferencesEvenForSuperuser() throws SQLException {
        try (Connection c = TestPostgres.superuser()) {
            SQLException ex = assertThrows(SQLException.class, () -> exec(c,
                    "INSERT INTO expense (tenant_id, membership_id, amount, category, spent_on) VALUES ('" + TENANT_B
                            + "', '" + MEMBER_STUDENT_A + "', 10, 'FOOD', current_date)"));
            assertThat(sqlState(ex)).isEqualTo("23503");
        }
    }

    // ---- meta-tests: a forgotten policy on a FUTURE table fails the build --------------------------------------------

    @Test
    void everyTableWithTenantIdHasForcedRlsAndAPolicy() throws SQLException {
        List<String> offenders = new ArrayList<>();
        try (Connection c = TestPostgres.superuser(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT c.table_name
                     FROM information_schema.columns c
                     JOIN pg_class pc ON pc.relname = c.table_name AND pc.relnamespace = 'public'::regnamespace
                     WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id'
                       AND (NOT pc.relrowsecurity OR NOT pc.relforcerowsecurity
                            OR NOT EXISTS (SELECT 1 FROM pg_policies p
                                           WHERE p.schemaname = 'public' AND p.tablename = c.table_name))""")) {
            while (rs.next()) {
                offenders.add(rs.getString(1));
            }
        }
        assertThat(offenders).as("tables with tenant_id but no forced RLS / policy").isEmpty();
    }

    @Test
    void noTableIsWithoutRlsUnlessExplicitlyAllowListed() throws SQLException {
        List<String> offenders = new ArrayList<>();
        try (Connection c = TestPostgres.superuser(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT relname FROM pg_class
                     WHERE relnamespace = 'public'::regnamespace AND relkind = 'r' AND NOT relrowsecurity
                       AND relname NOT IN ('city', 'tenant', 'platform_admin', 'spatial_ref_sys', 'flyway_schema_history',
                                           'transport_route', 'health_facility', 'emergency_contact',
                                           'safe_area_note', 'bank_kyc_guide')""")) {
            while (rs.next()) {
                offenders.add(rs.getString(1));
            }
        }
        assertThat(offenders).as("tables without RLS that are not on the allow-list").isEmpty();
    }
}

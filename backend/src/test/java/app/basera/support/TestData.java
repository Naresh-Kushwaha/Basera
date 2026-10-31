package app.basera.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Deterministic fixture: 2 colleges in Jabalpur (A, B), 1 in Bhopal (C), 4 users, 1 listing visible to A only. */
public final class TestData {

    public static final UUID CITY_JABALPUR = UUID.fromString("c1000000-0000-0000-0000-000000000001");
    public static final UUID CITY_BHOPAL = UUID.fromString("c2000000-0000-0000-0000-000000000002");

    public static final UUID TENANT_A = UUID.fromString("a0000000-0000-0000-0000-00000000000a");
    public static final UUID TENANT_B = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
    public static final UUID TENANT_C = UUID.fromString("c0000000-0000-0000-0000-00000000000c");

    public static final UUID USER_ADMIN_A = UUID.fromString("11111111-0000-0000-0000-000000000001");
    public static final UUID USER_STUDENT_A = UUID.fromString("11111111-0000-0000-0000-000000000002");
    public static final UUID USER_STUDENT_B = UUID.fromString("11111111-0000-0000-0000-000000000003");
    public static final UUID USER_OWNER = UUID.fromString("11111111-0000-0000-0000-000000000004");

    public static final UUID MEMBER_ADMIN_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID MEMBER_STUDENT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    public static final UUID MEMBER_OWNER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");
    public static final UUID MEMBER_STUDENT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");

    public static final UUID LISTING_1 = UUID.fromString("11000000-0000-0000-0000-000000000001");

    private TestData() {}

    public static void reset() throws SQLException {
        try (Connection c = TestPostgres.superuser(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE city, tenant, user_account, platform_admin, membership, invitation, refresh_token, "
                    + "auth_code, listing, audit_log CASCADE");
            s.execute("""
                    INSERT INTO city (id, name, state) VALUES
                      ('c1000000-0000-0000-0000-000000000001', 'Jabalpur', 'MP'),
                      ('c2000000-0000-0000-0000-000000000002', 'Bhopal', 'MP')""");
            s.execute("""
                    INSERT INTO tenant (id, city_id, slug, name, campus_lat, campus_lng) VALUES
                      ('a0000000-0000-0000-0000-00000000000a', 'c1000000-0000-0000-0000-000000000001', 'gec-a', 'GEC A', 23.2147, 79.9498),
                      ('b0000000-0000-0000-0000-00000000000b', 'c1000000-0000-0000-0000-000000000001', 'gec-b', 'GEC B', 23.1760, 79.9860),
                      ('c0000000-0000-0000-0000-00000000000c', 'c2000000-0000-0000-0000-000000000002', 'manit-c', 'MANIT C', 23.2156, 77.4094)""");
            s.execute("""
                    INSERT INTO user_account (id, google_sub, email, full_name) VALUES
                      ('11111111-0000-0000-0000-000000000001', 'g1', 'admin.a@example.in', 'Admin A'),
                      ('11111111-0000-0000-0000-000000000002', 'g2', 'student.a@example.in', 'Student A'),
                      ('11111111-0000-0000-0000-000000000003', 'g3', 'student.b@example.in', 'Student B'),
                      ('11111111-0000-0000-0000-000000000004', 'g4', 'owner@example.in', 'Owner O')""");
            s.execute("""
                    INSERT INTO membership (id, tenant_id, user_id, role, display_name) VALUES
                      ('aaaaaaaa-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-00000000000a', '11111111-0000-0000-0000-000000000001', 'TENANT_ADMIN', 'Admin A'),
                      ('aaaaaaaa-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-00000000000a', '11111111-0000-0000-0000-000000000002', 'STUDENT', 'Student A'),
                      ('aaaaaaaa-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-00000000000a', '11111111-0000-0000-0000-000000000004', 'OWNER', 'Owner O'),
                      ('bbbbbbbb-0000-0000-0000-000000000003', 'b0000000-0000-0000-0000-00000000000b', '11111111-0000-0000-0000-000000000003', 'STUDENT', 'Student B')""");
            s.execute("""
                    INSERT INTO listing (id, owner_user_id, city_id, kind, title, address_line, lat, lng) VALUES
                      ('11000000-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000004', 'c1000000-0000-0000-0000-000000000001', 'PG', 'PG near GEC A', '12 Napier Town', 23.2150, 79.9500)""");
            s.execute("INSERT INTO stay_detail (listing_id, rent_monthly) VALUES ('11000000-0000-0000-0000-000000000001', 4500)");
            s.execute("""
                    INSERT INTO listing_tenant_visibility (listing_id, tenant_id, city_id, owner_user_id, status) VALUES
                      ('11000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-00000000000a', 'c1000000-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000004', 'APPROVED')""");
        }
    }
}

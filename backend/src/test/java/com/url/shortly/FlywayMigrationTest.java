package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the real Flyway migrations against H2 in PostgreSQL mode.
 *
 * <p>The application test profile uses {@code ddl-auto: create-drop} so it does
 * not exercise the migration files. This test closes that gap: it applies V1
 * through V4, then checks the identity/analytics schema, uniqueness constraints
 * and the foreign-key actions for users, sessions and clicks.
 */
class FlywayMigrationTest {

    private static final String URL =
            "jdbc:h2:mem:shortly_migrations;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    @DisplayName("V1 through V4 apply cleanly and enforce the identity constraints")
    void migrationsApplyCleanly() throws Exception {
        MigrateResult result = Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(result.migrationsExecuted).isEqualTo(4);

        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            assertThat(appliedMigrations(connection)).isEqualTo(4);

            long userId = insertUser(connection, "migration@example.com");

            assertThatThrownBy(() -> insertUser(connection, "migration@example.com"))
                    .isInstanceOf(SQLException.class);

            long sessionId = insertSession(connection, userId, "a".repeat(64), "family-1");
            long secondSessionId = insertSession(connection, userId, "b".repeat(64), "family-1");
            assertThat(sessionId).isNotEqualTo(secondSessionId);

            // A duplicate refresh-token hash must be rejected.
            assertThatThrownBy(() ->
                    insertSession(connection, userId, "a".repeat(64), "family-2"))
                    .isInstanceOf(SQLException.class);

            long linkId = insertLink(connection, "migrate1", userId);
            insertClick(connection, linkId);

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT country, browser FROM click_event")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("US");
                assertThat(rs.getString(2)).isEqualTo("Chrome");
            }

            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM app_user WHERE id = ?")) {
                delete.setLong(1, userId);
                delete.executeUpdate();
            }

            // ON DELETE SET NULL for link owners.
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT owner_id FROM link WHERE short_code = 'migrate1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
            }

            // ON DELETE CASCADE for refresh sessions.
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM auth_session")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }

            // Deleting the link removes its click history with it.
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM link WHERE id = ?")) {
                delete.setLong(1, linkId);
                delete.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM click_event")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    private static int appliedMigrations(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success = TRUE")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static long insertUser(Connection connection, String email) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO app_user
                    (email, password_hash, role, status, created_at, updated_at)
                VALUES (?, ?, 'USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, email);
            insert.setString(2, "$2a$04$placeholder-hash");
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private static long insertSession(Connection connection, long userId,
                                      String tokenHash, String familyId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO auth_session
                    (user_id, refresh_token_hash, family_id, expires_at,
                     last_used_at, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, userId);
            insert.setString(2, tokenHash);
            insert.setString(3, familyId);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private static long insertLink(Connection connection, String shortCode, long ownerId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO link
                    (short_code, destination_url, owner_id, status, custom_alias,
                     created_at, updated_at)
                VALUES (?, 'https://example.com/owned', ?, 'ACTIVE', FALSE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, shortCode);
            insert.setLong(2, ownerId);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private static void insertClick(Connection connection, long linkId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO click_event
                    (link_id, occurred_at, visitor_hash, country, device_type,
                     operating_system, browser)
                VALUES (?, CURRENT_TIMESTAMP, ?, 'US', 'Desktop', 'Windows', 'Chrome')
                """)) {
            insert.setLong(1, linkId);
            insert.setString(2, "a".repeat(64));
            insert.executeUpdate();
        }
    }
}

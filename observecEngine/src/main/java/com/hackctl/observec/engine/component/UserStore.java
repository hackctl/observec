package com.hackctl.observec.engine.component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import com.hackctl.observec.engine.model.User;

import jakarta.annotation.PostConstruct;

@Component
public class UserStore {

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String dbPath;

    public UserStore(JdbcTemplate jdbcTemplate,
                     @Value("${observec.auth.db-path:/observec/observecData/users.db}") String dbPath) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbPath = dbPath;
    }

    @PostConstruct
    public void init() {

        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS users (" +
            " id INTEGER PRIMARY KEY AUTOINCREMENT," +
            " username TEXT UNIQUE NOT NULL," +
            " email TEXT," +
            " password_hash TEXT NOT NULL," +
            " role TEXT NOT NULL CHECK(role IN ('ADMIN','READER'))," +
            " created_at TEXT NOT NULL" +
            ")"
        );

        try {
            jdbcTemplate.queryForObject("SELECT email FROM users LIMIT 1", String.class);
        } catch (Exception e) {
            try {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN email TEXT");
                System.out.println("[observec-auth] Migrated users table: added email column");
            } catch (Exception ignored) {}
        }

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (count == null || count == 0) {
            String defaultUsername = "admin";
            String defaultPassword = "admin";
            String hash = passwordEncoder.encode(defaultPassword);
            jdbcTemplate.update(
                "INSERT INTO users (username, email, password_hash, role, created_at) VALUES (?, ?, ?, ?, ?)",
                defaultUsername, "admin@observec.local", hash, "ADMIN", Instant.now().toString()
            );
            System.out.println("[observec-auth] Created default admin user: username=admin password=admin email=admin@observec.local (please change after first login)");
        }
    }

    private static final RowMapper<User> USER_MAPPER = new RowMapper<User>() {
        @Override
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            User u = new User();
            u.setId(rs.getLong("id"));
            u.setUsername(rs.getString("username"));
            try {
                u.setEmail(rs.getString("email"));
            } catch (SQLException e) {
                u.setEmail("");
            }
            u.setPasswordHash(rs.getString("password_hash"));
            u.setRole(rs.getString("role"));
            String created = rs.getString("created_at");
            try {
                u.setCreatedAt(Instant.parse(created));
            } catch (Exception e) {
                u.setCreatedAt(Instant.now());
            }
            return u;
        }
    };

    public List<User> listAll() {
        return jdbcTemplate.query("SELECT id, username, email, password_hash, role, created_at FROM users ORDER BY id", USER_MAPPER);
    }

    public Optional<User> findByUsername(String username) {
        List<User> list = jdbcTemplate.query(
            "SELECT id, username, email, password_hash, role, created_at FROM users WHERE username = ?",
            USER_MAPPER, username);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<User> findById(Long id) {
        List<User> list = jdbcTemplate.query(
            "SELECT id, username, email, password_hash, role, created_at FROM users WHERE id = ?",
            USER_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<User> findByEmail(String email) {
        List<User> list = jdbcTemplate.query(
            "SELECT id, username, email, password_hash, role, created_at FROM users WHERE email = ?",
            USER_MAPPER, email);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public User createUser(String username, String email, String rawPassword, String role) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("username and password required");
        }
        String normRole = role != null && "ADMIN".equalsIgnoreCase(role) ? "ADMIN" : "READER";
        String hash = passwordEncoder.encode(rawPassword);
        String safeEmail = email != null ? email.trim() : "";
        jdbcTemplate.update(
            "INSERT INTO users (username, email, password_hash, role, created_at) VALUES (?, ?, ?, ?, ?)",
            username.trim(), safeEmail, hash, normRole, Instant.now().toString()
        );
        return findByUsername(username.trim()).orElseThrow();
    }

    public User createUser(String username, String rawPassword, String role) {
        return createUser(username, "", rawPassword, role);
    }

    public boolean deleteUser(Long id) {
        int rows = jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        return rows > 0;
    }

    public boolean updatePassword(Long id, String newRawPassword) {
        String hash = passwordEncoder.encode(newRawPassword);
        int rows = jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE id = ?", hash, id);
        return rows > 0;
    }

    public boolean updateRole(Long id, String newRole) {
        String normRole = "ADMIN".equalsIgnoreCase(newRole) ? "ADMIN" : "READER";
        int rows = jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", normRole, id);
        return rows > 0;
    }

    public boolean updateEmail(Long id, String newEmail) {
        int rows = jdbcTemplate.update("UPDATE users SET email = ? WHERE id = ?", newEmail != null ? newEmail.trim() : "", id);
        return rows > 0;
    }

    public boolean verifyPassword(User user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    public long countAdmins() {
        Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'", Integer.class);
        return c == null ? 0 : c;
    }
}

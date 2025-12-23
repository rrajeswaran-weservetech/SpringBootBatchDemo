package com.example.batch.repository;

import com.example.batch.model.Person;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class PersonRepository {
    private final JdbcTemplate jdbcTemplate;

    public PersonRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CurrentPerson> findCurrentByEmail(String email) {
        String sql = "SELECT TOP 1 id, first_name, last_name, email, age, version, updated_at FROM dbo.persons WHERE email = ? AND is_current = 1";
        return jdbcTemplate.query(sql, rs -> {
            if (rs.next()) {
                return Optional.of(mapCurrent(rs));
            }
            return Optional.empty();
        }, email);
    }

    public void markAllNotCurrent(String email) {
        jdbcTemplate.update("UPDATE dbo.persons SET is_current = 0 WHERE email = ? AND is_current = 1", email);
    }

    public void insert(Person person, int version) {
        jdbcTemplate.update(
                "INSERT INTO dbo.persons(first_name, last_name, email, age, version, is_current, updated_at) VALUES(?,?,?,?,?,1,SYSUTCDATETIME())",
                person.getFirstName(), person.getLastName(), person.getEmail(), person.getAge(), version
        );
    }

    private static CurrentPerson mapCurrent(ResultSet rs) throws SQLException {
        CurrentPerson cp = new CurrentPerson();
        cp.setId(rs.getInt("id"));
        cp.setFirstName(rs.getString("first_name"));
        cp.setLastName(rs.getString("last_name"));
        cp.setEmail(rs.getString("email"));
        int age = rs.getInt("age");
        cp.setAge(rs.wasNull() ? null : age);
        cp.setVersion(rs.getInt("version"));
        cp.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        return cp;
    }

    public static class CurrentPerson extends Person {
        private Integer id;
        private Integer version;
        private LocalDateTime updatedAt;

        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public Integer getVersion() { return version; }
        public void setVersion(Integer version) { this.version = version; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }
}

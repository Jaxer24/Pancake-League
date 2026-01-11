package com.example.websocketsql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;

@Component
public class MessageRepository {
    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS messages (id IDENTITY PRIMARY KEY, content VARCHAR(1024), ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }

    public void save(String content) {
        jdbc.update("INSERT INTO messages(content) VALUES(?)", content);
    }

    public List<String> findAll() {
        return jdbc.query("SELECT content FROM messages ORDER BY id", (rs, rowNum) -> rs.getString(1));
    }
}

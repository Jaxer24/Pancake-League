
package com.example.websocketsql;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class MessageRepository {
    public MessageRepository() {}
    public void save(String content) {}
    public List<String> findAll() { return Collections.emptyList(); }
}

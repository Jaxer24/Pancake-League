package com.example.websocketsql;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Component
public class MessageHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = Collections.synchronizedSet(new HashSet<>());
    private final MessageRepository repository;

    public MessageHandler(MessageRepository repository) {
        this.repository = repository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        // send existing messages
        repository.findAll().forEach(m -> {
            try {
                session.sendMessage(new TextMessage(m));
            } catch (IOException e) {
                // ignore
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        repository.save(payload);
        broadcast(payload);
    }

    private void broadcast(String msg) {
        synchronized (sessions) {
            sessions.forEach(s -> {
                try {
                    if (s.isOpen()) s.sendMessage(new TextMessage(msg));
                } catch (IOException e) {
                    // ignore individual failures
                }
            });
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
    }
}

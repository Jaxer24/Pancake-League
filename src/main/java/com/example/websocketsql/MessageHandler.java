package com.example.websocketsql;

import org.springframework.lang.NonNull;
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
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        sessions.add(session);
        // send existing messages
        repository.findAll().forEach(m -> {
            try {
                @SuppressWarnings("null")
                TextMessage msg = new TextMessage((CharSequence) m);
                session.sendMessage(msg);
            } catch (IOException e) {
                // ignore
            }
        });
    }

    @Override
    @SuppressWarnings("null")
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        String payload = message.getPayload();
        repository.save(payload);
        broadcast(payload);
    }

    private void broadcast(String msg) {
        synchronized (sessions) {
            sessions.forEach(s -> {
                try {
                    @SuppressWarnings("null")
                    TextMessage textMsg = new TextMessage((CharSequence) msg);
                    if (s.isOpen()) s.sendMessage(textMsg);
                } catch (IOException e) {
                    // ignore individual failures
                }
            });
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) throws Exception {
        sessions.remove(session);
    }
}

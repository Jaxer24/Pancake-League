package com.example.websocketsql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class GameHandler extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, PlayerInput> latestInput = new ConcurrentHashMap<>();
    private final GameLoop gameLoop;
    private final MatchManager matchManager;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.ExecutorService sendExecutor = java.util.concurrent.Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "ws-sender"); t.setDaemon(true); return t; });

    public GameHandler(GameLoop gameLoop, MatchManager matchManager) {
        this.gameLoop = gameLoop;
        this.matchManager = matchManager;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, org.springframework.web.socket.TextMessage message) throws Exception {
        String payload = message.getPayload();
        JsonNode node = mapper.readTree(payload);
        String type = node.get("type").asText();
        if ("join".equals(type)) {
            String name = node.get("name").asText();
            sessionsByPlayer.put(name, session);
            // enqueue for matchmaking and assign session if match exists
            matchManager.enqueue(name, session);
            matchManager.assignSessionToMatch(name, session);
            gameLoop.addPlayer(name); // keep global loop as fallback
        } else if ("input".equals(type)) {
            String name = node.get("name").asText();
            int seq = node.has("seq") ? node.get("seq").asInt() : 0;
            double throttle = node.get("throttle").asDouble(0.0);
            double steer = node.get("steer").asDouble(0.0);
            boolean jump = node.get("jump").asBoolean(false);
            boolean boost = node.get("boost").asBoolean(false);
            PlayerInput in = new PlayerInput(seq, throttle, steer, jump, boost);
            latestInput.put(name, in);
            Match m = matchManager.getMatchFor(name);
            if (m != null) {
                m.updateInput(name, new Match.PlayerInput(seq, throttle, steer, jump, boost));
            } else {
                gameLoop.updateInput(name, in);
            }
        }
    }

    public void broadcastState(String json) {
        for (Map.Entry<String, WebSocketSession> e : sessionsByPlayer.entrySet()) {
            String player = e.getKey();
            WebSocketSession s = e.getValue();
            if (s == null) continue;
            sendExecutor.submit(() -> {
                synchronized (s) {
                    try {
                        if (s.isOpen()) s.sendMessage(new TextMessage(json));
                    } catch (IllegalStateException | IOException ex) {
                        // Remove/close broken session to avoid blocking future broadcasts
                        sessionsByPlayer.remove(player);
                        latestInput.remove(player);
                        try { s.close(); } catch (Exception ignored) {}
                    }
                }
            });
        }
    }

    public void removePlayer(String name) {
        sessionsByPlayer.remove(name);
        latestInput.remove(name);
        gameLoop.removePlayer(name);
    }

    public static class PlayerInput {
        public final int seq;
        public final double throttle, steer;
        public final boolean jump, boost;
        public PlayerInput(int seq, double throttle, double steer, boolean jump, boolean boost) {
            this.seq = seq;
            this.throttle = throttle; this.steer = steer; this.jump = jump; this.boost = boost;
        }
    }
}

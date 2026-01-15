package com.example.websocketsql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    @SuppressWarnings("null")
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull org.springframework.web.socket.TextMessage message) throws Exception {
        String payload = message.getPayload();
        System.out.println("[DEBUG] WebSocket message received: " + payload);
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
            boolean brake = node.get("brake").asBoolean(false);
            PlayerInput in = new PlayerInput(seq, throttle, steer, jump, boost, brake);
            latestInput.put(name, in);
            Match m = matchManager.getMatchFor(name);
            if (m != null) {
                m.updateInput(name, new Match.PlayerInput(seq, throttle, steer, jump, boost, brake));
            } else {
                gameLoop.updateInput(name, in);
            }
        } else if ("boostReset".equals(type)) {
            String name = node.get("name").asText();
            // Try to reset boost in match context first
            Match m = matchManager.getMatchFor(name);
            boolean found = false;
            if (m != null) {
                try {
                    java.lang.reflect.Field playersField = m.getClass().getDeclaredField("players");
                    playersField.setAccessible(true);
                    Map<?,?> players = (Map<?,?>) playersField.get(m);
                    Object playerState = players.get(name);
                    if (playerState != null) {
                        java.lang.reflect.Field boostField = playerState.getClass().getDeclaredField("boostFuel");
                        boostField.setAccessible(true);
                        boostField.set(playerState, 100.0);
                        found = true;
                    }
                } catch (Exception ignored) {}
            }
            // If not in match, try global loop
            if (!found) {
                try {
                    java.lang.reflect.Field playersField = gameLoop.getClass().getDeclaredField("players");
                    playersField.setAccessible(true);
                    Map<?,?> players = (Map<?,?>) playersField.get(gameLoop);
                    Object player = players.get(name);
                    if (player != null) {
                        java.lang.reflect.Field boostField = player.getClass().getDeclaredField("boostFuel");
                        boostField.setAccessible(true);
                        boostField.set(player, 100.0);
                    }
                } catch (Exception ignored) {}
            }
        } else if ("ballJump".equals(type)) {
            String name = node.get("name").asText();
            Match m = matchManager.getMatchFor(name);
            if (m != null) {
                m.triggerBallJump();
            }
        }
    }

    public void broadcastState(String json) {
        // Only broadcast global state to players NOT in a match
        for (Map.Entry<String, WebSocketSession> e : sessionsByPlayer.entrySet()) {
            String player = e.getKey();
            // Only send if player is not in a match
            if (matchManager.getMatchFor(player) != null) continue;
            WebSocketSession s = e.getValue();
            if (s == null) continue;
            sendExecutor.submit(() -> {
                synchronized (s) {
                    try {
                        @SuppressWarnings("null")
                        TextMessage msg = new TextMessage((CharSequence) json);
                        if (s.isOpen()) s.sendMessage(msg);
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
        public final boolean jump, boost, brake;
        public PlayerInput(int seq, double throttle, double steer, boolean jump, boolean boost, boolean brake) {
            this.seq = seq;
            this.throttle = throttle; this.steer = steer; this.jump = jump; this.boost = boost; this.brake = brake;
        }
    }
}

package com.example.websocketsql;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

@Component
public class MatchManager {
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<String, Match> playerMatch = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Match> matches = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> pendingSessions = new ConcurrentHashMap<>();
    private final GameRepository repo;

    // Track last activity for each player
    private final ConcurrentMap<String, Long> lastActive = new ConcurrentHashMap<>();
    // Track last activity for each match
    private final ConcurrentMap<String, Long> matchLastActive = new ConcurrentHashMap<>();
    // Cleanup interval and timeout (ms)
    private static final long CLEANUP_INTERVAL_MS = 60_000; // 1 min
    // Separate timeouts for lobby and in-game inactivity
    private static final long LOBBY_TIMEOUT_MS = 2 * 60_000; // 2 min
    private static final long INGAME_TIMEOUT_MS = 2 * 60_000; // 2 min
    private final java.util.concurrent.ScheduledExecutorService cleanupExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    {
        // Start periodic cleanup
        cleanupExecutor.scheduleAtFixedRate(this::cleanupStaleSessions, CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public MatchManager(GameRepository repo) { this.repo = repo; }

    // Remove player from match and all tracking
    public void removePlayerFromMatch(String name) {
        Match m = playerMatch.remove(name);
        if (m != null) {
            m.removePlayer(name);
            System.out.println("[CLEANUP] Removed player " + name + " from match " + m.id);
            // If match is now empty, remove it
            if (m.isEmpty()) {
                matches.remove(m.id);
                matchLastActive.remove(m.id);
                System.out.println("[CLEANUP] Removed empty match " + m.id);
            }
        }
        pendingSessions.remove(name);
        lastActive.remove(name);
        queue.remove(name);
    }

    // Call this on player activity
    public void updatePlayerActivity(String name) {
        lastActive.put(name, System.currentTimeMillis());
        Match m = playerMatch.get(name);
        if (m != null) matchLastActive.put(m.id, System.currentTimeMillis());
    }

    // Cleanup stale sessions and matches
    private void cleanupStaleSessions() {
        long now = System.currentTimeMillis();
        // Clean up players (lobby and in-game)
        for (String name : lastActive.keySet()) {
            long last = lastActive.getOrDefault(name, now);
            boolean inMatch = playerMatch.containsKey(name);
            long timeout = inMatch ? INGAME_TIMEOUT_MS : LOBBY_TIMEOUT_MS;
            if (now - last > timeout) {
                System.out.println("[CLEANUP] Timeout: Removing stale " + (inMatch ? "in-game" : "lobby") + " player " + name);
                removePlayerFromMatch(name);
            }
        }
        // Clean up matches
        for (String matchId : matchLastActive.keySet()) {
            long last = matchLastActive.getOrDefault(matchId, now);
            if (now - last > INGAME_TIMEOUT_MS) {
                Match m = matches.remove(matchId);
                matchLastActive.remove(matchId);
                if (m != null) {
                    System.out.println("[CLEANUP] Timeout: Removing stale match " + matchId);
                    m.removeAllPlayers();
                }
            }
        }
    }

    public synchronized void enqueue(String name, WebSocketSession session) {
        if (playerMatch.containsKey(name)) return;
        pendingSessions.put(name, session);
        queue.add(name);

        updatePlayerActivity(name);

        // Check for any existing single-player matches
        String otherWaiting = null;
        for (String queued : queue) {
            if (!queued.equals(name)) {
                otherWaiting = queued;
                break;
            }
        }
        if (otherWaiting != null) {
            // Remove both from queue
            queue.remove(name);
            queue.remove(otherWaiting);
            // Create or reuse a match
            Match m = new Match(repo);
            matches.put(m.id, m);
            playerMatch.put(name, m);
            playerMatch.put(otherWaiting, m);
            WebSocketSession sa = pendingSessions.remove(name);
            WebSocketSession sb = pendingSessions.remove(otherWaiting);
            if (sa != null) m.addPlayer(name, sa);
            if (sb != null) m.addPlayer(otherWaiting, sb);
            return;
        }
        tryPair();
    }

    private void tryPair() {
        if (queue.size() >= 2) {
            String a = queue.poll();
            String b = queue.poll();
            Match m = new Match(repo);
            matches.put(m.id, m);
            playerMatch.put(a, m);
            playerMatch.put(b, m);
            WebSocketSession sa = pendingSessions.remove(a);
            WebSocketSession sb = pendingSessions.remove(b);
            if (sa != null) m.addPlayer(a, sa);
            if (sb != null) m.addPlayer(b, sb);
        }
    }

    public Match getMatchFor(String name) { return playerMatch.get(name); }

    // For cleanup: check if match is empty
    // (Add this method to Match class if not present)

    public void assignSessionToMatch(String name, WebSocketSession session) {
        Match m = playerMatch.get(name);
        if (m != null) m.addPlayer(name, session);
        updatePlayerActivity(name);
    }

}

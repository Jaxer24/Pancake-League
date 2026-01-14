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

    public MatchManager(GameRepository repo) { this.repo = repo; }

    public synchronized void enqueue(String name, WebSocketSession session) {
        if (playerMatch.containsKey(name)) return;
        pendingSessions.put(name, session);
        queue.add(name);

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

    public void assignSessionToMatch(String name, WebSocketSession session) {
        Match m = playerMatch.get(name);
        if (m != null) m.addPlayer(name, session);
    }

}

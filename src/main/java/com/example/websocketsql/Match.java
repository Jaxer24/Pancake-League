package com.example.websocketsql;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Match {
    public final String id = UUID.randomUUID().toString();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<String, PlayerInput> inputs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "match-sender-"+id); t.setDaemon(true); return t; });
    private final GameRepository repo;
    private int tickCounter = 0;
    private static final int BROADCAST_SKIP = 1; // send state every N ticks

    public Match(GameRepository repo) {
        this.repo = repo;
        executor.scheduleAtFixedRate(this::tick, 0, 33, TimeUnit.MILLISECONDS);
    }

    public void addPlayer(String name, WebSocketSession session) {
        sessions.put(name, session);
        players.putIfAbsent(name, PlayerState.spawn(name));
        // notify this session that it has been matched
        try {
            String json = "{\"type\":\"matched\",\"match\":\"" + id + "\",\"name\":\"" + name + "\"}";
            if (session != null && session.isOpen()) session.sendMessage(new TextMessage(json));
        } catch (IOException ignored) {}
        // if two players present, notify both with opponent name
        if (sessions.size() == 2) {
            String[] names = sessions.keySet().toArray(new String[0]);
            String a = names[0], b = names[1];
            WebSocketSession sa = sessions.get(a); WebSocketSession sb = sessions.get(b);
            try {
                String ja = "{\"type\":\"matched\",\"match\":\""+id+"\",\"name\":\""+a+"\",\"opponent\":\""+b+"\"}";
                String jb = "{\"type\":\"matched\",\"match\":\""+id+"\",\"name\":\""+b+"\",\"opponent\":\""+a+"\"}";
                if (sa != null && sa.isOpen()) sa.sendMessage(new TextMessage(ja));
                if (sb != null && sb.isOpen()) sb.sendMessage(new TextMessage(jb));
            } catch (IOException ignored) {}
        }
    }

    public void removePlayer(String name) {
        sessions.remove(name);
        players.remove(name);
        inputs.remove(name);
    }

    public void updateInput(String name, PlayerInput in) {
        if (in == null) return;
        inputs.put(name, in);
        // latch jump to avoid missed single-frame presses between network/tick boundaries
        PlayerState p = players.get(name);
        if (p != null && in.seq != 0 && in.jump) p.pendingJump = true;
    }

    private void tick() {
        try {
            long t0 = System.nanoTime();
            double dt = 0.033;
            long tickId = System.currentTimeMillis();
            tickCounter++;
            // always advance physics and persist positions each tick
            for (Map.Entry<String, PlayerState> e : players.entrySet()) {
                String name = e.getKey();
                PlayerState p = e.getValue();
                PlayerInput in = inputs.getOrDefault(name, new PlayerInput(0,0,0,false,false));
                p.applyInput(in, dt);
                p.lastAppliedSeq = in.seq;
                repo.enqueuePosition(name, tickId, p.x, p.y, p.z, p.vx, p.vy, p.vz);
            }
            // only build and broadcast visual state every BROADCAST_SKIP ticks
            if (tickCounter % BROADCAST_SKIP == 0) {
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                sb.append("\"type\":\"state\",");
                sb.append("\"match\":\"").append(id).append("\",");
                sb.append("\"tick\":").append(tickId).append(',');
                sb.append("\"players\":[");
                boolean first = true;
                for (Map.Entry<String, PlayerState> e : players.entrySet()) {
                    String name = e.getKey();
                    PlayerState p = e.getValue();
                    if (!first) sb.append(','); first = false;
                    sb.append('{')
                        .append("\"name\":\"").append(name).append("\",")
                        .append("\"x\":").append(p.x).append(',')
                        .append("\"y\":").append(p.y).append(',')
                        .append("\"z\":").append(p.z).append(',')
                        .append("\"angle\":").append(p.angle).append(',')
                        .append("\"lastSeq\":").append(p.lastAppliedSeq).append('}');
                }
                sb.append(']');
                sb.append('}');
                broadcastState(sb.toString());
            }
            long t1 = System.nanoTime();
            long ms = (t1 - t0) / 1_000_000;
            if (ms > 10) {
                System.out.println("[Match] id=" + id + " tick=" + tickCounter + " players=" + players.size() + " durationMs=" + ms + " dbQueue=" + repo.getQueueSize());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void broadcastState(String json) {
        for (Map.Entry<String, WebSocketSession> e : sessions.entrySet()) {
            String player = e.getKey();
            WebSocketSession s = e.getValue();
            if (s == null) continue;
            sendExecutor.submit(() -> {
                synchronized (s) {
                    try {
                        if (s.isOpen()) s.sendMessage(new TextMessage(json));
                    } catch (IllegalStateException | IOException ex) {
                        // remove and close broken session
                        sessions.remove(player);
                        inputs.remove(player);
                        players.remove(player);
                        try { s.close(); } catch (Exception ignored) {}
                    }
                }
            });
        }
    }

    private static class PlayerState {
        final String name; double x,y,vx,vy,angle,z,vz; double boostFuel=100; int lastAppliedSeq = 0; boolean pendingJump = false;
        PlayerState(String name){this.name=name;}
        static PlayerState spawn(String name){ PlayerState p=new PlayerState(name); p.x=Math.random()*400+100; p.y=Math.random()*200+100; p.angle=0; return p; }
        void applyInput(PlayerInput in, double dt){
            double forward = in.throttle;
            // use same physics as global GameLoop (no extra match boost)
            double accel = 700*forward*dt;
            if (in.boost && boostFuel>0){accel+=800*dt; boostFuel-=40*dt;}
            double fx=Math.cos(angle), fy=Math.sin(angle);
            vx+=fx*accel; vy+=fy*accel;
            // steering matches GameLoop
            double steerFactor = 3.0 * (1.0 + Math.hypot(vx,vy) / 300.0);
            angle += in.steer * steerFactor * dt;
            double damping = Math.max(0.0, 1.0 - 1.2 * dt);
            vx *= damping; vy *= damping;
            x+=vx*dt; y+=vy*dt;
            // screen bounds (scale hitbox with vertical height to match visuals)
            double scale = 1.0 + Math.min(1.0, z * 0.12);
            double radius = 20.0 * scale;
            double minX = radius, maxX = 800 - radius;
            double minY = radius, maxY = 600 - radius;
            if (x < minX) { x = minX; vx = -vx * 0.5; } else if (x > maxX) { x = maxX; vx = -vx * 0.5; }
            if (y < minY) { y = minY; vy = -vy * 0.5; } else if (y > maxY) { y = maxY; vy = -vy * 0.5; }
            if (Math.abs(vx) < 1.0) vx = 0.0; if (Math.abs(vy) < 1.0) vy = 0.0;
            // handle jump via pending latch to avoid missing single-frame presses
            // jump velocity matches GameLoop
            if (pendingJump && z<=0.001) { vz = 42; pendingJump = false; }
            if (in.jump && z<=0.001) vz=42;
            vz -= 100.0 * dt; z += vz * dt; if (z < 0) { z = 0; vz = 0; }
        }
    }

    // simple serializable input container
    public static class PlayerInput { public final int seq; public final double throttle, steer; public final boolean jump, boost; public PlayerInput(int seq,double t,double s,boolean j,boolean b){ this.seq=seq; throttle=t;steer=s;jump=j;boost=b;} }
}

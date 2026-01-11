package com.example.websocketsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class GameLoop {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    private final Map<String, PlayerInput> inputs = new ConcurrentHashMap<>();
    private final GameRepository repo;
    private final GameHandler handler;
    private final MatchManager matchManager;
    private final ObjectMapper mapper = new ObjectMapper();

    private long tick = 0;
    private static final int BROADCAST_SKIP = 1; // send state every N ticks

    public GameLoop(GameRepository repo, @Lazy GameHandler handler, MatchManager matchManager) {
        this.repo = repo;
        this.handler = handler;
        this.matchManager = matchManager;
    }

    // profiling helpers
    public long getTick() { return tick; }
    public int getPlayerCount() { return players.size(); }
    public int getQueueSize() { return repo.getQueueSize(); }

    @PostConstruct
    public void start() {
        executor.scheduleAtFixedRate(this::tick, 0, 33, TimeUnit.MILLISECONDS); // ~30 Hz
    }

    public void addPlayer(String name) {
        players.putIfAbsent(name, Player.spawn(name));
    }

    public void removePlayer(String name) {
        players.remove(name);
        inputs.remove(name);
    }

    public void updateInput(String name, GameHandler.PlayerInput in) {
        if (in == null) return;
        inputs.put(name, new PlayerInput(in.seq, in.throttle, in.steer, in.jump, in.boost));
        Player p = players.get(name);
        if (p != null && in.jump) p.pendingJump = true;
    }

    private void tick() {
        try {
            long t0 = System.nanoTime();
            tick++;
            double dt = 0.033; // seconds per tick (~33 ms)
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"type\":\"state\",");
            sb.append("\"match\":null,");
            sb.append("\"tick\":").append(tick).append(',');
            sb.append("\"players\":[");
            boolean first = true;
            for (Map.Entry<String, Player> e : players.entrySet()) {
                String name = e.getKey();
                // if this player is assigned to a match, skip global loop for them
                if (matchManager != null && matchManager.getMatchFor(name) != null) continue;
                Player p = e.getValue();
                PlayerInput in = inputs.getOrDefault(name, new PlayerInput(0,0,0,false,false));
                p.applyInput(in, dt);
                p.lastAppliedSeq = in.seq;
                repo.enqueuePosition(name, tick, p.x, p.y, p.z, p.vx, p.vy, p.vz);
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
            if (tick % BROADCAST_SKIP == 0) {
                handler.broadcastState(sb.toString());
            }
            long t1 = System.nanoTime();
            long ms = (t1 - t0) / 1_000_000;
            if (ms > 10) {
                System.out.println("[GameLoop] tick=" + tick + " players=" + players.size() + " durationMs=" + ms + " dbQueue=" + repo.getQueueSize());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static class PlayerInput {
        final int seq; final double throttle, steer; final boolean jump, boost;
        PlayerInput(int seq, double t, double s, boolean j, boolean b) { this.seq=seq; throttle=t; steer=s; jump=j; boost=b; }
    }

    private static class Player {
        final String name;
        double x,y, vx,vy, angle, z, vz; // z is vertical
        int lastAppliedSeq = 0;
        double boostFuel = 100.0;
        // pending jump latch to avoid missed presses
        boolean pendingJump = false;

        private Player(String name) { this.name = name; }

        static Player spawn(String name) {
            Player p = new Player(name);
            p.x = Math.random()*400+100; p.y = Math.random()*200+100; p.angle = 0; return p;
        }

        void applyInput(PlayerInput in, double dt) {
            double forward = in.throttle;
            // single-player: slightly reduced acceleration so two-player matches feel comparable
            double accelMag = 700.0 * forward * dt; // reduced base acceleration for single-player
            if (in.boost && boostFuel > 0) { accelMag += 800.0 * dt; boostFuel -= 80.0 * dt; }

            double facingX = Math.cos(angle);
            double facingY = Math.sin(angle);
            vx += facingX * accelMag;
            vy += facingY * accelMag;

            double speed = Math.hypot(vx, vy);

            // tuned steering for single-player (a bit gentler)
            double steerFactor = 3.0 * (1.0 + speed / 300.0);
            angle += in.steer * steerFactor * dt;

            // braking: if throttle opposes forward velocity, apply stronger deceleration
            double forwardVelocity = facingX * vx + facingY * vy;
            if (forward != 0 && Math.signum(forward) != Math.signum(forwardVelocity) && Math.abs(forwardVelocity) > 0.1) {
                vx *= 0.75;
                vy *= 0.75;
            } else {
                // tuned damping: gentler damping so momentum feels snappier
                double damping = Math.max(0.0, 1.0 - 1.2 * dt);
                vx *= damping;
                vy *= damping;
            }

            // integrate position
            x += vx * dt;
            y += vy * dt;

            // screen bounds collision (scale hitbox with vertical height to match visuals)
            double scale = 1.0 + Math.min(1.0, z * 0.12);
            double radius = 20.0 * scale;
            double minX = radius, maxX = 800 - radius;
            double minY = radius, maxY = 600 - radius;
            if (x < minX) {
                x = minX;
                vx = -vx * 0.5; // damped bounce
            } else if (x > maxX) {
                x = maxX;
                vx = -vx * 0.5;
            }
            if (y < minY) {
                y = minY;
                vy = -vy * 0.5;
            } else if (y > maxY) {
                y = maxY;
                vy = -vy * 0.5;
            }
            // stop tiny velocities to avoid perpetual micro-bounce
            if (Math.abs(vx) < 1.0) vx = 0.0;
            if (Math.abs(vy) < 1.0) vy = 0.0;

            // jump initiation: consume pendingJump if on ground
            if (pendingJump && z <= 0.001) {
                // single-player jump velocity (higher)
                vz = 42.0;
                pendingJump = false;
                System.out.println("[GameLoop] Jump triggered for player=" + name + " z=" + z + " vx=" + vx + " vy=" + vy);
            }

            // apply gravity and small air assist if boosting; stronger gravity shortens airtime
            vz -= 100.0 * dt; // gravity
            if (in.boost && z > 0.001 && boostFuel > 0) {
                vz += 6.0 * dt;
                boostFuel -= 20.0 * dt;
            }

            z += vz * dt;
            if (z < 0) { z = 0; vz = 0; pendingJump = false; }

            // cap speed to avoid runaway
            double maxSpeed = 1100.0;
            if (speed > maxSpeed) {
                double speedScale = maxSpeed / speed;
                vx *= speedScale;
                vy *= speedScale;
            }
        }
    }
}

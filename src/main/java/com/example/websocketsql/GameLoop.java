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
    @SuppressWarnings("unused")
    private final ObjectMapper mapper = new ObjectMapper();

    // Add a ball for single player practice
    private Ball ball = new Ball();
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
        inputs.put(name, new PlayerInput(in.seq, in.throttle, in.steer, in.jump, in.boost, in.brake));
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

            boolean anyPlayer = false;
            for (Map.Entry<String, Player> e : players.entrySet()) {
                String name = e.getKey();
                if (matchManager != null && matchManager.getMatchFor(name) != null) continue;
                anyPlayer = true;
                Player p = e.getValue();
                PlayerInput in = inputs.getOrDefault(name, new PlayerInput(0,0,0,false,false,false));
                p.applyInput(in, dt);
                p.lastAppliedSeq = in.seq;
                repo.enqueuePosition(name, tick, p.x, p.y, p.z, p.vx, p.vy, p.vz);
            }

            // --- Ball update and collision logic from Match.java ---
            if (anyPlayer) {
                ball.update(dt);
                // Ball hitbox scaling (Match.java logic)
                double ballScale = 1.0 + Math.min(1.0, ball.z * 0.12);
                double ballRadius = 20.0 * ballScale;
                // Ball-wall collision (allow entry into goal zones, restrict only at outer canvas edges)
                boolean inRightGoal = (ball.x > 920 && ball.y > 210 && ball.y < 390);
                boolean inLeftGoal = (ball.x < 120 && ball.y > 210 && ball.y < 390);
                double ballMaxX = inRightGoal ? 1040 : 1040 - ballRadius;
                double ballMinX = inLeftGoal ? 0 : ballRadius;
                int fieldLeft = 120, fieldRight = 1040 - 120, width = 1040;
                if (ball.z > 6.5) {
                    if (ball.x - ballRadius < fieldLeft) {
                        ball.x = fieldLeft + ballRadius;
                        ball.vx = Math.abs(ball.vx);
                    } else if (ball.x + ballRadius > fieldRight) {
                        ball.x = fieldRight - ballRadius;
                        ball.vx = -Math.abs(ball.vx);
                    }
                } else {
                    if (ball.x < ballMinX) { ball.x = ballMinX; ball.vx = -ball.vx * 0.8; }
                    if (ball.x > ballMaxX) { ball.x = ballMaxX; ball.vx = -ball.vx * 0.8; }
                }
                if (ball.y < ballRadius) { ball.y = ballRadius; ball.vy = -ball.vy * 0.8; }
                if (ball.y > 600 - ballRadius) { ball.y = 600 - ballRadius; ball.vy = -ball.vy * 0.8; }

                // --- GREEN PATCH (CORNER) COLLISION LOGIC FOR BALL ---
                double[][] patches = {
                    {0, 0, 120, 120},           // top-left
                    {920, 0, 120, 120},         // top-right
                    {0, 480, 120, 120},         // bottom-left
                    {920, 480, 120, 120}        // bottom-right
                };
                for (double[] patch : patches) {
                    double px = patch[0], py = patch[1], pw = patch[2], ph = patch[3];
                    double closestX = Math.max(px, Math.min(ball.x, px + pw));
                    double closestY = Math.max(py, Math.min(ball.y, py + ph));
                    double dx = ball.x - closestX, dy = ball.y - closestY;
                    if ((dx * dx + dy * dy) <= (ballRadius * ballRadius)) {
                        double len = Math.hypot(dx, dy);
                        double nx = (len == 0) ? 1 : dx / len;
                        double ny = (len == 0) ? 0 : dy / len;
                        // Clamp to edge
                        ball.x = closestX + nx * (ballRadius + 0.1);
                        ball.y = closestY + ny * (ballRadius + 0.1);
                        // Reflect and dampen normal velocity, keep tangent (like wall)
                        double vdotn = ball.vx * nx + ball.vy * ny;
                        double vnormx = nx * vdotn;
                        double vnormy = ny * vdotn;
                        double vtangx = ball.vx - vnormx;
                        double vtangy = ball.vy - vnormy;
                        double bounce = 0.5;
                        double reflectedVnormx = -vnormx * bounce;
                        double reflectedVnormy = -vnormy * bounce;
                        ball.vx = vtangx + reflectedVnormx;
                        ball.vy = vtangy + reflectedVnormy;
                        break;
                    }
                }

                // Now handle ball-player collision after all movement updates
                for (Map.Entry<String, Player> e : players.entrySet()) {
                    String name = e.getKey();
                    if (matchManager != null && matchManager.getMatchFor(name) != null) continue;
                    Player p = e.getValue();
                    double dz = p.z - ball.z;
                    double zThreshold = 3.0;
                    if (Math.abs(dz) > zThreshold) continue;
                    double pScale = 1.0 + Math.min(1.0, p.z * 0.12);
                    double pRadius = 24.0 * pScale;
                    double bScale = 1.0 + Math.min(1.0, ball.z * 0.12);
                    double bRadius = 20.0 * bScale;
                    double dx = ball.x - p.x, dy = ball.y - p.y;
                    double carSpeed = Math.hypot(p.vx, p.vy);
                    double minDist = pRadius + bRadius;
                    double dist = Math.hypot(dx, dy);
                    if (dist < minDist) {
                        double nx = dx / dist;
                        double ny = dy / dist;
                        if (carSpeed > 400.0) {
                            // High-speed: launch ball and slow car (jump)
                            double vxNorm = p.vx / carSpeed;
                            double vyNorm = p.vy / carSpeed;
                            double transferVx = vxNorm * (carSpeed * 1.2);
                            double transferVy = vyNorm * (carSpeed * 1.2);
                            p.vx *= 0.1;
                            p.vy *= 0.1;
                            ball.vx += transferVx;
                            ball.vy += transferVy;
                            // Ball jump effect if hit hard and on ground
                            if (ball.z <= 0.001) {
                                ball.vz = 60; // or adjust for desired jump height
                            }
                        } else if (carSpeed > 200.0) {
                            // Medium-speed: dramatic velocity transfer, no jump, car slows a lot
                            double vxNorm = p.vx / carSpeed;
                            double vyNorm = p.vy / carSpeed;
                            double transferVx = vxNorm * (carSpeed * 1.1);
                            double transferVy = vyNorm * (carSpeed * 1.1);
                            p.vx *= 0.05;
                            p.vy *= 0.05;
                            ball.vx += transferVx;
                            ball.vy += transferVy;
                        } else {
                            // Low-speed: use 2D elastic collision (equal mass)
                            double rvx = ball.vx - p.vx;
                            double rvy = ball.vy - p.vy;
                            double relVelAlongNormal = rvx * nx + rvy * ny;
                            if (relVelAlongNormal < 0) {
                                double ballVn = ball.vx * nx + ball.vy * ny;
                                double ballVt = -ball.vx * ny + ball.vy * nx;
                                double carVn = p.vx * nx + p.vy * ny;
                                double carVt = -p.vx * ny + p.vy * nx;
                                double newBallVn = carVn;
                                double newCarVn = ballVn;
                                double bounce = 0.85;
                                newBallVn *= bounce;
                                newCarVn *= bounce;
                                ball.vx = newBallVn * nx - ballVt * ny;
                                ball.vy = newBallVn * ny + ballVt * nx;
                                p.vx = newCarVn * nx - carVt * ny;
                                p.vy = newCarVn * ny + carVt * nx;
                            }
                        }
                        // Only push the ball away from the player; never modify the player's position
                        ball.x = p.x + nx * minDist;
                        ball.y = p.y + ny * minDist;
                    }
                }
            } else {
                ball.reset();
            }

            // --- Output state ---
            sb.append("\"ball\":{")
                .append("\"x\":").append(ball.x).append(",\"y\":").append(ball.y).append(",\"z\":").append(ball.z).append("},");
            sb.append("\"players\":[");
            boolean first = true;
            for (Map.Entry<String, Player> e : players.entrySet()) {
                String name = e.getKey();
                if (matchManager != null && matchManager.getMatchFor(name) != null) continue;
                Player p = e.getValue();
                if (!first) sb.append(','); first = false;
                sb.append('{')
                    .append("\"name\":\"").append(name).append("\",")
                    .append("\"x\":").append(p.x).append(',')
                    .append("\"y\":").append(p.y).append(',')
                    .append("\"z\":").append(p.z).append(',')
                    .append("\"angle\":").append(p.angle).append(',')
                    .append("\"lastSeq\":").append(p.lastAppliedSeq).append(',')
                    .append("\"boostFuel\":").append(p.boostFuel).append('}');
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
    // Ball class copied from Match.java
    private static class Ball {
        double x = 520, y = 300, vx = 0, vy = 0;
        double z = 0, vz = 0;
        void update(double dt) {
            double speed = Math.hypot(vx, vy);
            double minSpeed = 20.0; // units/second
            if (speed > minSpeed) {
                vx *= 0.985; // less friction for ball at high speeds
                vy *= 0.985;
            } else {
                vx *= 0.995; // less friction for ball at low speeds
                vy *= 0.995;
            }
            x += vx * dt;
            y += vy * dt;
            // Ball jump/vertical physics (Match.java uses -150.0 * dt for gravity)
            vz -= 150.0 * dt;
            z += vz * dt;
            if (z < 0) { z = 0; vz = 0; }
        }
        void reset() { x = 520; y = 300; vx = 0; vy = 0; z = 0; vz = 0; }
    }

    private static class PlayerInput {
        final int seq; final double throttle, steer; @SuppressWarnings("unused") final boolean jump, boost, brake;
        PlayerInput(int seq, double t, double s, boolean j, boolean b, boolean br) { this.seq=seq; throttle=t; steer=s; jump=j; boost=b; brake=br; }
    }

    private static class Player {
        final String name;
        double x,y, vx,vy, angle, z, vz; // z is vertical
        int lastAppliedSeq = 0;
        double boostFuel = 100.0;
        // pending jump latch to avoid missed presses
        boolean pendingJump = false;
        boolean boostLocked = false;

        private Player(String name) { this.name = name; }

        static Player spawn(String name) {
            Player p = new Player(name);
            p.x = Math.random()*400+100; p.y = Math.random()*200+100; p.angle = 0; return p;
        }

        void applyInput(PlayerInput in, double dt) {
            double forward = in.throttle;
            boolean boostLockActive = false;
            if (in.jump && z <= 0.001) {
                boostLocked = true;
            }
            if (!in.boost) {
                boostLocked = false;
            }
            if (in.boost && boostFuel > 0 && z > 0.001 && !boostLocked) {
                boostLockActive = true;
            }
            double accelMag = 700.0 * forward * dt;
            if (in.boost && boostFuel > 0 && !boostLocked) { accelMag += 800.0 * dt; boostFuel -= 40.0 * dt; }
            if (in.brake) {
                accelMag = 0;
            }
            double carRadius = 24.0;
            double[][] patches = {
                {0, 0, 120, 120},           // top-left
                {920, 0, 120, 120},         // top-right
                {0, 480, 120, 120},         // bottom-left
                {920, 480, 120, 120}        // bottom-right
            };
            boolean blocked = false;
            for (double[] patch : patches) {
                double px = patch[0], py = patch[1], pw = patch[2], ph = patch[3];
                double closestX = Math.max(px, Math.min(x, px + pw));
                double closestY = Math.max(py, Math.min(y, py + ph));
                double dx = x - closestX, dy = y - closestY;
                if ((dx * dx + dy * dy) <= (carRadius * carRadius)) {
                    double len = Math.hypot(dx, dy);
                    double nx = (len == 0) ? 1 : dx / len;
                    double ny = (len == 0) ? 0 : dy / len;
                    x = closestX + nx * (carRadius + 0.1);
                    y = closestY + ny * (carRadius + 0.1);
                    double vdotn = vx * nx + vy * ny;
                    double vnormx = nx * vdotn;
                    double vnormy = ny * vdotn;
                    double vtangx = vx - vnormx;
                    double vtangy = vy - vnormy;
                    double bounce = 0.5;
                    double reflectedVnormx = -vnormx * bounce;
                    double reflectedVnormy = -vnormy * bounce;
                    vx = vtangx + reflectedVnormx;
                    vy = vtangy + reflectedVnormy;
                    break;
                }
            }
            double facingX = Math.cos(angle);
            double facingY = Math.sin(angle);
            double regularSpeed = 700.0 * dt;
            this.vx += facingX * accelMag;
            this.vy += facingY * accelMag;
            double speed = Math.hypot(this.vx, this.vy);
            if (speed <= regularSpeed + 1.0) {
                double proj = this.vx * facingX + this.vy * facingY;
                this.vx = facingX * proj;
                this.vy = facingY * proj;
            }
            if (Math.abs(forward) < 0.01 && speed <= regularSpeed + 1.0) {
                this.vx *= 0.7;
                this.vy *= 0.7;
            }
            if (in.brake) {
                this.vx *= 0.85;
                this.vy *= 0.85;
                if (in.throttle != 0) {
                    double slowSpeed = 180.0;
                    this.vx = facingX * slowSpeed * Math.signum(in.throttle);
                    this.vy = facingY * slowSpeed * Math.signum(in.throttle);
                }
            }
            this.vx += facingX * accelMag;
            this.vy += facingY * accelMag;
            speed = Math.hypot(this.vx, this.vy);
            if (speed <= regularSpeed + 1.0) {
                double proj = this.vx * facingX + this.vy * facingY;
                this.vx = facingX * proj;
                this.vy = facingY * proj;
            }
            double steerFactor = 2.0 * (1.0 + speed / 300.0);
            if (in.brake) {
                steerFactor *= 2;
            }
            if (in.steer != 0) {
                angle += in.steer * steerFactor * dt;
            }
            double damping = Math.max(0.0, 1.0 - 4.0 * dt);
            this.vx *= damping; this.vy *= damping;
            x += this.vx * dt; y += this.vy * dt;
            double scale = 1.0 + Math.min(1.0, z * 0.12);
            double radius = 20.0 * scale;
            boolean inRightGoal = (x > 920 && y > 210 && y < 390);
            boolean inLeftGoal = (x < 120 && y > 210 && y < 390);
            double maxX = inRightGoal ? 1040 : 1040 - radius;
            double minX = inLeftGoal ? 0 : radius;
            double minY = radius, maxY = 600 - radius;
            if (x < minX) { x = minX; this.vx = -this.vx * 0.5; }
            if (x > maxX) { x = maxX; this.vx = -this.vx * 0.5; }
            if (y < minY) { y = minY; this.vy = -this.vy * 0.5; }
            if (y > maxY) { y = maxY; this.vy = -this.vy * 0.5; }
            if (Math.abs(this.vx) < 1.0) this.vx = 0.0; if (Math.abs(this.vy) < 1.0) this.vy = 0.0;
            if (pendingJump && z<=0.001) { vz = 42; pendingJump = false; }
            if (in.jump && z<=0.001) {
                vz = 42;
            }
            if (boostLockActive) {
                // Lock z/vz while boosting in air with fuel
                // Do not update vz/z, just freeze at current values
            } else {
                vz -= 120.0 * dt;
                z += vz * dt;
                if (z < 0) { z = 0; vz = 0; }
            }
        }
    }
}

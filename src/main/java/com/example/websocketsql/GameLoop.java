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

            // --- Ball physics and logic copied from Match.java ---
            // Ball update (gravity, friction, wall/goal collision, jump, etc.)
            boolean anyPlayer = false;
            for (Map.Entry<String, Player> e : players.entrySet()) {
                String name = e.getKey();
                // if this player is assigned to a match, skip global loop for them
                if (matchManager != null && matchManager.getMatchFor(name) != null) continue;
                anyPlayer = true;
                Player p = e.getValue();
                PlayerInput in = inputs.getOrDefault(name, new PlayerInput(0,0,0,false,false,false));
                p.applyInput(in, dt);
                p.lastAppliedSeq = in.seq;
                repo.enqueuePosition(name, tick, p.x, p.y, p.z, p.vx, p.vy, p.vz);
            }

            // --- Car-ball collision and jump logic (EXACT from Match.java) ---
            for (Player p : players.values()) {
                if (matchManager != null && matchManager.getMatchFor(p.name) != null) continue;
                double dz = p.z - ball.z;
                double zThreshold = 3.0;
                if (Math.abs(dz) > zThreshold) continue;
                double pScale = 1.0 + Math.min(1.0, p.z * 0.12);
                double pRadius = 24.0 * pScale;
                double ballRadius = 30.0 * (1.0 + Math.min(1.0, ball.z * 0.12));
                double dx = ball.x - p.x, dy = ball.y - p.y;
                double dist = Math.hypot(dx, dy);
                double minDist = pRadius + ballRadius;
                if (dist < minDist && dist > 0.0001) {
                    double nx = dx / dist;
                    double ny = dy / dist;
                    double overlap = minDist - dist;
                    // Move each half the overlap
                    ball.x += nx * (overlap / 2.0);
                    ball.y += ny * (overlap / 2.0);
                    p.x -= nx * (overlap / 2.0);
                    p.y -= ny * (overlap / 2.0);
                    // Reflect velocities (elastic, with damping)
                    double v1n = ball.vx * nx + ball.vy * ny;
                    double v2n = p.vx * nx + p.vy * ny;
                    if (v2n - v1n > 0) {
                        double bounce = 0.8;
                        double v1nNew = v2n * bounce;
                        double v2nNew = v1n * bounce;
                        ball.vx += (v1nNew - v1n) * nx;
                        ball.vy += (v1nNew - v1n) * ny;
                        p.vx += (v2nNew - v2n) * nx;
                        p.vy += (v2nNew - v2n) * ny;
                    }
                    // Ball jump logic: only if car is on ground, ball is on ground, car speed > 220, car moving toward ball
                    double carSpeed = Math.hypot(p.vx, p.vy);
                    double preVx = p.vx - (v2n - v2n * 0.8) * nx;
                    double preVy = p.vy - (v2n - v2n * 0.8) * ny;
                    double preSpeed = Math.hypot(preVx, preVy);
                    double dot = (ball.x - p.x) * preVx + (ball.y - p.y) * preVy;
                    boolean brake = inputs.getOrDefault(p.name, new PlayerInput(0,0,0,false,false,false)).brake;
                    if (p.z < 0.5 && ball.z < 0.5 && carSpeed > 400.0 && !brake) {
                        ball.vz = 60.0;
                        System.out.println("[DEBUG] Ball jump TRIGGERED: player=" + p.name + " ball.vz=" + ball.vz + " carSpeed=" + carSpeed);
                    } else if (p.z < 0.5 && ball.z < 0.5 && carSpeed > 200.0 && !brake) {
                        // Medium-speed: dramatic velocity transfer, no jump
                        double vxNorm = p.vx / carSpeed;
                        double vyNorm = p.vy / carSpeed;
                        double transferVx = vxNorm * (carSpeed * 1.1);
                        double transferVy = vyNorm * (carSpeed * 1.1);
                        ball.vx += transferVx;
                        ball.vy += transferVy;
                        System.out.println("[DEBUG] Medium-speed velocity transfer: player=" + p.name + " transferVx=" + transferVx + " transferVy=" + transferVy + " carSpeed=" + carSpeed);
                    } else {
                        System.out.println("[DEBUG] Ball jump NOT triggered: player=" + p.name + " p.z=" + p.z + " ball.z=" + ball.z + " carSpeed=" + carSpeed + " brake=" + brake);
                    }
                }
            }

            // --- Ball-corner patch collision logic to prevent sticking ---
            double ballScale = 1.0 + Math.min(1.0, ball.z * 0.12);
            double ballRadius = 30.0 * ballScale;
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
                    // Clamp ball to edge
                    ball.x = closestX + nx * (ballRadius + 0.1);
                    ball.y = closestY + ny * (ballRadius + 0.1);
                    // Reflect and dampen normal velocity, keep tangent
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
                }
            }

            // Ball update (gravity, friction, wall/goal collision, jump, etc.)
            if (anyPlayer) {
                // Ball physics copied from Match.java
                double speed = Math.hypot(ball.vx, ball.vy);
                double minSpeed = 20.0;
                if (speed > minSpeed) {
                    ball.vx *= 0.985;
                    ball.vy *= 0.985;
                } else {
                    ball.vx *= 0.995;
                    ball.vy *= 0.995;
                }
                ball.x += ball.vx * dt;
                ball.y += ball.vy * dt;
                // Ball jump/vertical physics
                ball.vz -= 100.0 * dt;
                ball.z += ball.vz * dt;
                if (ball.z < 0) { ball.z = 0; ball.vz = 0; }

                // Ball wall/goal collision (copied from Match.java)
                double scale = 1.0 + Math.min(1.0, ball.z * 0.12);
                double radius = 30.0 * scale;
                double minX = radius, maxX = 1040 - radius;
                double minY = radius, maxY = 600 - radius;
                if (ball.x < minX) { ball.x = minX; ball.vx = -ball.vx * 0.5; }
                if (ball.x > maxX) { ball.x = maxX; ball.vx = -ball.vx * 0.5; }
                if (ball.y < minY) { ball.y = minY; ball.vy = -ball.vy * 0.5; }
                if (ball.y > maxY) { ball.y = maxY; ball.vy = -ball.vy * 0.5; }
                if (Math.abs(ball.vx) < 1.0) ball.vx = 0.0;
                if (Math.abs(ball.vy) < 1.0) ball.vy = 0.0;
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
            // Ball jump/vertical physics
            vz -= 40.0 * dt; // gravity (floatier)
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
            if (in.brake) {
                accelMag = 0; // no acceleration while braking
            }
            // DEBUG: Force z to 7 to test goal entry restriction
            
            // Apply extra damping if braking (but never force stop or fixed speed)
            if (in.brake) {
                vx *= 0.92; // extra damping
                vy *= 0.92;
                // If throttle is pressed, allow driving at a fixed slow speed in the input direction
                if (in.throttle != 0) {
                    double slowSpeed = 180.0; // fixed slow speed while braking
                    double facingX = Math.cos(angle);
                    double facingY = Math.sin(angle);
                    vx = facingX * slowSpeed * Math.signum(in.throttle);
                    vy = facingY * slowSpeed * Math.signum(in.throttle);
                }
            }

            // --- GREEN PATCH (CORNER) COLLISION LOGIC (SLIDING, MATCH MULTIPLAYER) ---
            double carScale = 1.0 + Math.min(1.0, z * 0.12);
            double carRadius = 24.0 * carScale;
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
                    // Clamp to edge
                    x = closestX + nx * (carRadius + 0.1);
                    y = closestY + ny * (carRadius + 0.1);
                    // Reflect and dampen normal velocity, keep tangent (like wall)
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
                    // Do not block input/acceleration
                    break;
                }
            }

            double facingX = Math.cos(angle);
            double facingY = Math.sin(angle);
            vx += facingX * accelMag;
            vy += facingY * accelMag;

            double speed = Math.hypot(vx, vy);

            // tuned steering for single-player (a bit gentler)
            double steerFactor = 3.0 * (1.0 + speed / 300.0);
            if (in.brake) steerFactor *= 0.5; // halve turning speed when braking
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

            // Ball/goal collision: z > 6.5 cannot enter goal at all (bounces), z <= 6.5 can fully enter and score
            double scale = 1.0 + Math.min(1.0, z * 0.12);
            double radius = 20.0 * scale;
            boolean inRightGoal = (x > 920 && y > 210 && y < 390);
            boolean inLeftGoal = (x < 120 && y > 210 && y < 390);
            double maxX = inRightGoal ? 1040 : 1040 - radius;
            double minX = inLeftGoal ? 0 : radius;
            double minY = radius, maxY = 600 - radius;
            int fieldLeft = 120, fieldRight = 1040 - 120, width = 1040;
            if (z > 6.5) {
                if (x - radius < fieldLeft) {
                    x = fieldLeft + radius;
                    vx = Math.abs(vx);
                } else if (x + radius > fieldRight) {
                    x = fieldRight - radius;
                    vx = -Math.abs(vx);
                }
            } else {
                if (x + radius <= 0) {
                    // scoring handled elsewhere in multiplayer, but can reset or handle here if needed
                } else if (x - radius >= width) {
                    // scoring handled elsewhere in multiplayer, but can reset or handle here if needed
                }
                if (x < minX) {
                    x = minX;
                    vx = -vx * 0.5;
                } else if (x > maxX) {
                    x = maxX;
                    vx = -vx * 0.5;
                }
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

            // jump initiation: always jump if jump is pressed and on ground
            if (in.jump && z <= 0.001) {
                vz = 42.0;
                System.out.println("[GameLoop] Standard jump for player=" + name + " z=" + z + " vx=" + vx + " vy=" + vy);
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

package com.example.websocketsql;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Match {
    public final String id = UUID.randomUUID().toString();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<String, PlayerInput> inputs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    @SuppressWarnings("unused")
    private final java.util.concurrent.ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "match-sender-"+id); t.setDaemon(true); return t; });
    private final GameRepository repo;
    private int tickCounter = 0;
    private static final int BROADCAST_SKIP = 1; // send state every N ticks
    private Ball ball;
    private int scoreA = 0, scoreB = 0;
    private String playerA = null, playerB = null;
    // --- ROUND TIMER ---
    private static final int ROUND_DURATION_MS = 180000; // 3 minutes
    private long roundEndTime = System.currentTimeMillis() + 180000 + 5000; // 5s countdown, then 3 min round
    private boolean roundOver = false;

    // --- ROUND START COUNTDOWN ---
    private boolean roundFrozen = true;
    private long roundCountdownEndTime = System.currentTimeMillis() + 5000; // 5 seconds from match creation

    public Match(GameRepository repo) {
        this.repo = repo;
        this.ball = new Ball();
        // Ensure ball starts in the center
        this.ball.x = 520;
        this.ball.y = 300;
        executor.scheduleAtFixedRate(this::tick, 0, 33, TimeUnit.MILLISECONDS);
    }

    public void addPlayer(String name, WebSocketSession session) {
        sessions.put(name, session);
        // Assign spawn positions: playerA (left), playerB (right)
        if (playerA == null) {
            playerA = name;
            // Spawn playerA in front of left goal, higher up (y=200)
            players.putIfAbsent(name, PlayerState.spawn(name, 180, 200, 0));
            // In single player mode, spawn a practice ball
            if (sessions.size() == 1) {
                this.ball = new Ball();
                this.ball.x = 520;
                this.ball.y = 300;
            }
        } else if (playerB == null) {
            playerB = name;
            // Spawn playerB in front of right goal, lower down (y=400), facing away from ball (angle=0)
            players.putIfAbsent(name, PlayerState.spawn(name, 860, 400, 0));
        } else {
            players.putIfAbsent(name, PlayerState.spawn(name)); // fallback random
        }
        // notify this session that it has been matched
        try {
            String json = "{\"type\":\"matched\",\"match\":\"" + id + "\",\"playerA\":\"" + playerA + "\",\"playerB\":\"" + playerB + "\"}";
            if (session != null && session.isOpen()) session.sendMessage(new TextMessage(json));
        } catch (IOException ignored) {}
        // if two players present, notify both with playerA/playerB
        if (sessions.size() == 2 && playerA != null && playerB != null) {
            WebSocketSession sa = sessions.get(playerA);
            WebSocketSession sb = sessions.get(playerB);
            String matchInfo = String.format(
                "{\"type\":\"matched\",\"match\":\"%s\",\"playerA\":\"%s\",\"playerB\":\"%s\"}",
                id, playerA, playerB
            );
            try {
                if (sa != null && sa.isOpen()) sa.sendMessage(new TextMessage(matchInfo));
                if (sb != null && sb.isOpen()) sb.sendMessage(new TextMessage(matchInfo));
            } catch (IOException ignored) {}
            // Start countdown if both players present and not already running
            if (roundFrozen) {
                roundCountdownEndTime = System.currentTimeMillis() + 5000;
                roundEndTime = roundCountdownEndTime + ROUND_DURATION_MS;
                roundOver = false;
            }
        }
    }

    public void removePlayer(String name) {
        sessions.remove(name);
        players.remove(name);
        inputs.remove(name);
    }

    public void updateInput(String name, PlayerInput in) {
        if (in == null) return;
        // Block input if round is frozen (countdown)
        if (!roundFrozen) {
            inputs.put(name, in);
            // Latch jump to avoid missed single-frame presses between network/tick boundaries
            PlayerState p = players.get(name);
            // Latch jump on any new jump press (seq != 0 and jump is true and wasn't already latched)
            if (p != null && in.seq != 0 && in.jump) p.pendingJump = true;
        } else {
            // During freeze, ignore all input except for updating lastAppliedSeq for smooth client prediction
            PlayerState p = players.get(name);
            if (p != null) p.lastAppliedSeq = in.seq;
        }
    }

    private void tick() {
        // --- Handle round timer ---
        long now = System.currentTimeMillis();
        long timerMs = Math.max(0, roundEndTime - now);
        boolean overtime = false;
        if (!roundFrozen && !roundOver && timerMs <= 0) {
            // If scores are tied, enter overtime (sudden death)
            if (scoreA == scoreB) {
                overtime = true;
                // Do not set roundOver, keep timerMs at 0, but let game continue
            } else {
                roundOver = true;
                timerMs = 0;
                // Determine winner
                String winner = null;
                if (scoreA > scoreB) winner = playerA;
                else if (scoreB > scoreA) winner = playerB;
                // Broadcast game over state
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                sb.append("\"type\":\"gameover\",");
                sb.append("\"match\":\"").append(id).append("\",");
                sb.append("\"scoreA\":").append(scoreA).append(",\"scoreB\":").append(scoreB).append(',');
                sb.append("\"winner\":");
                if (winner != null) sb.append('"').append(winner).append('"');
                else sb.append("null");
                sb.append('}');
                System.out.println("[DEBUG] Broadcasting gameover: " + sb.toString());
                broadcastState(sb.toString());
            }
        }
        // --- Handle round start countdown freeze logic ---
        // (reuse 'now' variable)
        long msLeft = roundCountdownEndTime - now;
        if (roundFrozen) {
            if (msLeft <= 0) {
                roundFrozen = false;
                msLeft = 0;
                // Start round timer when countdown ends
                roundEndTime = System.currentTimeMillis() + ROUND_DURATION_MS;
                roundOver = false;
            }
            // During preround countdown, rotate playerB to face left (angle = Math.PI) for the entire countdown
            if (playerB != null && players.containsKey(playerB)) {
                PlayerState pB = players.get(playerB);
                pB.angle = Math.PI;
            }
            // Broadcast countdown state every tick
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"type\":\"state\",");
            sb.append("\"match\":\"").append(id).append("\",");
            sb.append("\"tick\":").append(tickCounter).append(',');
            sb.append("\"scoreA\":").append(scoreA).append(",\"scoreB\":").append(scoreB).append(',');
            sb.append("\"ball\":{").append("\"x\":").append(ball.x).append(",\"y\":").append(ball.y).append(",\"z\":").append(ball.z).append("},");
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
                    .append("\"lastSeq\":").append(p.lastAppliedSeq).append(',')
                    .append("\"boostFuel\":").append(p.boostFuel).append(',');
                if (name.equals(playerA)) {
                    sb.append("\"color\":\"blue\"");
                } else if (name.equals(playerB)) {
                    sb.append("\"color\":\"red\"");
                } else {
                    sb.append("\"color\":\"gray\"");
                }
                sb.append('}');
            }
            sb.append(']');
            sb.append(",\"countdownMs\":").append(msLeft);
            sb.append(",\"timerMs\":").append(ROUND_DURATION_MS);
            sb.append('}');
            broadcastState(sb.toString());
            tickCounter++;
            return;
        }
        // Player-player collision (simple elastic, like wall), only if on same z level
        final double zThreshold = 3; // max vertical distance to allow collision (less strict z-level matching)
        for (Map.Entry<String, PlayerState> e1 : players.entrySet()) {
            PlayerState p1 = e1.getValue();
            double p1Scale = 1.0 + Math.min(1.0, p1.z * 0.12);
            double p1Radius = 24.0 * p1Scale;
            for (Map.Entry<String, PlayerState> e2 : players.entrySet()) {
                if (e1 == e2) continue;
                PlayerState p2 = e2.getValue();
                double p2Scale = 1.0 + Math.min(1.0, p2.z * 0.12);
                double p2Radius = 24.0 * p2Scale;
                if (Math.abs(p1.z - p2.z) > zThreshold) continue; // skip collision if not on same level
                double dx = p2.x - p1.x, dy = p2.y - p1.y;
                double dist = Math.hypot(dx, dy);
                double minDist = p1Radius + p2Radius;
                if (dist < minDist && dist > 0.0001) {
                    // Push players apart
                    double nx = dx / dist;
                    double ny = dy / dist;
                    double overlap = minDist - dist;
                    // Move each player half the overlap
                    p1.x -= nx * (overlap / 2.0);
                    p1.y -= ny * (overlap / 2.0);
                    p2.x += nx * (overlap / 2.0);
                    p2.y += ny * (overlap / 2.0);
                    // Reflect velocities (like wall, with damping)
                    double v1n = p1.vx * nx + p1.vy * ny;
                    double v2n = p2.vx * nx + p2.vy * ny;
                    // Only reflect if moving toward each other
                    if (v1n - v2n > 0) {
                        double bounce = 0.8;
                        double v1nNew = v2n * bounce;
                        double v2nNew = v1n * bounce;
                        p1.vx += (v1nNew - v1n) * nx;
                        p1.vy += (v1nNew - v1n) * ny;
                        p2.vx += (v2nNew - v2n) * nx;
                        p2.vy += (v2nNew - v2n) * ny;
                    }
                }
            }
        }
        try {
            long t0 = System.nanoTime();
            double dt = 0.033;
            long tickId = System.currentTimeMillis();
            tickCounter++;
            // advance ball physics
            ball.update(dt);
            // ball-wall collision (allow entry into goal zones, restrict only at outer canvas edges)
            // Canvas: 1040x600, field: x=120..919, y=0..599, goals: x=0..119 (left), x=920..1039 (right), ball radius=30
            // Ball border collision at true canvas edge (0..1040, 0..600)
            // Only restrict at outer canvas edge if not in goal zone (y in goal range)
            boolean inRightGoal = (ball.x > 920 && ball.y > 210 && ball.y < 390);
            boolean inLeftGoal = (ball.x < 120 && ball.y > 210 && ball.y < 390);
            double ballMaxX = inRightGoal ? 1040 : 1040 - 30;
            double ballMinX = inLeftGoal ? 0 : 30;
            // Ball can only enter goal if z <= 6
            double ballRadius = 30;
            int goalWidth = 145;
            // Left goal: ball is completely in if (ball.x + ballRadius <= 0)
            // Only allow entry if z <= 6.5 at the moment of crossing the edge
            int fieldLeft = goalWidth, fieldRight = 1040 - goalWidth, width = 1040;
            if (ball.z > 6.5) {
                if (ball.x - ballRadius < fieldLeft) {
                    ball.x = fieldLeft + ballRadius;
                    ball.vx = Math.abs(ball.vx);
                } else if (ball.x + ballRadius > fieldRight) {
                    ball.x = fieldRight - ballRadius;
                    ball.vx = -Math.abs(ball.vx);
                }
            } else {
                // simplified scoring logic: score is won when ball.x + ballRadius < 120 and ball.x - ballRadius > 1040 - 120
                // Debug print removed
                // Left goal: ball fully crosses left line
                boolean scored = false;
                boolean leftGoal = false, rightGoal = false;
                double ballHaloPadding = 5; // extra padding for goal detection
                if (ball.x + ballRadius + ballHaloPadding < 120) {
                    leftGoal = true;
                    System.out.println("[DEBUG] Left goal scored! ball.x=" + ball.x + ", ballRadius=" + ballRadius);
                    if (playerB != null) {
                        System.out.println("[DEBUG] scoreB++");
                        scoreB++;
                        scored = true;
                    }
                    ball.reset();
                    // Reset both players to their spawn positions
                    if (playerA != null && players.containsKey(playerA)) players.put(playerA, PlayerState.spawn(playerA, 180, 300, 0));
                    if (playerB != null && players.containsKey(playerB)) players.put(playerB, PlayerState.spawn(playerB, 860, 400, Math.PI));
                    // Reset ball to center after goal
                    if (ball != null) { ball.x = 520; ball.y = 300; ball.vx = 0; ball.vy = 0; ball.z = 0; ball.vz = 0; }
                } else if (ball.x - ballRadius - ballHaloPadding > 920) {
                    rightGoal = true;
                    System.out.println("[DEBUG] Right goal scored! ball.x=" + ball.x + ", ballRadius=" + ballRadius);
                    if (playerA != null) {
                        System.out.println("[DEBUG] scoreA++");
                        scoreA++;
                        scored = true;
                    }
                    ball.reset();
                    // Reset both players to their spawn positions
                    if (playerA != null && players.containsKey(playerA)) players.put(playerA, PlayerState.spawn(playerA, 180, 300, 0));
                    if (playerB != null && players.containsKey(playerB)) players.put(playerB, PlayerState.spawn(playerB, 860, 400, Math.PI));
                    // Reset ball to center after goal
                    if (ball != null) { ball.x = 520; ball.y = 300; ball.vx = 0; ball.vy = 0; ball.z = 0; ball.vz = 0; }
                }
                // If in overtime and a point is scored, end the game and declare winner
                if (!roundOver && timerMs <= 0 && scoreA != scoreB) {
                    roundOver = true;
                    String winner = null;
                    if (scoreA > scoreB) winner = playerA;
                    else if (scoreB > scoreA) winner = playerB;
                    StringBuilder sb = new StringBuilder();
                    sb.append('{');
                    sb.append("\"type\":\"gameover\",");
                    sb.append("\"match\":\"").append(id).append("\",");
                    sb.append("\"scoreA\":").append(scoreA).append(",\"scoreB\":").append(scoreB).append(',');
                    sb.append("\"winner\":");
                    if (winner != null) sb.append('"').append(winner).append('"');
                    else sb.append("null");
                    sb.append('}');
                    broadcastState(sb.toString());
                }
                if (ball.x < ballMinX) { ball.x = ballMinX; ball.vx = -ball.vx * 0.8; }
                if (ball.x > ballMaxX) { ball.x = ballMaxX; ball.vx = -ball.vx * 0.8; }
            }
            if (ball.y < 30) { ball.y = 30; ball.vy = -ball.vy * 0.8; }
            if (ball.y > 600 - 30) { ball.y = 600 - 30; ball.vy = -ball.vy * 0.8; }

            // --- GREEN PATCH (CORNER) COLLISION LOGIC FOR BALL (MATCHES CAR LOGIC) --- 
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
            
            // always advance physics and persist positions each tick
            for (Map.Entry<String, PlayerState> e : players.entrySet()) {
                String name = e.getKey();
                PlayerState p = e.getValue();
                PlayerInput in = inputs.getOrDefault(name, new PlayerInput(0,0,0,false,false,false));
                p.applyInput(in, dt);
                p.lastAppliedSeq = in.seq;
                repo.enqueuePosition(name, tickId, p.x, p.y, p.z, p.vx, p.vy, p.vz);
            }

            // Now handle ball-player collision after all movement updates
            for (Map.Entry<String, PlayerState> e : players.entrySet()) {
                PlayerState p = e.getValue();
                // Only collide if ball and player are on the same z level (within zThreshold)
                double dz = p.z - ball.z;
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
                        // No jump for the ball
                    } else {
                        // Low-speed: use 2D elastic collision (equal mass)
                        // Relative velocity
                        double rvx = ball.vx - p.vx;
                        double rvy = ball.vy - p.vy;
                        // Only collide if moving toward each other
                        double relVelAlongNormal = rvx * nx + rvy * ny;
                        if (relVelAlongNormal < 0) {
                            // Elastic collision, equal mass
                            double ballVn = ball.vx * nx + ball.vy * ny;
                            double ballVt = -ball.vx * ny + ball.vy * nx;
                            double carVn = p.vx * nx + p.vy * ny;
                            double carVt = -p.vx * ny + p.vy * nx;
                            // Swap normal components, keep tangential
                            double newBallVn = carVn;
                            double newCarVn = ballVn;
                            // Damping for realism
                            double bounce = 0.85;
                            newBallVn *= bounce;
                            newCarVn *= bounce;
                            // Convert back to x/y
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
            // only build and broadcast visual state every BROADCAST_SKIP ticks
            if (tickCounter % BROADCAST_SKIP == 0) {
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                sb.append("\"type\":\"state\",");
                sb.append("\"match\":\"").append(id).append("\",");
                sb.append("\"tick\":").append(tickId).append(',');
                sb.append("\"scoreA\":").append(scoreA).append(",\"scoreB\":").append(scoreB).append(',');
                sb.append("\"ball\":{").append("\"x\":").append(ball.x).append(",\"y\":").append(ball.y).append(",\"z\":").append(ball.z).append("},");
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
                        .append("\"lastSeq\":").append(p.lastAppliedSeq).append(',')
                        .append("\"boostFuel\":").append(p.boostFuel).append(',');
                    if (name.equals(playerA)) {
                        sb.append("\"color\":\"blue\"");
                    } else if (name.equals(playerB)) {
                        sb.append("\"color\":\"red\"");
                    } else {
                        sb.append("\"color\":\"gray\"");
                    }
                    sb.append('}');
                }
                sb.append(']');
                sb.append(",\"countdownMs\":0");
                sb.append(",\"timerMs\":").append(timerMs);
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
                        @SuppressWarnings("null")
                        TextMessage msg = new TextMessage((CharSequence) json);
                        if (s.isOpen()) s.sendMessage(msg);
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
        @SuppressWarnings("unused")
    final String name; double x,y,vx,vy,angle,z,vz; double boostFuel=100; int lastAppliedSeq = 0; boolean pendingJump = false;
        boolean boostLocked = false;
        PlayerState(String name){this.name=name;}
        static PlayerState spawn(String name){ PlayerState p=new PlayerState(name); p.x=Math.random()*400+100; p.y=Math.random()*200+100; p.angle=0; return p; }
        static PlayerState spawn(String name, double x, double y, double angle) {
            PlayerState p = new PlayerState(name);
            p.x = x;
            p.y = y;
            p.angle = angle;
            return p;
        }
        void applyInput(PlayerInput in, double dt){
            double forward = in.throttle;
            double accelMag = 700.0 * forward * dt;
            boolean boostLockActive = false;
            // --- BOOST LOCKOUT LOGIC ---
            // If jump is pressed, lock boost until boost key is released
            if (in.jump && z <= 0.001) {
                boostLocked = true;
            }
            if (!in.boost) {
                boostLocked = false;
            }
            // Boost lock: if in air and boosting and has fuel, freeze z/vz
            if (in.boost && boostFuel > 0 && z > 0.001 && !boostLocked) {
                boostLockActive = true;
            }
            if (in.boost && boostFuel > 0 && !boostLocked) { accelMag += 800.0 * dt; boostFuel -= 40.0 * dt; }
            if (in.brake) {
                accelMag = 0;
            }
            // --- GREEN PATCH (CORNER) COLLISION LOGIC (MATCH CLIENT & SOLO SERVER) ---
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
                // If brake is held, increase turn speed instead of halving
                steerFactor *= 2; // Increase turn speed by 50% when braking
            }
            if (in.steer != 0) {
                angle += in.steer * steerFactor * dt;
            }
            double damping = Math.max(0.0, 1.0 - 4.0 * dt); // more friction for cars
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

    // simple serializable input container
    public static class PlayerInput {
        public final int seq;
        public final double throttle, steer;
        public final boolean jump, boost, brake;
        public PlayerInput(int seq, double t, double s, boolean j, boolean b, boolean br) {
            this.seq = seq; throttle = t; steer = s; jump = j; boost = b; brake = br;
        }
    }

    private static class Ball {
        double x = 400, y = 300, vx = 0, vy = 0;
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
            vz -= 150.0 * dt; // gravity 
            z += vz * dt;
            if (z < 0) { z = 0; vz = 0; }
        }
        void reset() { x = 520; y = 300; vx = 0; vy = 0; z = 0; vz = 0; }
    }
    /**
     * Triggers a vertical jump for the ball if it is on the ground (z == 0).
     * Can be called externally (e.g., from GameHandler) to simulate a ball jump event.
     */
    public void triggerBallJump() {
        if (ball != null && ball.z == 0) {
            ball.vz = 60; // Adjust for desired jump height
        }
    }
}

package com.example.websocketsql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class GameRepository {
    private final JdbcTemplate jdbc;
    private final BlockingQueue<Position> queue = new LinkedBlockingQueue<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "position-writer");
        t.setDaemon(true);
        return t;
    });

    public GameRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final boolean writesEnabled = Boolean.parseBoolean(System.getProperty("game.repo.enabled", "true"));

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS positions (player VARCHAR(100), tick BIGINT, x DOUBLE, y DOUBLE, z DOUBLE, vx DOUBLE, vy DOUBLE, vz DOUBLE)");
        writer.submit(() -> {
            try {
                while (true) {
                    Position p = queue.take();
                    try {
                        jdbc.update("INSERT INTO positions(player,tick,x,y,z,vx,vy,vz) VALUES(?,?,?,?,?,?,?,?)",
                                p.player, p.tick, p.x, p.y, p.z, p.vx, p.vy, p.vz);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            } catch (InterruptedException ignored) {
            }
        });
    }

    public void savePosition(String player, long tick, double x, double y, double z, double vx, double vy, double vz) {
        // kept for compatibility but delegates to async queue
        enqueuePosition(player, tick, x, y, z, vx, vy, vz);
    }

    public void enqueuePosition(String player, long tick, double x, double y, double z, double vx, double vy, double vz) {
        if (!writesEnabled) return;
        queue.offer(new Position(player, tick, x, y, z, vx, vy, vz));
    }

    public int getQueueSize() {
        return queue.size();
    }

    private static class Position {
        final String player; final long tick; final double x, y, z, vx, vy, vz;
        Position(String player, long tick, double x, double y, double z, double vx, double vy, double vz) {
            this.player = player; this.tick = tick; this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz;
        }
    }
}

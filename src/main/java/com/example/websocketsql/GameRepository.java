
package com.example.websocketsql;

import org.springframework.stereotype.Component;

@Component

public class GameRepository {
    public GameRepository() {}
    public void savePosition(String player, long tick, double x, double y, double z, double vx, double vy, double vz) {}
    public void enqueuePosition(String player, long tick, double x, double y, double z, double vx, double vy, double vz) {}
    public int getQueueSize() { return 0; }
}

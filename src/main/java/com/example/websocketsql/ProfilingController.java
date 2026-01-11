package com.example.websocketsql;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class ProfilingController {
    private final GameLoop gameLoop;

    public ProfilingController(GameLoop gameLoop) {
        this.gameLoop = gameLoop;
    }

    @GetMapping("/admin/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        m.put("tick", gameLoop.getTick());
        m.put("players", gameLoop.getPlayerCount());
        m.put("dbQueue", gameLoop.getQueueSize());
        m.put("timestamp", System.currentTimeMillis());
        return m;
    }
}

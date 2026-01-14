package com.example.websocketsql;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private MessageHandler messageHandler;
    @Autowired
    private GameHandler gameHandler;

    @Override
    @SuppressWarnings("null")
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(messageHandler, "/ws").setAllowedOrigins("*");
        registry.addHandler(gameHandler, "/game").setAllowedOrigins("*");
    }
}

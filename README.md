# WebSocket + H2 Demo

Simple Spring Boot application exposing a WebSocket at `/ws` and persisting messages to an in-memory H2 database.

Run:

```bash
mvn spring-boot:run
```

Open http://localhost:8080/ to run the Phaser prototype (enter a username). The client connects to the WebSocket `/game` endpoint.

H2 console: http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:mem:websocketsql`, user `sa`, empty password).

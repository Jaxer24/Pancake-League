# Load testing & profiling

This repository contains a simple Node.js WebSocket load-test and quick instructions for collecting a Java Flight Recorder (JFR).

## Node load test

Install dependencies and run:

```bash
npm init -y
npm install ws
node tools/ws-loadtest.js 200
```

- Adjust the client count (200) as needed. Set `WS_URL` env var to target a different URL.
- The script opens many WebSocket clients, sends a `join` and periodic `input` messages at ~30Hz.

## Capturing a JFR (Java Flight Recorder)

While the server is running you can record a JFR for a fixed duration. Example (60s):

If running the jar:

```bash
java -XX:StartFlightRecording=duration=60s,filename=recording.jfr,settings=profile -jar target/*.jar
```

If using `mvn spring-boot:run`:

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-XX:StartFlightRecording=duration=60s,filename=recording.jfr,settings=profile"
```

Open the resulting `recording.jfr` in Java Mission Control (JMC) or other JFR-compatible viewer to inspect CPU, threads, allocation, and blocking I/O.

## Quick profiling workflow

1. Run the server locally (`mvn spring-boot:run`).
2. Start the load test: `node tools/ws-loadtest.js 200`.
3. While load is running, capture a JFR (60-120s).
4. Open the JFR and look for long GC pauses, frequent blocking I/O, and high CPU methods (e.g., JSON serialization, DB writes, websocket send locks).

## Server stats endpoint

This repo adds a small endpoint at `GET /admin/stats` that returns a tiny JSON payload with the current tick, player count and DB queue size. Use it to monitor `repo.getQueueSize()` during load testing.

```bash
curl http://localhost:8080/admin/stats
```

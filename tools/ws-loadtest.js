// Simple WebSocket load-test script for the game server
// Usage: npm install ws && node ws-loadtest.js [clientCount]

const WebSocket = require('ws');
const url = process.env.WS_URL || 'ws://localhost:8080/game';
const N = parseInt(process.argv[2] || process.env.CLIENTS || '100', 10);

console.log(`Connecting ${N} clients to ${url}`);
const clients = [];

for (let i = 0; i < N; i++) {
  const ws = new WebSocket(url);
  ws.on('open', () => {
    const name = 'bot' + i;
    ws.send(JSON.stringify({ type: 'join', name }));
    let seq = 1;
    // send inputs at ~30Hz
    const iv = setInterval(() => {
      if (ws.readyState !== WebSocket.OPEN) { clearInterval(iv); return; }
      const msg = {
        type: 'input',
        seq: seq++,
        throttle: Math.random() * 2 - 1,
        steer: (Math.random() - 0.5) * 2,
        jump: Math.random() < 0.02,
        boost: Math.random() < 0.05
      };
      ws.send(JSON.stringify(msg));
    }, 33);
  });
  ws.on('message', () => {});
  ws.on('error', (e) => { console.error('client', i, 'error', e && e.message); });
  ws.on('close', () => {});
  clients.push(ws);
}

process.on('SIGINT', () => {
  console.log('Closing clients...');
  clients.forEach(c => c.close());
  process.exit();
});

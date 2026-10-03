// One-shot CDP probe: connect to the WebView page and evaluate an expression.
// Usage: node cdp-probe.js "expression"
const http = require('http');
const WebSocket = require('ws');

const expr = process.argv[2] || '1';

http.get('http://127.0.0.1:9333/json', (res) => {
  let body = '';
  res.on('data', (c) => (body += c));
  res.on('end', () => {
    const list = JSON.parse(body);
    const page = list.find((p) => p.type === 'page');
    if (!page) { console.error('no page'); process.exit(1); }
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    ws.on('open', () => {
      ws.send(JSON.stringify({
        id: 1, method: 'Runtime.evaluate',
        params: { expression: expr, returnByValue: true, awaitPromise: true }
      }));
    });
    ws.on('message', (m) => {
      const msg = JSON.parse(m);
      if (msg.id === 1) {
        console.log(JSON.stringify(msg.result, null, 2));
        ws.close();
      }
    });
    ws.on('error', (e) => { console.error('ws error', e.message); process.exit(1); });
  });
}).on('error', (e) => { console.error('http error', e.message); process.exit(1); });

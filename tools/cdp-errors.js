// Capture console + exceptions for N seconds, then report.
const http = require('http');
const WebSocket = require('ws');
const seconds = parseInt(process.argv[2] || '20', 10);

http.get('http://127.0.0.1:9333/json', (res) => {
  let body = '';
  res.on('data', (c) => (body += c));
  res.on('end', () => {
    const page = JSON.parse(body).find((p) => p.type === 'page');
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    let id = 0;
    const send = (method, params) => ws.send(JSON.stringify({ id: ++id, method, params }));
    ws.on('open', () => {
      send('Runtime.enable');
      send('Log.enable');
      send('Page.enable');
      send('Page.reload', { ignoreCache: true });
    });
    ws.on('message', (m) => {
      const msg = JSON.parse(m);
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params.exceptionDetails;
        console.log('[EXC]', d.text, '|', (d.exception && d.exception.description || '').split('\n').slice(0, 4).join(' || '));
      } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
        console.log('[CONSOLE-ERR]', msg.params.args.map((a) => a.value || a.description).join(' '));
      } else if (msg.method === 'Log.entryAdded') {
        const e = msg.params.entry;
        if (e.level === 'error') console.log('[LOG-ERR]', e.text, e.url || '');
      }
    });
    setTimeout(() => { console.log('--- done ---'); ws.close(); process.exit(0); }, seconds * 1000);
  });
});

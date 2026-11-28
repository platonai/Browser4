// dev-server.js — 零依赖，直接 serve browser4-rest/static 目录，并把 /api/* 反代到后端
//
// 为什么需要反代：agent.html 从哪个 origin 打开，就把 state.backend 当成那个 origin
// （SAME_ORIGIN 逻辑）。只 serve 静态文件的话，/api/* 全会打到本服务器 404，页面会误判成
// “后端已连接”（探测逻辑把 404 当成后端在线）。所以 /api/* 必须转发到真实后端。
//
// 启动：node dev-server.js
// 访问：http://127.0.0.1:8765/agent.html
// 改完源文件 F5 刷新即可，不用重启 Spring Boot

const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, 'browser4-rest/src/main/resources/static');
const PORT = 8765;
const BACKEND_HOST = '127.0.0.1';
const BACKEND_PORT = Number(process.env.B4_BACKEND_PORT || 18182);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js':   'application/javascript; charset=utf-8',
  '.css':  'text/css; charset=utf-8',
  '.svg':  'image/svg+xml',
  '.png':  'image/png',
  '.ico':  'image/x-icon',
  '.json': 'application/json; charset=utf-8',
};

/** Forward one request to the Spring backend, streaming the response back (SSE needs this). */
function proxyToBackend(req, res) {
  const headers = Object.assign({}, req.headers, { host: BACKEND_HOST + ':' + BACKEND_PORT });
  const upstream = http.request(
    { host: BACKEND_HOST, port: BACKEND_PORT, path: req.url, method: req.method, headers },
    (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
      upstreamRes.pipe(res);
    },
  );
  upstream.on('error', (error) => {
    if (res.headersSent) { res.end(); return; }
    res.writeHead(502, {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
    });
    res.end(JSON.stringify({ error: 'backend unreachable: ' + error.message }));
  });
  req.pipe(upstream);
}

http.createServer((req, res) => {
  const rawPath = req.url.split('?')[0];

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    });
    res.end();
    return;
  }

  // Anything under /api/ is the backend's, not ours.
  if (rawPath.startsWith('/api/')) {
    proxyToBackend(req, res);
    return;
  }

  let urlPath = decodeURIComponent(rawPath);
  if (urlPath === '/') urlPath = '/agent.html';
  const filePath = path.join(ROOT, urlPath);
  if (!filePath.startsWith(ROOT)) {
    res.writeHead(403); res.end('Forbidden'); return;
  }
  fs.readFile(filePath, (err, data) => {
    if (err) { res.writeHead(404); res.end('Not found: ' + urlPath); return; }
    const ext = path.extname(filePath).toLowerCase();
    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store, must-revalidate',
      'X-Dev-Mode': 'live-reload',
    });
    res.end(data);
  });
}).listen(PORT, '127.0.0.1', () => {
  console.log('🌐 Browser4 agent.html 开发服务器');
  console.log('   静态根目录:', ROOT);
  console.log('   开发地址  : http://127.0.0.1:' + PORT + '/agent.html');
  console.log('   后端 API  : http://' + BACKEND_HOST + ':' + BACKEND_PORT + '  (/api/* 反向代理，需已启动)');
  console.log('   修改源文件后直接 F5 刷新浏览器');
  console.log('   Ctrl+C 停止');
});

import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const port = Number(process.env.PORT || 3000);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '../../');

const mimeTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
};

function getContentType(filePath) {
  return mimeTypes[path.extname(filePath).toLowerCase()] || 'application/octet-stream';
}

async function serveStatic(req, res) {
  const url = req.url === '/' ? '/index.html' : req.url.split('?')[0];
  const safePath = path.normalize(path.join(root, url));
  if (!safePath.startsWith(root)) {
    res.writeHead(400, { 'content-type': 'text/plain' });
    res.end('Bad request');
    return;
  }

  try {
    const data = await fs.readFile(safePath);
    res.writeHead(200, { 'content-type': getContentType(safePath), 'cache-control': 'no-store' });
    res.end(data);
  } catch (error) {
    if (error.code === 'ENOENT') {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('Not found');
      return;
    }
    res.writeHead(500, { 'content-type': 'text/plain' });
    res.end('Server error');
  }
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'latifword' }));
    return;
  }

  if (req.method === 'GET') {
    await serveStatic(req, res);
    return;
  }

  res.writeHead(405, { 'content-type': 'text/plain' });
  res.end('Method not allowed');
});

server.listen(port, () => {
  console.log(`LATIF server listening on http://127.0.0.1:${port}`);
});

import http from 'node:http';

const port = Number(process.env.PORT || 3000);

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'latifword' }));
    return;
  }

  res.writeHead(200, { 'content-type': 'text/plain' });
  res.end('LATIF workspace starter is running.');
});

server.listen(port, () => {
  console.log(`LATIF server listening on http://127.0.0.1:${port}`);
});

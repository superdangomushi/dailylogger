"use strict";

// Minimal backend used only to verify the C++ process supervisor. The real
// server entry remains server.js.
const http = require("node:http");

const host = process.env.AIHELPER_NODE_HOST || "127.0.0.1";
const port = Number(process.env.PORT);
const startDelayMs = Number(process.env.AIHELPER_TEST_START_DELAY_MS || 0);
const server = http.createServer((request, response) => {
  if (request.url === "/hold") {
    response.writeHead(200, { "Content-Type": "application/octet-stream" });
    response.flushHeaders();
    return;
  }
  response.setHeader("Content-Type", "application/json");
  response.end(JSON.stringify({
    pid: process.pid,
    host,
    gatewayMode: process.env.AIHELPER_CPP_GATEWAY,
    url: request.url,
  }));
});

const startTimer = setTimeout(() => server.listen(port, host), startDelayMs);

function shutdown() {
  clearTimeout(startTimer);
  if (!server.listening) process.exit(0);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 2000).unref();
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

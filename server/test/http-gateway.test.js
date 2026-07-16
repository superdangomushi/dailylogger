"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const http = require("node:http");
const net = require("node:net");
const path = require("node:path");
const zlib = require("node:zlib");
const { spawn } = require("node:child_process");
const { after, before, test } = require("node:test");

const serverDir = path.resolve(__dirname, "..");
const gatewayBinary = process.env.AIHELPER_TEST_GATEWAY_BIN ||
  path.join(serverDir, "aihelper-server");
let backend;
let backendPort;
let gatewayPort;
let gateway;
let gatewayStdout = "";
let gatewayStderr = "";

function listen(server, port = 0) {
  return new Promise((resolve, reject) => {
    const onError = (error) => {
      server.off("listening", onListening);
      reject(error);
    };
    const onListening = () => {
      server.off("error", onError);
      resolve(server.address().port);
    };
    server.once("error", onError);
    server.once("listening", onListening);
    server.listen(port, "127.0.0.1");
  });
}

function close(server) {
  if (!server?.listening) return Promise.resolve();
  return new Promise((resolve) => server.close(resolve));
}

function waitForExit(child, timeoutMs) {
  if (child.exitCode !== null) return Promise.resolve(true);
  return new Promise((resolve) => {
    let settled = false;
    const finish = (exited) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      child.off("exit", onExit);
      resolve(exited);
    };
    const onExit = () => finish(true);
    const timer = setTimeout(() => finish(false), timeoutMs);
    child.once("exit", onExit);
  });
}

async function reservePort() {
  const temporary = http.createServer();
  const port = await listen(temporary);
  await close(temporary);
  return port;
}

function headerValues(response, wanted) {
  const values = [];
  for (let i = 0; i < response.rawHeaders.length; i += 2) {
    if (response.rawHeaders[i].toLowerCase() === wanted.toLowerCase()) {
      values.push(response.rawHeaders[i + 1]);
    }
  }
  return values;
}

function rawRequest({ method = "GET", target = "/", headers = {}, body, chunks }) {
  return new Promise((resolve, reject) => {
    const request = http.request({
      host: "127.0.0.1",
      port: gatewayPort,
      method,
      path: target,
      headers,
      agent: false,
    }, (response) => {
      const parts = [];
      response.on("data", (part) => parts.push(part));
      response.on("end", () => resolve({
        status: response.statusCode,
        headers: response.headers,
        rawHeaders: response.rawHeaders,
        body: Buffer.concat(parts),
      }));
    });
    request.on("error", reject);
    if (chunks) {
      for (const chunk of chunks) request.write(chunk);
      request.end();
    } else {
      request.end(body);
    }
  });
}

function rawSocketRequest(payload) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: "127.0.0.1", port: gatewayPort });
    const parts = [];
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      const value = Buffer.concat(parts).toString("latin1");
      socket.destroy();
      resolve(value);
    };
    socket.setTimeout(3000, () => {
      if (!settled) socket.destroy(new Error("raw request timed out"));
    });
    socket.on("connect", () => socket.write(payload));
    socket.on("data", (part) => {
      parts.push(part);
      if (Buffer.concat(parts).includes("\r\n\r\n")) finish();
    });
    socket.on("end", finish);
    socket.on("error", reject);
  });
}

function dripUntilClosed(prefix, byte = "a", intervalMs = 200) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: "127.0.0.1", port: gatewayPort });
    const parts = [];
    const startedAt = Date.now();
    let interval;
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearInterval(interval);
      clearTimeout(timeout);
      socket.destroy();
      if (error && parts.length === 0) {
        reject(error);
      } else {
        resolve({
          elapsedMs: Date.now() - startedAt,
          response: Buffer.concat(parts).toString("latin1"),
        });
      }
    };
    const timeout = setTimeout(
      () => finish(new Error("drip request was not closed by its deadline")),
      3000,
    );
    socket.on("connect", () => {
      socket.write(prefix);
      interval = setInterval(() => socket.write(byte), intervalMs);
    });
    socket.on("data", (part) => parts.push(part));
    socket.on("end", () => finish());
    socket.on("close", () => finish());
    socket.on("error", finish);
  });
}

function collectRequest(request) {
  return new Promise((resolve, reject) => {
    const parts = [];
    request.on("data", (part) => parts.push(part));
    request.on("end", () => resolve(Buffer.concat(parts)));
    request.on("error", reject);
  });
}

before(async () => {
  backend = http.createServer(async (request, response) => {
    const body = await collectRequest(request);

    if (request.url === "/response-headers") {
      response.statusCode = 201;
      response.setHeader("Set-Cookie", ["first=1; Path=/", "second=2; HttpOnly"]);
      response.setHeader(
        "Content-Disposition",
        "attachment; filename=sample.bin; filename*=UTF-8''%E4%BA%88%E5%AE%9A.bin",
      );
      response.setHeader("X-Backend-Header", "kept");
      response.setHeader("X-Percent", "%41%2F+");
      response.end(Buffer.from([0x00, 0xff, 0x41, 0x00]));
      return;
    }

    if (request.url === "/gzip-response") {
      const compressed = zlib.gzipSync(Buffer.from('{"from":"backend"}'));
      response.setHeader("Content-Type", "application/json");
      response.setHeader("Content-Encoding", "gzip");
      response.setHeader("Content-Length", String(compressed.length));
      response.end(compressed);
      return;
    }

    if (request.url === "/no-framing") {
      response.setHeader(
        "X-Seen-Content-Length",
        request.headers["content-length"] ?? "absent",
      );
      response.end("ok");
      return;
    }

    if (request.url === "/trailer") {
      response.setHeader(
        "X-Seen-Authorization",
        request.headers.authorization ?? "absent",
      );
      response.end("ok");
      return;
    }

    if (request.url === "/redirect") {
      response.statusCode = 302;
      response.setHeader("Location", "/destination?from=backend");
      response.end("redirect body");
      return;
    }

    if (request.url === "/range") {
      const data = Buffer.from("abcdefghij");
      if (request.headers.range === "bytes=2-5") {
        response.statusCode = 206;
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Range", "bytes 2-5/10");
        response.setHeader("Content-Length", "4");
        response.end(data.subarray(2, 6));
      } else {
        response.setHeader("Content-Length", String(data.length));
        response.end(data);
      }
      return;
    }

    if (request.url === "/head") {
      response.setHeader("Content-Type", "text/plain; charset=utf-8");
      response.setHeader("Content-Length", "9");
      response.end("ninebytes");
      return;
    }

    if (request.url === "/no-content") {
      response.statusCode = 204;
      response.end();
      return;
    }

    if (request.url === "/chunked-large") {
      response.setHeader("Content-Type", "application/octet-stream");
      for (let index = 0; index < 96; index += 1) {
        response.write(Buffer.alloc(64 * 1024, index));
      }
      response.end();
      return;
    }

    if (request.url === "/delayed-response") {
      setTimeout(() => response.end("delayed-ok"), 1200);
      return;
    }

    response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.end(JSON.stringify({
      method: request.method,
      url: request.url,
      headers: request.headers,
      bodyBase64: body.toString("base64"),
    }));
  });
  backendPort = await listen(backend);
  gatewayPort = await reservePort();

  gateway = spawn(gatewayBinary, [], {
    cwd: serverDir,
    env: {
      ...process.env,
      PORT: String(gatewayPort),
      AIHELPER_HTTP_HOST: "127.0.0.1",
      AIHELPER_SERVER_DIR: serverDir,
      AIHELPER_NODE_PORT: String(backendPort),
      AIHELPER_GATEWAY_NO_SPAWN: "1",
      AIHELPER_NODE_START_TIMEOUT_MS: "5000",
      AIHELPER_HTTP_THREADS: "8",
      AIHELPER_HTTP_HEADER_TIMEOUT_SEC: "1",
      AIHELPER_HTTP_BODY_TIMEOUT_SEC: "1",
      AIHELPER_HTTP_MAX_BODY_BYTES: String(32 * 1024 * 1024),
      TRUST_PROXY: "0",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  gateway.stdout.on("data", (data) => { gatewayStdout += data; });
  gateway.stderr.on("data", (data) => { gatewayStderr += data; });

  const deadline = Date.now() + 5000;
  let lastError;
  while (Date.now() < deadline) {
    if (gateway.exitCode !== null) break;
    try {
      const result = await rawRequest({ target: "/ready" });
      if (result.status === 200) return;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error(
    `gateway did not start: ${lastError?.message || "process exited"}\n` +
    `stdout:\n${gatewayStdout}\nstderr:\n${gatewayStderr}`,
  );
});

after(async () => {
  if (gateway && gateway.exitCode === null) {
    gateway.kill("SIGTERM");
    const exited = await waitForExit(gateway, 5000);
    if (!exited) gateway.kill("SIGKILL");
  }
  await close(backend);
});

test("raw target, Host, auth headers, and trusted client IP reach Node", async () => {
  const result = await rawRequest({
    target: "/echo?value=%2F+%25&value=second",
    headers: {
      Host: "calendar.example.test:8443",
      Authorization: "Bearer %41%2F+token",
      "X-Account-Email": "user@example.test",
      "X-AIHelper-Gateway-Client-IP": "203.0.113.99",
      "X-Empty": "",
    },
  });
  assert.equal(result.status, 200);
  const echoed = JSON.parse(result.body.toString("utf8"));
  assert.equal(echoed.url, "/echo?value=%2F+%25&value=second");
  assert.equal(echoed.headers.host, "calendar.example.test:8443");
  assert.equal(echoed.headers.authorization, "Bearer %41%2F+token");
  assert.equal(echoed.headers["x-account-email"], "user@example.test");
  assert.equal(echoed.headers["x-empty"], "");
  assert.notEqual(
    echoed.headers["x-aihelper-gateway-client-ip"],
    "203.0.113.99",
  );
  assert.match(echoed.headers["x-aihelper-gateway-client-ip"], /127\.0\.0\.1/);
});

test("POST, PATCH, and DELETE bodies remain byte-identical", async (t) => {
  const cases = [
    ["POST", "application/json", Buffer.from('{"日本語":"予定"}')],
    ["PATCH", "text/plain", Buffer.from("line 1\r\nline 2\0", "utf8")],
    ["DELETE", "application/octet-stream", Buffer.from([0, 1, 2, 255, 0, 7])],
  ];
  for (const [method, contentType, body] of cases) {
    await t.test(method, async () => {
      const result = await rawRequest({
        method,
        target: `/body-${method.toLowerCase()}`,
        headers: {
          "Content-Type": contentType,
          "Content-Length": String(body.length),
        },
        body,
      });
      const echoed = JSON.parse(result.body.toString("utf8"));
      assert.equal(echoed.method, method);
      assert.equal(echoed.headers["content-type"], contentType);
      assert.deepEqual(Buffer.from(echoed.bodyBase64, "base64"), body);
    });
  }
});

test("chunked request bodies are forwarded without changing bytes", async () => {
  // Exceeds the gateway's bounded 4 MiB response queue, exercising both the
  // request spool file and response backpressure paths.
  const chunks = [Buffer.from("first-"), Buffer.alloc(6 * 1024 * 1024, 0xab), Buffer.from("-last")];
  const result = await rawRequest({
    method: "POST",
    target: "/chunked-upload",
    headers: { "Content-Type": "application/octet-stream" },
    chunks,
  });
  const echoed = JSON.parse(result.body.toString("utf8"));
  assert.deepEqual(
    Buffer.from(echoed.bodyBase64, "base64"),
    Buffer.concat(chunks),
  );
});

test("GET/OPTIONS bodies and compressed bytes reach Node unchanged", async () => {
  const getBody = Buffer.from("body on GET \0 日本語");
  const getResult = await rawRequest({
    method: "GET",
    target: "/get-with-body",
    headers: {
      "Content-Type": "text/plain",
      "Content-Length": String(getBody.length),
    },
    body: getBody,
  });
  const getEcho = JSON.parse(getResult.body.toString("utf8"));
  assert.equal(getEcho.method, "GET");
  assert.deepEqual(Buffer.from(getEcho.bodyBase64, "base64"), getBody);

  const optionsBody = Buffer.from("options-body");
  const optionsResult = await rawRequest({
    method: "OPTIONS",
    target: "/options-with-body",
    headers: { "Content-Length": String(optionsBody.length) },
    body: optionsBody,
  });
  const optionsEcho = JSON.parse(optionsResult.body.toString("utf8"));
  assert.equal(optionsEcho.method, "OPTIONS");
  assert.deepEqual(Buffer.from(optionsEcho.bodyBase64, "base64"), optionsBody);

  const compressed = zlib.gzipSync(Buffer.from('{"compressed":true}'));
  const gzipResult = await rawRequest({
    method: "POST",
    target: "/gzip",
    headers: {
      "Content-Type": "application/json",
      "Content-Encoding": "gzip",
      "Content-Length": String(compressed.length),
    },
    body: compressed,
  });
  const gzipEcho = JSON.parse(gzipResult.body.toString("utf8"));
  assert.equal(gzipEcho.headers["content-encoding"], "gzip");
  assert.deepEqual(Buffer.from(gzipEcho.bodyBase64, "base64"), compressed);

  const emptyGet = await rawRequest({
    method: "GET",
    target: "/empty-get",
    headers: { "Content-Length": "0" },
  });
  const emptyGetEcho = JSON.parse(emptyGet.body.toString("utf8"));
  assert.equal(emptyGetEcho.headers["content-length"], "0");
});

test("unframed empty POST stays unframed and trailers are not promoted", async () => {
  const noFraming = await rawSocketRequest(
    "POST /no-framing HTTP/1.1\r\n" +
    "Host: localhost\r\n" +
    "Connection: close\r\n\r\n",
  );
  assert.match(noFraming, /\r\nX-Seen-Content-Length: absent\r\n/i);

  const trailer = await rawSocketRequest(
    "POST /trailer HTTP/1.1\r\n" +
    "Host: localhost\r\n" +
    "Transfer-Encoding: chunked\r\n" +
    "Trailer: Authorization\r\n" +
    "Connection: close\r\n\r\n" +
    "4\r\ntest\r\n" +
    "0\r\nAuthorization: Bearer injected\r\n\r\n",
  );
  assert.match(trailer, /\r\nX-Seen-Authorization: absent\r\n/i);
});

test("status, redirects, duplicate headers, and binary responses pass through", async () => {
  const headersResult = await rawRequest({ target: "/response-headers" });
  assert.equal(headersResult.status, 201);
  assert.deepEqual(headersResult.body, Buffer.from([0x00, 0xff, 0x41, 0x00]));
  assert.equal(headersResult.headers["x-backend-header"], "kept");
  assert.equal(
    headersResult.headers["content-disposition"],
    "attachment; filename=sample.bin; filename*=UTF-8''%E4%BA%88%E5%AE%9A.bin",
  );
  assert.equal(headersResult.headers["x-percent"], "%41%2F+");
  assert.deepEqual(headerValues(headersResult, "Set-Cookie"), [
    "first=1; Path=/",
    "second=2; HttpOnly",
  ]);

  const redirect = await rawRequest({ target: "/redirect" });
  assert.equal(redirect.status, 302);
  assert.equal(redirect.headers.location, "/destination?from=backend");
  assert.equal(redirect.body.toString("utf8"), "redirect body");
});

test("Range is handled once by Node and is not sliced again by C++", async () => {
  const result = await rawRequest({
    target: "/range",
    headers: { Range: "bytes=2-5" },
  });
  assert.equal(result.status, 206);
  assert.equal(result.headers["content-range"], "bytes 2-5/10");
  assert.equal(result.headers["content-length"], "4");
  assert.equal(result.body.toString("utf8"), "cdef");

  const invalid = await rawRequest({
    target: "/range",
    headers: { Range: "not-a-valid-byte-range" },
  });
  assert.equal(invalid.status, 200);
  assert.equal(invalid.body.toString("utf8"), "abcdefghij");
});

test("ambiguous HTTP framing is rejected before reaching Node", async () => {
  const conflicting = await rawSocketRequest(
    "POST /smuggle HTTP/1.1\r\n" +
    "Host: localhost\r\n" +
    "Content-Length: 4\r\n" +
    "Transfer-Encoding: chunked\r\n" +
    "Connection: close\r\n\r\n0\r\n\r\n",
  );
  assert.match(conflicting, /^HTTP\/1\.1 400 /);

  const duplicateLength = await rawSocketRequest(
    "POST /smuggle HTTP/1.1\r\n" +
    "Host: localhost\r\n" +
    "Content-Length: 0\r\n" +
    "Content-Length: 0\r\n" +
    "Connection: close\r\n\r\n",
  );
  assert.match(duplicateLength, /^HTTP\/1\.1 400 /);

  const invalidHeaderName = await rawSocketRequest(
    "GET /smuggle HTTP/1.1\r\n" +
    "Host: localhost\r\n" +
    "Bad Header: value\r\n" +
    "Connection: close\r\n\r\n",
  );
  assert.match(invalidHeaderName, /^HTTP\/1\.1 400 /);

  const oversizedRequestLine = await rawSocketRequest(
    `GET /${"a".repeat(17 * 1024)}`,
  );
  assert.match(oversizedRequestLine, /^HTTP\/1\.1 414 /);

  const oversizedHeaderLine = await rawSocketRequest(
    "GET /oversized-header HTTP/1.1\r\n" +
    "Host: localhost\r\n" +
    `X-Oversized: ${"a".repeat(17 * 1024)}`,
  );
  assert.match(oversizedHeaderLine, /^HTTP\/1\.1 400 /);

  const oversizedChunkLine = await rawSocketRequest(
    "POST /oversized-chunk HTTP/1.1\r\n" +
    "Host: localhost\r\n" +
    "Content-Type: application/octet-stream\r\n" +
    "Transfer-Encoding: chunked\r\n\r\n" +
    "1".repeat(17 * 1024),
  );
  assert.match(oversizedChunkLine, /^HTTP\/1\.1 400 /);
});

test("absolute read deadlines release workers from slow headers and framing", async (t) => {
  await t.test("unfinished headers", async () => {
    const attempts = await Promise.all(Array.from({ length: 8 }, () =>
      dripUntilClosed(
        "GET /slow-header HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "X-Slow: ",
      )));
    for (const result of attempts) {
      assert.ok(result.elapsedMs < 2500, `header deadline took ${result.elapsedMs}ms`);
      assert.match(result.response, /^HTTP\/1\.1 408 /);
    }
    const readyStartedAt = Date.now();
    assert.equal((await rawRequest({ target: "/ready-after-slow" })).status, 200);
    assert.ok(Date.now() - readyStartedAt < 500);
  });

  await t.test("unfinished chunk-size", async () => {
    const result = await dripUntilClosed(
      "POST /slow-chunk HTTP/1.1\r\n" +
      "Host: localhost\r\n" +
      "Content-Type: application/octet-stream\r\n" +
      "Transfer-Encoding: chunked\r\n\r\n" +
      "1",
    );
    assert.ok(result.elapsedMs < 2500);
    assert.match(result.response, /^HTTP\/1\.1 408 /);
  });

  await t.test("unfinished trailer", async () => {
    const result = await dripUntilClosed(
      "POST /slow-trailer HTTP/1.1\r\n" +
      "Host: localhost\r\n" +
      "Content-Type: application/octet-stream\r\n" +
      "Transfer-Encoding: chunked\r\n\r\n" +
      "1\r\na\r\n0\r\nX-Trailer: ",
    );
    assert.ok(result.elapsedMs < 2500);
    assert.match(result.response, /^HTTP\/1\.1 408 /);
  });

  await t.test("unfinished fixed-length body", async () => {
    const result = await dripUntilClosed(
      "POST /slow-fixed HTTP/1.1\r\n" +
      "Host: localhost\r\n" +
      "Content-Type: application/octet-stream\r\n" +
      "Content-Length: 100\r\n\r\n" +
      "a",
    );
    assert.ok(result.elapsedMs < 2500);
    assert.match(result.response, /^HTTP\/1\.1 408 /);
  });
});

test("request read deadlines do not limit delayed backend responses", async () => {
  const result = await rawRequest({
    method: "POST",
    target: "/delayed-response",
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Length": "1",
    },
    body: Buffer.from("x"),
  });
  assert.equal(result.status, 200);
  assert.equal(result.body.toString("utf8"), "delayed-ok");
});

test("request body size is bounded before temporary-file growth", async () => {
  const result = await rawRequest({
    method: "POST",
    target: "/too-large",
    headers: {
      "Content-Type": "application/json",
      "Content-Length": String(11 * 1024 * 1024),
    },
  });
  assert.equal(result.status, 413);
});

test("HEAD and 204 do not gain synthetic entity headers", async () => {
  const head = await rawRequest({ method: "HEAD", target: "/head" });
  assert.equal(head.status, 200);
  assert.equal(head.body.length, 0);
  assert.equal(head.headers["content-length"], "9");
  assert.equal(head.headers["accept-ranges"], undefined);

  const noContent = await rawRequest({ target: "/no-content" });
  assert.equal(noContent.status, 204);
  assert.equal(noContent.body.length, 0);
  assert.equal(noContent.headers["content-length"], undefined);
});

test("large chunked responses stream through with identical bytes", async () => {
  const expected = Buffer.concat(Array.from(
    { length: 96 },
    (_, index) => Buffer.alloc(64 * 1024, index),
  ));
  const result = await rawRequest({ target: "/chunked-large" });
  assert.equal(result.status, 200);
  assert.equal(result.headers["transfer-encoding"], "chunked");
  assert.equal(result.headers["content-length"], undefined);
  assert.equal(
    crypto.createHash("sha256").update(result.body).digest("hex"),
    crypto.createHash("sha256").update(expected).digest("hex"),
  );
});

test("compressed backend responses are forwarded without decompression", async () => {
  const result = await rawRequest({ target: "/gzip-response" });
  assert.equal(result.status, 200);
  assert.equal(result.headers["content-encoding"], "gzip");
  assert.deepEqual(
    zlib.gunzipSync(result.body),
    Buffer.from('{"from":"backend"}'),
  );
});

test("OPTIONS is forwarded to the Node application", async () => {
  const result = await rawRequest({ method: "OPTIONS", target: "/anything" });
  const echoed = JSON.parse(result.body.toString("utf8"));
  assert.equal(echoed.method, "OPTIONS");
  assert.equal(echoed.url, "/anything");
});

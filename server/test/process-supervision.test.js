"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
const net = require("node:net");
const path = require("node:path");
const { spawn } = require("node:child_process");
const test = require("node:test");

const serverDir = path.resolve(__dirname, "..");
const gatewayBinary = process.env.AIHELPER_TEST_GATEWAY_BIN ||
  path.join(serverDir, "aihelper-server");

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve(server.address().port));
  });
}

async function reservePort() {
  const server = http.createServer();
  const port = await listen(server);
  await new Promise((resolve) => server.close(resolve));
  return port;
}

function getJson(port, target) {
  return new Promise((resolve, reject) => {
    const request = http.get({
      host: "127.0.0.1",
      port,
      path: target,
      agent: false,
    }, (response) => {
      const parts = [];
      response.on("data", (part) => parts.push(part));
      response.on("end", () => {
        try {
          resolve({ status: response.statusCode, value: JSON.parse(Buffer.concat(parts)) });
        } catch (error) {
          reject(error);
        }
      });
    });
    request.on("error", reject);
  });
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

function processExists(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error.code === "ESRCH") return false;
    throw error;
  }
}

function childPids(pid) {
  try {
    const value = fs.readFileSync(`/proc/${pid}/task/${pid}/children`, "utf8").trim();
    return value ? value.split(/\s+/).map(Number) : [];
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
}

test("gateway starts Node on loopback and stops it with the parent", async () => {
  const publicPort = await reservePort();
  const backendPort = await reservePort();
  let output = "";
  const gateway = spawn(gatewayBinary, [], {
    cwd: serverDir,
    env: {
      ...process.env,
      PORT: String(publicPort),
      AIHELPER_HTTP_HOST: "127.0.0.1",
      AIHELPER_SERVER_DIR: serverDir,
      AIHELPER_NODE_PORT: String(backendPort),
      AIHELPER_NODE_ENTRY: "test/managed-backend.js",
      AIHELPER_NODE_START_TIMEOUT_MS: "5000",
      AIHELPER_HTTP_THREADS: "4",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  gateway.stdout.on("data", (part) => { output += part; });
  gateway.stderr.on("data", (part) => { output += part; });

  let backendPid;
  let stalledUpload;
  let heldRequest;
  let heldResponse;
  try {
    const deadline = Date.now() + 5000;
    let response;
    while (Date.now() < deadline) {
      if (gateway.exitCode !== null) break;
      try {
        response = await getJson(publicPort, "/managed-check");
        break;
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 25));
      }
    }
    assert.ok(response, `managed gateway failed to start:\n${output}`);
    assert.equal(response.status, 200);
    assert.equal(response.value.host, "127.0.0.1");
    assert.equal(response.value.gatewayMode, "1");
    assert.equal(response.value.url, "/managed-check");
    backendPid = response.value.pid;
    assert.equal(processExists(backendPid), true);

    ({ request: heldRequest, response: heldResponse } = await new Promise((resolve, reject) => {
      const request = http.get({
        host: "127.0.0.1",
        port: publicPort,
        path: "/hold",
        agent: false,
      }, (held) => resolve({ request, response: held }));
      request.on("error", reject);
    }));
    heldRequest.on("error", () => {});
    heldResponse.on("error", () => {});

    stalledUpload = net.createConnection({ host: "127.0.0.1", port: publicPort });
    await new Promise((resolve, reject) => {
      stalledUpload.once("connect", resolve);
      stalledUpload.once("error", reject);
    });
    stalledUpload.on("error", () => {});
    stalledUpload.write(
      "POST /stalled HTTP/1.1\r\n" +
      "Host: localhost\r\n" +
      "Content-Type: text/plain\r\n" +
      "Content-Length: 1\r\n\r\n",
    );
    await new Promise((resolve) => setTimeout(resolve, 50));

    gateway.kill("SIGTERM");
    assert.equal(await waitForExit(gateway, 5000), true, output);
    assert.equal(gateway.exitCode, 0, output);

    const stopDeadline = Date.now() + 3000;
    while (processExists(backendPid) && Date.now() < stopDeadline) {
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
    assert.equal(processExists(backendPid), false, "managed Node process was orphaned");
  } finally {
    stalledUpload?.destroy();
    heldRequest?.destroy();
    heldResponse?.destroy();
    if (gateway.exitCode === null) {
      gateway.kill("SIGKILL");
      await waitForExit(gateway, 1000);
    }
    if (backendPid && processExists(backendPid)) process.kill(backendPid, "SIGKILL");
  }
});

test("SIGTERM during Node startup exits cleanly without an orphan", async () => {
  const publicPort = await reservePort();
  const backendPort = await reservePort();
  let output = "";
  const gateway = spawn(gatewayBinary, [], {
    cwd: serverDir,
    env: {
      ...process.env,
      PORT: String(publicPort),
      AIHELPER_HTTP_HOST: "127.0.0.1",
      AIHELPER_SERVER_DIR: serverDir,
      AIHELPER_NODE_PORT: String(backendPort),
      AIHELPER_NODE_ENTRY: "test/managed-backend.js",
      AIHELPER_NODE_START_TIMEOUT_MS: "10000",
      AIHELPER_TEST_START_DELAY_MS: "5000",
      AIHELPER_HTTP_THREADS: "4",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  gateway.stdout.on("data", (part) => { output += part; });
  gateway.stderr.on("data", (part) => { output += part; });

  let backendPid;
  try {
    const childDeadline = Date.now() + 3000;
    while (Date.now() < childDeadline && gateway.exitCode === null) {
      [backendPid] = childPids(gateway.pid);
      if (backendPid) break;
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.ok(backendPid, `managed Node was not spawned:\n${output}`);

    gateway.kill("SIGTERM");
    assert.equal(await waitForExit(gateway, 5000), true, output);
    assert.equal(gateway.exitCode, 0, output);

    const stopDeadline = Date.now() + 3000;
    while (processExists(backendPid) && Date.now() < stopDeadline) {
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.equal(processExists(backendPid), false, "startup Node process was orphaned");
  } finally {
    if (gateway.exitCode === null) {
      gateway.kill("SIGKILL");
      await waitForExit(gateway, 1000);
    }
    if (backendPid && processExists(backendPid)) process.kill(backendPid, "SIGKILL");
  }
});

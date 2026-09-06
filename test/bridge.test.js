import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import sharp from "sharp";
import { createBridge } from "../src/bridge.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { createMcp } from "../src/mcp.js";
import { renderPage } from "../src/render.js";

async function fixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "boox-test-"));
  const bridge = await createBridge({ directory });
  await new Promise((resolve) => bridge.server.listen(0, "127.0.0.1", resolve));
  const url = `http://127.0.0.1:${bridge.server.address().port}`;
  t.after(async () => {
    bridge.server.closeAllConnections();
    await new Promise((resolve) => bridge.server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  });
  const api = async (endpoint, body, headers = {}) => {
    const response = await fetch(url + endpoint, {
      method: body ? "POST" : "GET",
      headers: {
        Authorization: `Bearer ${bridge.store.token}`,
        ...(body ? { "Content-Type": "application/json" } : {}),
        ...headers,
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: response.status, body: await response.json() };
  };
  const imageBase64 = (
    await sharp({
      create: { width: 200, height: 300, channels: 3, background: "#fff" },
    })
      .png()
      .toBuffer()
  ).toString("base64");
  return {
    ...bridge,
    directory,
    url,
    api,
    page: { title: "Test", imageBase64, width: 200, height: 300 },
    feedback: {
      submissionId: "submit-1",
      compositeBase64: imageBase64,
      strokes: [
        {
          width: 3,
          points: [
            { x: 10, y: 20, pressure: 0.5 },
            { x: 20, y: 30, pressure: 1 },
          ],
        },
      ],
      note: "Move here",
    },
  };
}
test("authenticated lifecycle, duplicate and stale submission, persistence", async (t) => {
  const f = await fixture(t);
  assert.equal(
    (await f.api("/api/health", null, { Authorization: "Bearer nope" })).status,
    401,
  );
  assert.equal(
    (await f.api("/api/health", null, { Origin: "https://evil.example" }))
      .status,
    403,
  );
  assert.deepEqual((await f.api("/api/reviews/current")).body, {
    review: null,
  });
  const created = await f.api("/api/reviews", f.page);
  assert.equal(created.status, 201);
  const id = created.body.id;
  assert.equal((await f.api("/api/reviews", f.page)).status, 409);
  assert.equal(
    (await f.api(`/api/reviews/${id}/feedback`)).body.status,
    "pending",
  );
  assert.equal(
    (await f.api(`/api/reviews/${id}/feedback`, f.feedback)).status,
    200,
  );
  assert.equal(
    (await f.api(`/api/reviews/${id}/feedback`, f.feedback)).status,
    200,
  );
  assert.equal(
    (
      await f.api(`/api/reviews/${id}/feedback`, {
        ...f.feedback,
        note: "Different",
      })
    ).status,
    409,
  );
  const next = (await f.api("/api/reviews", f.page)).body;
  assert.equal(
    (
      await f.api(`/api/reviews/${id}/feedback`, {
        ...f.feedback,
        submissionId: "stale",
      })
    ).status,
    409,
  );
  assert.equal(
    (await f.api(`/api/reviews/${next.id}/cancel`, {})).body.status,
    "cancelled",
  );
  assert.equal(
    (await f.api(`/api/reviews/${next.id}/feedback`, f.feedback)).status,
    409,
  );
  const restored = await createBridge({ directory: f.directory });
  assert.equal(restored.store.token, f.store.token);
  assert.equal(restored.store.feedback(id).note, "Move here");
  assert.equal(restored.store.current().status, "cancelled");
});
test("image and stroke validation rejects malformed input", async (t) => {
  const f = await fixture(t);
  assert.equal(
    (await f.api("/api/reviews", { ...f.page, width: 201 })).status,
    400,
  );
  assert.equal(
    (await f.api("/api/reviews", { ...f.page, imageBase64: "dGVzdA==" }))
      .status,
    400,
  );
  const id = (await f.api("/api/reviews", f.page)).body.id;
  assert.equal(
    (
      await f.api(`/api/reviews/${id}/feedback`, {
        ...f.feedback,
        strokes: [{ width: 3, points: [{ x: 900, y: 0, pressure: 1 }] }],
      })
    ).status,
    400,
  );
  assert.equal(
    (await f.api(`/api/reviews/${id}/feedback`)).body.status,
    "pending",
  );
});
test("MCP returns bounded pending result then annotated image and ink", async (t) => {
  const f = await fixture(t);
  const server = createMcp({
    url: f.url,
    tokenPath: path.join(f.directory, "token"),
  });
  const client = new Client({ name: "test", version: "1" });
  const [a, b] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(a), client.connect(b)]);
  t.after(() => Promise.all([client.close(), server.close()]));
  const tools = await client.listTools();
  assert.equal(tools.tools.length, 4);
  const present = await client.callTool({
    name: "boox_present",
    arguments: {
      title: "Diagram",
      scene: [{ type: "rect", x: 50, y: 50, width: 100, height: 100 }],
      width: 200,
      height: 300,
    },
  });
  assert.equal(present.isError, undefined);
  const id = JSON.parse(present.content[0].text).id;
  const start = Date.now();
  const pending = await client.callTool({
    name: "boox_wait_feedback",
    arguments: { review_id: id, wait_seconds: 0 },
  });
  assert.ok(Date.now() - start < 1000);
  assert.equal(JSON.parse(pending.content[0].text).status, "pending");
  await f.api(`/api/reviews/${id}/feedback`, f.feedback);
  const submitted = await client.callTool({
    name: "boox_wait_feedback",
    arguments: { review_id: id, wait_seconds: 1 },
  });
  assert.equal(submitted.content[1].type, "image");
  assert.equal(submitted.content[1].data, f.feedback.compositeBase64);
  assert.deepEqual(
    JSON.parse(submitted.content[0].text).strokes,
    f.feedback.strokes,
  );
});
test("text rendering escapes markup and refuses overflow", async () => {
  const page = await renderPage({
    text: "<script>hello</script>",
    width: 1404,
    height: 1872,
  });
  assert.equal(
    (await sharp(Buffer.from(page.imageBase64, "base64")).metadata()).width,
    1404,
  );
  await assert.rejects(
    renderPage({ text: "many\n".repeat(100), width: 1404, height: 1872 }),
    /exceeds/,
  );
  await assert.rejects(renderPage({ text: "hello", scene: [] }), /exactly one/);
});
test("stdio MCP process exposes tools without contaminating protocol stdout", async (t) => {
  const f = await fixture(t);
  const { StdioClientTransport } = await import(
    "@modelcontextprotocol/sdk/client/stdio.js"
  );
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [path.resolve("src/mcp.js")],
    env: {
      ...process.env,
      BOOX_BRIDGE_URL: f.url,
      BOOX_TOKEN_PATH: path.join(f.directory, "token"),
    },
    stderr: "pipe",
  });
  const client = new Client({ name: "stdio-test", version: "1" });
  t.after(() => client.close());
  await client.connect(transport);
  const status = await client.callTool({ name: "boox_status", arguments: {} });
  assert.equal(status.isError, undefined);
  assert.equal(JSON.parse(status.content[0].text).health.ok, true);
  assert.equal(
    JSON.parse(status.content[0].text).health.lastTabletPollAt,
    null,
  );
});

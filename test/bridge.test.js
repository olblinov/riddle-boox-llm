import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, readFile, writeFile } from "node:fs/promises";
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
  assert.equal(restored.store.current(), null);
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
  assert.equal(tools.tools.length, 6);
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

test("Markdown review preserves source context and rejects changed versions", async (t) => {
  const f = await fixture(t);
  const file = path.join(f.directory, "proposal.md");
  const markdown =
    "# Review proposal\n\n" +
    Array.from(
      { length: 35 },
      (_, i) =>
        `## Section ${i + 1}\n\nKeep this source intact until all pen comments have returned. Each page is part of the same document.\n\n`,
    ).join("");
  await writeFile(file, markdown);
  const server = createMcp({
    url: f.url,
    tokenPath: path.join(f.directory, "token"),
  });
  const client = new Client({ name: "markdown-roundtrip", version: "1" });
  const [a, b] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(a), client.connect(b)]);
  t.after(() => Promise.all([client.close(), server.close()]));
  const presented = await client.callTool({
    name: "boox_present",
    arguments: { title: "Proposal", markdown_path: file, markdown_page: 1 },
  });
  assert.equal(presented.isError, undefined, JSON.stringify(presented));
  const review = JSON.parse(presented.content[0].text);
  assert.equal(review.source.path, file);
  assert.equal(review.source.kind, "markdown");
  assert.equal(review.source.pageIndex, 1);
  assert.ok(review.source.pageCount > 1);
  assert.ok(review.source.startLine >= 1);
  assert.ok(review.source.endLine >= review.source.startLine);
  assert.equal(await readFile(review.source.snapshotPath, "utf8"), markdown);
  const page = f.store.get(review.id);
  const submitted = await f.api(`/api/reviews/${review.id}/feedback`, {
    ...f.feedback,
    compositeBase64: page.imageBase64,
  });
  assert.equal(submitted.status, 200);
  const feedback = await client.callTool({
    name: "boox_wait_feedback",
    arguments: { review_id: review.id, wait_seconds: 0 },
  });
  assert.deepEqual(JSON.parse(feedback.content[0].text).source, review.source);
  assert.equal(feedback.content[1].type, "image");
  const second = await client.callTool({
    name: "boox_present",
    arguments: {
      title: "Proposal",
      markdown_path: file,
      markdown_page: 2,
      expected_sha256: review.source.sha256,
    },
  });
  assert.equal(second.isError, undefined, JSON.stringify(second));
  assert.equal(JSON.parse(second.content[0].text).source.pageIndex, 2);
  assert.equal(await readFile(file, "utf8"), markdown);
  await writeFile(file, markdown + "\nNew concurrent change.\n");
  const changed = await client.callTool({
    name: "boox_present",
    arguments: {
      title: "Proposal",
      markdown_path: file,
      markdown_page: 2,
      expected_sha256: review.source.sha256,
    },
  });
  assert.equal(changed.isError, true);
  assert.match(changed.content[0].text, /changed|checksum|SHA|version/i);
  const restored = await createBridge({ directory: f.directory });
  assert.deepEqual(restored.store.feedback(review.id).source, review.source);
});

test("document feedback is atomic, ordered, idempotent and survives reload", async (t) => {
  const f = await fixture(t);
  const source = (pageIndex) => ({
    kind: "markdown",
    path: "/tmp/source.md",
    snapshotPath: "/tmp/snapshot.md",
    sha256: "a".repeat(64),
    pageIndex,
    pageCount: 2,
    startLine: pageIndex * 10,
    endLine: pageIndex * 10 + 9,
  });
  const secondImage = (
    await sharp({
      create: { width: 300, height: 200, channels: 3, background: "#ddd" },
    })
      .png()
      .toBuffer()
  ).toString("base64");
  const pages = [
    { ...f.page, source: source(1) },
    { imageBase64: secondImage, width: 300, height: 200, source: source(2) },
  ];
  const created = await f.api("/api/reviews", {
    title: "Whole document",
    pages,
  });
  assert.equal(created.status, 201);
  const review = created.body;
  assert.equal(review.pageCount, 2);
  assert.equal(review.width, 200);
  assert.equal(review.source.pageIndex, 1);
  assert.equal(review.pages[1].source.pageIndex, 2);
  assert.equal(review.pages[0].imageBase64, undefined);
  const image = await fetch(f.url + review.pages[1].imageUrl, {
    headers: { Authorization: `Bearer ${f.store.token}` },
  });
  assert.equal(
    Buffer.from(await image.arrayBuffer()).toString("base64"),
    secondImage,
  );
  assert.equal(
    (await f.api(`/api/reviews/${review.id}/image?page=0`)).status,
    400,
  );
  assert.equal(
    (await f.api(`/api/reviews/${review.id}/image?page=3`)).status,
    400,
  );
  const payload = {
    submissionId: "document-1",
    note: "Overall comment",
    pages: [
      {
        pageIndex: 1,
        compositeBase64: f.page.imageBase64,
        strokes: f.feedback.strokes,
        note: "First page",
      },
      {
        pageIndex: 2,
        compositeBase64: secondImage,
        strokes: [],
        note: "Second page",
      },
    ],
  };
  for (const bad of [
    { ...payload, pages: payload.pages.slice(0, 1) },
    { ...payload, pages: [...payload.pages].reverse() },
    { ...payload, pages: [payload.pages[0], payload.pages[0]] },
    {
      ...payload,
      pages: [
        payload.pages[0],
        { ...payload.pages[1], compositeBase64: "dGVzdA==" },
      ],
    },
  ]) {
    assert.equal(
      (await f.api(`/api/reviews/${review.id}/feedback`, bad)).status,
      400,
    );
    assert.equal(
      (await f.api(`/api/reviews/${review.id}/feedback`)).body.status,
      "pending",
    );
  }
  assert.equal(
    (await f.api(`/api/reviews/${review.id}/feedback`, payload)).status,
    200,
  );
  assert.equal(
    (await f.api(`/api/reviews/${review.id}/feedback`, payload)).status,
    200,
  );
  const changed = structuredClone(payload);
  changed.pages[1].note = "Altered";
  assert.equal(
    (await f.api(`/api/reviews/${review.id}/feedback`, changed)).status,
    409,
  );
  const restored = await createBridge({ directory: f.directory });
  const feedback = restored.store.feedback(review.id);
  assert.equal(feedback.pages.length, 2);
  assert.equal(feedback.note, "Overall comment");
  assert.deepEqual(feedback.pages[1].source, source(2));
  assert.equal(feedback.pages[1].compositeBase64, secondImage);
  assert.equal(restored.store.image(review.id, 1), f.page.imageBase64);
  const next = await f.api("/api/reviews", f.page);
  assert.equal(next.status, 201);
  assert.equal(
    (
      await f.api(`/api/reviews/${review.id}/feedback`, {
        ...payload,
        submissionId: "stale",
      })
    ).status,
    409,
  );
});

test("MCP Markdown defaults to all pages and returns labelled image feedback without base64 text", async (t) => {
  const f = await fixture(t);
  const file = path.join(f.directory, "document.md");
  await writeFile(
    file,
    Array.from(
      { length: 12 },
      (_, i) =>
        `## Section ${i + 1}\n\nWhole document annotations stay together. Read every page before pressing Send.\n\n`,
    ).join(""),
  );
  const server = createMcp({
    url: f.url,
    tokenPath: path.join(f.directory, "token"),
  });
  const client = new Client({ name: "document-test", version: "1" });
  const [a, b] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(a), client.connect(b)]);
  t.after(() => Promise.all([client.close(), server.close()]));
  const presented = await client.callTool({
    name: "boox_present",
    arguments: {
      title: "Entire document",
      markdown_path: file,
      width: 1000,
      height: 1000,
    },
  });
  assert.equal(presented.isError, undefined, JSON.stringify(presented));
  const review = JSON.parse(presented.content[0].text);
  assert.ok(review.pageCount > 1);
  assert.equal(review.pages.length, review.pageCount);
  assert.equal(review.pages.at(-1).source.pageIndex, review.pageCount);
  const payload = {
    submissionId: "all-pages",
    pages: review.pages.map((page) => ({
      pageIndex: page.pageIndex,
      compositeBase64: f.store.image(review.id, page.pageIndex),
      strokes: [],
      note: `Comment ${page.pageIndex}`,
    })),
  };
  assert.equal(
    (await f.api(`/api/reviews/${review.id}/feedback`, payload)).status,
    200,
  );
  const returned = await client.callTool({
    name: "boox_wait_feedback",
    arguments: { review_id: review.id, wait_seconds: 0 },
  });
  const images = returned.content.filter((content) => content.type === "image");
  assert.equal(images.length, review.pageCount);
  const texts = returned.content
    .filter((content) => content.type === "text")
    .map((content) => content.text);
  assert.ok(
    texts.every(
      (text) => !text.includes("compositeBase64") && !text.includes("iVBOR"),
    ),
  );
  const last = JSON.parse(texts.at(-1));
  assert.equal(
    last.label,
    `Annotated page ${review.pageCount}/${review.pageCount}`,
  );
  assert.deepEqual(last.source, review.pages.at(-1).source);
  assert.equal(last.note, `Comment ${review.pageCount}`);
});

test("oversized requests return explicit 24 MiB error without creating a review", async (t) => {
  const f = await fixture(t);
  const oversized = await f.api("/api/reviews", {
    title: "Oversized",
    padding: "x".repeat(24 * 1024 * 1024),
  });
  assert.equal(oversized.status, 413);
  assert.match(oversized.body.error, /24 MiB/);
  assert.equal(f.store.current(), null);
});

test("chunked oversize upload receives JSON limit error without partial review", async (t) => {
  const f = await fixture(t);
  const body = async function* () {
    for (let i = 0; i < 25; i++) yield Buffer.alloc(1024 * 1024, "x");
  };
  const response = await fetch(f.url + "/api/reviews", {
    method: "POST",
    duplex: "half",
    body: body(),
    headers: {
      Authorization: `Bearer ${f.store.token}`,
      "Content-Type": "application/json",
    },
  });
  assert.equal(response.status, 413);
  assert.match((await response.json()).error, /24 MiB/);
  assert.equal(f.store.current(), null);
});


test("sent history persists, expires after 90 days, and never removes pending drafts", async (t) => {
  const f = await fixture(t);
  const sent = (await f.api("/api/reviews", f.page)).body;
  await f.api(`/api/reviews/${sent.id}/feedback`, f.feedback);
  const firstAck = await f.api(`/api/reviews/${sent.id}/feedback`, f.feedback);
  const retryAck = await f.api(`/api/reviews/${sent.id}/feedback`, f.feedback);
  assert.ok(firstAck.body.submittedAt);
  assert.equal(retryAck.body.submittedAt, firstAck.body.submittedAt);
  const pending = (await f.api("/api/reviews", f.page)).body;
  const history = await f.api("/api/history");
  assert.equal(history.status, 200);
  assert.equal(history.body.retentionDays, 90);
  assert.deepEqual(history.body.reviews.map(r => r.id), [sent.id]);
  assert.equal(history.body.reviews[0].pageCount, 1);
  assert.equal((await f.api(`/api/reviews/${sent.id}/feedback`)).body.status, "submitted");
  const timestamp = Date.parse(history.body.reviews[0].submittedAt);
  assert.equal((await f.store.history(timestamp + 90 * 86400000)).reviews.length, 1);
  assert.equal((await f.store.history(timestamp + 90 * 86400000 + 1)).reviews.length, 0);
  assert.equal(f.store.get(pending.id).status, "pending");
  assert.equal(JSON.parse(await readFile(path.join(f.directory, "state.json"))).reviews[sent.id], undefined);
});


test("document queue preserves active review, advances FIFO, and survives restart", async (t) => {
  const f = await fixture(t);
  const a = (await f.api("/api/reviews", f.page)).body;
  const b = (await f.api("/api/reviews", {...f.page,title:"Second"})).body;
  const c = (await f.api("/api/reviews", {...f.page,title:"Third"})).body;
  assert.equal(b.queuePosition, 2);
  assert.equal(f.store.current().id, a.id);
  assert.deepEqual((await f.api("/api/queue")).body.reviews.map(r=>r.id),[a.id,b.id,c.id]);
  assert.equal((await f.api(`/api/reviews/${b.id}/feedback`, f.feedback)).status,409);
  await f.api(`/api/reviews/${a.id}/feedback`,f.feedback);
  assert.equal(f.store.current().id,b.id);
  await f.api(`/api/reviews/${a.id}/feedback`,f.feedback);
  assert.equal(f.store.current().id,b.id);
  const restored = await createBridge({directory:f.directory});
  restored.server.emit("close");
  assert.equal(restored.store.current().id,b.id);
  await f.api(`/api/reviews/${b.id}/cancel`,{});
  assert.equal(f.store.current().id,c.id);
  assert.equal((await f.api(`/api/reviews/${a.id}/feedback`)).body.status,"submitted");
});


test("expanded canvas keeps signed source coordinates and rejects clipped or excessive bounds", async (t) => {
 const f=await fixture(t);const r=(await f.api("/api/reviews",f.page)).body;
 const image=(await sharp({create:{width:300,height:450,channels:3,background:"white"}}).png().toBuffer()).toString("base64");
 const feedback={...f.feedback,compositeBase64:image,canvasBounds:{x:-50,y:-50,width:300,height:450},strokes:[{width:3,points:[{x:-20,y:-25,pressure:.5}]}]};
 assert.equal((await f.api(`/api/reviews/${r.id}/feedback`,{...feedback,canvasBounds:{x:1,y:-50,width:300,height:450}})).status,400);
 assert.equal((await f.api(`/api/reviews/${r.id}/feedback`,{...feedback,canvasBounds:{x:-600,y:-50,width:300,height:450}})).status,400);
 assert.equal((await f.api(`/api/reviews/${r.id}/feedback`,feedback)).status,200);
 const stored=(await f.api(`/api/reviews/${r.id}/feedback`)).body;
 assert.deepEqual(stored.canvasBounds,feedback.canvasBounds);
 assert.equal(stored.strokes[0].points[0].x,-20);
});


test("unread feedback survives waiting and requires exact submission acknowledgement", async (t) => {
 const f=await fixture(t); const r=(await f.api("/api/reviews",f.page)).body;
 await f.api(`/api/reviews/${r.id}/feedback`,f.feedback);
 assert.equal((await f.api("/api/feedback-inbox")).body.reviews[0].id,r.id);
 await f.api(`/api/reviews/${r.id}/feedback`);
 assert.equal((await f.api("/api/feedback-inbox")).body.reviews.length,1);
 assert.equal((await f.api(`/api/reviews/${r.id}/acknowledge`,{submissionId:"wrong"})).status,409);
 assert.equal((await f.api(`/api/reviews/${r.id}/acknowledge`,{submissionId:f.feedback.submissionId})).status,200);
 assert.equal((await f.api("/api/feedback-inbox")).body.reviews.length,0);
 assert.equal((await f.api(`/api/reviews/${r.id}/feedback`)).body.status,"submitted");
});


test("queue selection preserves drafts, resists stale switches, and survives enqueue/restart", async (t) => {
 const f=await fixture(t);
 const a=(await f.api("/api/reviews",f.page)).body;
 const b=(await f.api("/api/reviews",f.page)).body;
 assert.equal((await f.api(`/api/reviews/${b.id}/activate`,{expectedCurrentReviewId:a.id})).status,200);
 assert.equal(f.store.current().id,b.id);
 assert.equal(f.store.get(a.id).status,"pending");
 assert.equal((await f.api(`/api/reviews/${b.id}/activate`,{expectedCurrentReviewId:a.id})).status,200);
 const c=(await f.api("/api/reviews",f.page)).body;
 assert.equal(f.store.current().id,b.id);
 assert.equal((await f.api(`/api/reviews/${c.id}/activate`,{expectedCurrentReviewId:a.id})).status,409);
 assert.equal((await f.api(`/api/reviews/${c.id}/activate`,{})).status,400);
 await f.api(`/api/reviews/${b.id}/feedback`,f.feedback);
 assert.equal(f.store.current().id,a.id);
 assert.equal((await f.api(`/api/reviews/${b.id}/activate`,{expectedCurrentReviewId:a.id})).status,409);
 const restored=await createBridge({directory:f.directory});restored.server.emit("close");
 assert.equal(restored.store.current().id,a.id);
 assert.equal(restored.store.get(c.id).status,"pending");
});


test("last review submission and cancellation leave a persistent empty inbox", async (t) => {
 const f=await fixture(t);const a=(await f.api("/api/reviews",f.page)).body;
 await f.api(`/api/reviews/${a.id}/feedback`,f.feedback);
 assert.equal((await f.api("/api/reviews/current")).body.review,null);
 assert.deepEqual((await f.api("/api/queue")).body,{activeReviewId:null,reviews:[]});
 assert.equal((await f.api(`/api/reviews/${a.id}/feedback`)).body.status,"submitted");
 const b=(await f.api("/api/reviews",f.page)).body;
 await f.api(`/api/reviews/${b.id}/cancel`,{});
 assert.equal(f.store.current(),null);
 const restored=await createBridge({directory:f.directory});restored.server.emit("close");
 assert.equal(restored.store.current(),null);
 assert.equal(restored.store.get(a.id).status,"submitted");
});

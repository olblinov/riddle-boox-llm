const { test } = require("node:test");
const vm = require("node:vm");
const fs = require("node:fs");
const assert = require("node:assert/strict");
const legacy = {
  id: "original",
  title: "Test",
  width: 100,
  height: 100,
  status: "pending",
  imageUrl: "/image",
};
const documentReview = {
  ...legacy,
  pageCount: 2,
  pages: [
    { pageIndex: 1, width: 100, height: 100, imageUrl: "/image?page=1" },
    { pageIndex: 2, width: 100, height: 100, imageUrl: "/image?page=2" },
  ],
};
function fixture(review = legacy, { dropAck = false, png = "cG5n" } = {}) {
  const saved = new Map(),
    requests = [];
  let acceptedBody;
  function createContext() {
    const elements = new Map();
    const drawing = new Proxy({}, { get: () => () => {} });
    const element = (id) =>
      elements.get(id) ||
      (elements.set(id, {
        value: "",
        checked: false,
        style: {},
        clientWidth: 100,
        addEventListener() {},
        getContext: () => drawing,
        toDataURL: () => `data:image/png;base64,${png}`,
        requestSubmit() {},
        getBoundingClientRect: () => ({
          left: 0,
          top: 0,
          width: 100,
          height: 100,
        }),
      }),
      elements.get(id));
    const context = vm.createContext({
      document: {
        getElementById: element,
        createElement: () => element("canvas"),
      },
      globalThis: { crypto: { randomUUID: () => "fixed-id" } },
      localStorage: {
        getItem: (k) => saved.get(k) || null,
        setItem: (k, v) => saved.set(k, v),
      },
      sessionStorage: { getItem: () => null, setItem() {} },
      window: { devicePixelRatio: 2, addEventListener() {} },
      innerWidth: 1000,
      URL: { createObjectURL: () => "blob:test", revokeObjectURL() {} },
      Image: class {
        naturalWidth = 100;
        naturalHeight = 100;
        get src() {
          return this.url;
        }
        set src(value) {
          this.url = value;
          queueMicrotask(() => this.onload());
        }
      },
      setInterval() {},
      setTimeout,
      clearTimeout,
      AbortController,
      TextEncoder,
      confirm: () => true,
      fetch: async (path, options) => {
        if (options.method === "POST") {
          requests.push(options.body);
          if (!acceptedBody) acceptedBody = options.body;
          assert.equal(options.body, acceptedBody);
          if (dropAck) {
            dropAck = false;
            throw new Error("ACK lost after server acceptance");
          }
          return { ok: true };
        }
        return {
          ok: true,
          json: async () =>
            path.endsWith("/current")
              ? { review: { ...review, id: "newer" } }
              : { ok: true },
          blob: async () => ({}),
        };
      },
    });
    vm.runInContext(fs.readFileSync("public/app.js", "utf8"), context);
    return { elements, run: (code) => vm.runInContext(code, context) };
  }
  return { saved, requests, createContext };
}
async function prepare(app, review) {
  await app.run(`openReview(${JSON.stringify(review)})`);
  app.run(
    "strokes.push({width:2,points:[{x:2,y:2,pressure:0.5}]});ui.note.value='original comment';changed();",
  );
}
for (const review of [legacy, documentReview])
  test(`${review.pages ? "document" : "legacy"} uncertain submission survives reload and exact retry`, async () => {
    const f = fixture(review, { dropAck: true });
    let app = f.createContext();
    await prepare(app, review);
    if (review.pages) await app.run("showPage(1)");
    await app.run("ui.send.onclick()");
    assert.equal(app.elements.get("note").disabled, true);
    assert.equal(app.elements.get("undo").disabled, true);
    assert.equal(app.elements.get("send").disabled, false);
    assert.equal(app.elements.get("send").textContent, "Retry same comments");
    assert.ok(JSON.parse(f.saved.values().next().value).frozenBody);
    const id = app.run("submissionId");
    app.run("changed()");
    assert.equal(app.run("submissionId"), id);
    app = f.createContext();
    app.elements.get("token").value = "test";
    await app.run("ui.pair.onsubmit({preventDefault(){}})");
    assert.equal(app.run("review.id"), "original");
    assert.equal(app.elements.get("note").disabled, true);
    assert.equal(app.elements.get("next").disabled, true);
    await app.run("ui.next.onclick()");
    assert.equal(app.run("review.id"), "original");
    await app.run("ui.send.onclick()");
    assert.equal(f.requests.length, 2);
    assert.equal(f.requests[0], f.requests[1]);
    assert.equal(app.run("submitted"), true);
  });
test("document retains per-page ink and notes across navigation and reload; submits all pages once", async () => {
  const f = fixture(documentReview);
  let app = f.createContext();
  await prepare(app, documentReview);
  assert.equal(app.elements.get("send").disabled, true);
  await app.run("ui.send.onclick()");
  assert.equal(f.requests.length, 0);
  await app.run("showPage(1)");
  app.run(
    "strokes.push({width:3,points:[{x:70,y:80,pressure:1}]});ui.note.value='second page';changed();",
  );
  await app.run("showPage(0)");
  assert.equal(app.run("ui.note.value"), "original comment");
  assert.equal(app.run("strokes[0].points[0].x"), 2);
  app = f.createContext();
  app.elements.get("token").value = "test";
  await app.run("ui.pair.onsubmit({preventDefault(){}})");
  assert.equal(app.run("review.id"), "original");
  assert.equal(app.run("ui.note.value"), "original comment");
  await app.run("showPage(1)");
  assert.equal(app.run("ui.note.value"), "second page");
  assert.equal(app.elements.get("send").disabled, false);
  await app.run("ui.send.onclick()");
  const payload = JSON.parse(f.requests[0]);
  assert.deepEqual(
    payload.pages.map((p) => p.pageIndex),
    [1, 2],
  );
  assert.deepEqual(
    payload.pages.map((p) => p.note),
    ["original comment", "second page"],
  );
  assert.deepEqual(
    payload.pages.map((p) => p.strokes[0].points[0].x),
    [2, 70],
  );
  assert.equal(f.requests.length, 1);
  assert.equal(app.elements.get("ink").width, 200);
});
test("oversize document feedback is rejected before send without freezing draft", async () => {
  const f = fixture(documentReview, { png: "x".repeat(13 * 1024 * 1024) });
  const app = f.createContext();
  await prepare(app, documentReview);
  await app.run("showPage(1)");
  await app.run("ui.send.onclick()");
  assert.equal(f.requests.length, 0);
  assert.equal(app.run("frozenBody"), null);
  assert.equal(app.elements.get("note").disabled, false);
  assert.match(app.elements.get("status").textContent, /exceeds 24 MiB/);
});

test("decoded image cache stays bounded and zoom canvas stays within raster budget", async () => {
  const many = {
    ...legacy,
    pages: Array.from({ length: 6 }, (_, index) => ({
      pageIndex: index + 1,
      width: 100,
      height: 100,
      imageUrl: `/image?page=${index + 1}`,
    })),
  };
  const f = fixture(many);
  const app = f.createContext();
  await prepare(app, many);
  for (let index = 1; index < many.pages.length; index++) {
    await app.run(`showPage(${index})`);
    assert.ok(app.run("imageCache.size") <= 3);
    assert.equal(
      app.run("imageCache.has(pages()[pagePosition].imageUrl)"),
      true,
    );
  }
  app.elements.get("viewport").clientWidth = 10000;
  app.elements.get("zoom").value = "2";
  app.run("fit()");
  assert.ok(
    app.elements.get("ink").width * app.elements.get("ink").height <= 8_010_000,
  );
});

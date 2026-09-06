const { test } = require("node:test");
const vm = require("node:vm");
const fs = require("node:fs");
const assert = require("node:assert/strict");
const saved = new Map();
const requests = [];
let acceptedBody,
  dropAck = true;
const review = {
  id: "original",
  title: "Test",
  width: 100,
  height: 100,
  status: "pending",
  imageUrl: "/image",
};
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
      toDataURL: () => "data:image/png;base64,cG5n",
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
    window: { addEventListener() {} },
    innerWidth: 1000,
    URL: { createObjectURL: () => "blob:test", revokeObjectURL() {} },
    Image: class {
      naturalWidth = 100;
      naturalHeight = 100;
      set src(value) {
        queueMicrotask(() => this.onload());
      }
    },
    setInterval() {},
    setTimeout,
    clearTimeout,
    AbortController,
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
  return { context, elements, run: (code) => vm.runInContext(code, context) };
}
test("uncertain browser submission survives reload and retries exact feedback", async () => {
  let app = createContext();
  app.run(
    `review=${JSON.stringify(review)}; image={}; strokes=[{width:2,points:[{x:2,y:2,pressure:0.5}]}];submissionId='first';ui.note.value='original comment';`,
  );
  await app.run("ui.send.onclick()");
  assert.equal(app.elements.get("note").disabled, true);
  assert.equal(app.elements.get("undo").disabled, true);
  assert.equal(app.elements.get("send").disabled, false);
  assert.equal(app.elements.get("send").textContent, "Retry same comments");
  assert.ok(JSON.parse(saved.values().next().value).frozenBody);
  app.run("changed()");
  assert.equal(app.run("submissionId"), "first");
  app = createContext();
  app.elements.get("token").value = "test";
  await app.run("ui.pair.onsubmit({preventDefault(){}})");
  assert.equal(app.run("review.id"), "original");
  assert.equal(app.elements.get("note").disabled, true);
  assert.equal(app.elements.get("next").disabled, true);
  await app.run("ui.next.onclick()");
  assert.equal(app.run("review.id"), "original");
  await app.run("ui.send.onclick()");
  assert.equal(requests.length, 2);
  assert.equal(requests[0], requests[1]);
  assert.equal(app.run("submitted"), true);
  console.log(
    "PASS: accepted-but-lost ACK locks edits, preserves exact payload across reload/new current page, identical retry confirms delivery.",
  );
});

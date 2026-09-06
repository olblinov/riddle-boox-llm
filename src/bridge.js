import http from "node:http";
import { timingSafeEqual } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Store, HttpError, fail } from "./store.js";
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
export async function createBridge({
  directory = path.join(root, ".runtime"),
  publicDirectory = path.join(root, "public"),
} = {}) {
  const store = await new Store(directory).init();
  let lastTabletPollAt = null;
  const server = http.createServer(async (req, res) => {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader("X-Frame-Options", "DENY");
    res.setHeader(
      "Content-Security-Policy",
      "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'",
    );
    const json = (status, body) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    };
    try {
      const url = new URL(req.url, "http://localhost");
      if (url.pathname.startsWith("/api/")) {
        const origin = req.headers.origin;
        if (
          origin &&
          origin !== `http://${req.headers.host}` &&
          origin !== `https://${req.headers.host}`
        )
          fail(403, "Origin not allowed");
        const expected = Buffer.from(`Bearer ${store.token}`);
        const actual = Buffer.from(req.headers.authorization ?? "");
        if (
          expected.length !== actual.length ||
          !timingSafeEqual(expected, actual)
        )
          fail(401, "Unauthorized");
        let body = {};
        if (req.method === "POST") {
          if (!req.headers["content-type"]?.startsWith("application/json"))
            fail(415, "JSON required");
          const chunks = [];
          let size = 0;
          for await (const chunk of req) {
            size += chunk.length;
            if (size > 24 * 1024 * 1024) fail(413, "Request too large");
            chunks.push(chunk);
          }
          try {
            body = JSON.parse(Buffer.concat(chunks).toString());
          } catch {
            fail(400, "Invalid JSON");
          }
          if (!body || typeof body !== "object" || Array.isArray(body))
            fail(400, "JSON object required");
        }
        if (req.method === "GET" && url.pathname === "/api/health")
          return json(200, { ok: true, lastTabletPollAt });
        if (req.method === "GET" && url.pathname === "/api/reviews/current") {
          if (req.headers["x-boox-client"] !== "mcp")
            lastTabletPollAt = new Date().toISOString();
          return json(200, { review: store.current() });
        }
        if (req.method === "POST" && url.pathname === "/api/reviews")
          return json(201, await store.create(body));
        const match = url.pathname.match(
          /^\/api\/reviews\/([a-f0-9-]{36})(?:\/(image|feedback|cancel))?$/,
        );
        if (match) {
          const [, id, operation] = match;
          if (req.method === "GET" && !operation)
            return json(200, store.metadata(store.get(id)));
          if (req.method === "GET" && operation === "image") {
            res.writeHead(200, { "Content-Type": "image/png" });
            return res.end(Buffer.from(store.get(id).imageBase64, "base64"));
          }
          if (req.method === "GET" && operation === "feedback")
            return json(200, store.feedback(id));
          if (req.method === "POST" && operation === "feedback")
            return json(200, await store.submit(id, body));
          if (req.method === "POST" && operation === "cancel")
            return json(200, await store.cancel(id));
        }
        fail(404, "Endpoint not found");
      }
      if (req.method !== "GET" && req.method !== "HEAD")
        fail(405, "Method not allowed");
      const name =
        url.pathname === "/"
          ? "index.html"
          : decodeURIComponent(url.pathname).slice(1);
      if (
        !/^[a-zA-Z0-9_./-]+$/.test(name) ||
        name.split("/").some((p) => p === ".." || p.startsWith("."))
      )
        fail(404, "Not found");
      const target = path.resolve(publicDirectory, name);
      if (!target.startsWith(path.resolve(publicDirectory) + path.sep))
        fail(404, "Not found");
      let bytes;
      try {
        bytes = await readFile(target);
      } catch {
        fail(404, "Not found");
      }
      const types = {
        ".html": "text/html; charset=utf-8",
        ".js": "text/javascript; charset=utf-8",
        ".css": "text/css; charset=utf-8",
        ".png": "image/png",
        ".svg": "image/svg+xml",
      };
      res.writeHead(200, {
        "Content-Type":
          types[path.extname(target)] ?? "application/octet-stream",
      });
      res.end(req.method === "HEAD" ? undefined : bytes);
    } catch (error) {
      if (!res.headersSent)
        json(error instanceof HttpError ? error.status : 500, {
          error:
            error instanceof HttpError
              ? error.message
              : "Internal bridge error",
        });
      else res.end();
    }
  });
  server.requestTimeout = 30000;
  server.headersTimeout = 10000;
  return { server, store };
}
if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const { server } = await createBridge({
    directory: process.env.BOOX_DATA_DIR,
  });
  const host = process.env.BOOX_HOST ?? "127.0.0.1";
  const port = Number(process.env.BOOX_PORT ?? 4317);
  server.listen(port, host, () =>
    console.error(
      `BOOX bridge listening on http://${host}:${port}. Pairing token in .runtime/token (or BOOX_DATA_DIR/token).`,
    ),
  );
}

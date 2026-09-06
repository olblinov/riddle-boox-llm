import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { renderPage } from "./render.js";
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
export function createMcp({
  url = process.env.BOOX_BRIDGE_URL ?? "http://127.0.0.1:4317",
  tokenPath = process.env.BOOX_TOKEN_PATH ??
    path.join(
      process.env.BOOX_DATA_DIR ?? path.join(root, ".runtime"),
      "token",
    ),
} = {}) {
  const server = new McpServer({ name: "boox-review", version: "0.1.0" });
  const request = async (endpoint, body) => {
    const token = (await readFile(tokenPath, "utf8")).trim();
    const response = await fetch(new URL(endpoint, url), {
      method: body ? "POST" : "GET",
      headers: {
        "X-Boox-Client": "mcp",
        Authorization: `Bearer ${token}`,
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(10000),
    });
    const result = await response.json();
    if (!response.ok)
      throw new Error(`Bridge ${response.status}: ${result.error}`);
    return result;
  };
  const output = (data) => ({
    content: [{ type: "text", text: JSON.stringify(data) }],
  });
  const safe = (handler) => async (args) => {
    try {
      return await handler(args);
    } catch (error) {
      return {
        isError: true,
        content: [{ type: "text", text: error.message }],
      };
    }
  };
  server.tool(
    "boox_present",
    "Present a persistent page on paired BOOX for handwritten feedback. Provide exactly one PNG path, plain text, or constrained diagram scene. A pending review must be completed or cancelled first.",
    {
      title: z.string().min(1).max(200),
      image_path: z.string().optional(),
      text: z.string().max(20000).optional(),
      scene: z
        .array(
          z.object({
            type: z.enum(["text", "rect", "line", "arrow"]),
            x: z.number(),
            y: z.number(),
            x2: z.number().optional(),
            y2: z.number().optional(),
            width: z.number().optional(),
            height: z.number().optional(),
            size: z.number().optional(),
            text: z.string().optional(),
          }),
        )
        .max(500)
        .optional(),
      width: z.number().int().min(100).max(4096).optional(),
      height: z.number().int().min(100).max(4096).optional(),
    },
    safe(async (args) =>
      output(
        await request("/api/reviews", {
          title: args.title,
          ...(await renderPage(args)),
        }),
      ),
    ),
  );
  server.tool(
    "boox_wait_feedback",
    "Wait up to 45 seconds for explicit Send on BOOX. Pending is not approval. Submitted result includes annotated PNG plus original pen strokes; use same review ID to resume waiting.",
    {
      review_id: z.string().uuid(),
      wait_seconds: z.number().min(0).max(45).default(40),
    },
    safe(async ({ review_id, wait_seconds }) => {
      const deadline = Date.now() + wait_seconds * 1000;
      while (true) {
        const result = await request(`/api/reviews/${review_id}/feedback`);
        if (result.status === "submitted") {
          const { compositeBase64, ...metadata } = result;
          return {
            content: [
              { type: "text", text: JSON.stringify(metadata) },
              { type: "image", mimeType: "image/png", data: compositeBase64 },
            ],
          };
        }
        if (result.status !== "pending" || Date.now() >= deadline)
          return output({ ...result, reviewId: review_id });
        await new Promise((resolve) =>
          setTimeout(resolve, Math.min(500, deadline - Date.now())),
        );
      }
    }),
  );
  server.tool(
    "boox_cancel",
    "Cancel pending BOOX review. Does not delete preserved page or submitted feedback.",
    { review_id: z.string().uuid() },
    safe(async ({ review_id }) =>
      output(await request(`/api/reviews/${review_id}/cancel`, {})),
    ),
  );
  server.tool(
    "boox_status",
    "Read bridge health and current BOOX review. Does not prove a physical tablet is connected.",
    {},
    safe(async () =>
      output({
        health: await request("/api/health"),
        ...(await request("/api/reviews/current")),
      }),
    ),
  );
  return server;
}
if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
)
  await createMcp().connect(new StdioServerTransport());

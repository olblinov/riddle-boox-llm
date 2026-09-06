import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
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
  const server = new McpServer({ name: "boox-review", version: "0.3.0" });
  const request = async (endpoint, body) => {
    const token = (await readFile(tokenPath, "utf8")).trim();
    const serialized = body ? JSON.stringify(body) : undefined;
    if (serialized && Buffer.byteLength(serialized) > 24 * 1024 * 1024)
      throw new Error("Request exceeds 24 MiB; split the document review");
    const response = await fetch(new URL(endpoint, url), {
      method: body ? "POST" : "GET",
      headers: {
        "X-Boox-Client": "mcp",
        Authorization: `Bearer ${token}`,
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      body: serialized,
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
    "Present a persistent page on paired BOOX for handwritten feedback. Provide exactly one PNG path, Markdown file path, plain text, or constrained diagram scene. Markdown defaults to a whole-document review: browse and annotate all pages, then Send once. Explicit markdown_page selects a legacy single-page review. Retain source checksum before adapting the original file. A pending review must be completed or cancelled first.",
    {
      title: z.string().min(1).max(200),
      image_path: z.string().optional(),
      markdown_path: z.string().optional(),
      markdown_page: z.number().int().min(1).max(200).optional(),
      expected_sha256: z
        .string()
        .regex(/^[a-f0-9]{64}$/)
        .optional(),
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
    safe(async (args) => {
      const inputs = [
        args.image_path,
        args.markdown_path,
        args.text,
        args.scene,
      ];
      if (inputs.filter((value) => value !== undefined).length !== 1) {
        throw new Error(
          "Provide exactly one of image_path, markdown_path, text, scene",
        );
      }
      if (args.markdown_path !== undefined) {
        const { renderMarkdownFile } = await import("./markdown.js");
        const document = await renderMarkdownFile(args.markdown_path, {
          width: args.width,
          height: args.height,
          expectedSha256: args.expected_sha256,
        });
        const index = args.markdown_page;
        const page =
          index === undefined ? undefined : document.pages[index - 1];
        if (index !== undefined && !page)
          throw new Error(
            `Markdown has ${document.pages.length} pages; requested ${index}`,
          );
        const original = await readFile(document.source.path);
        if (
          createHash("sha256").update(original).digest("hex") !==
          document.source.sha256
        ) {
          throw new Error(
            "Markdown changed during rendering; retry before requesting feedback",
          );
        }
        const snapshotDirectory = path.join(
          path.dirname(path.resolve(tokenPath)),
          "documents",
        );
        await mkdir(snapshotDirectory, { recursive: true, mode: 0o700 });
        const snapshotPath = path.join(
          snapshotDirectory,
          `${document.source.sha256}.md`,
        );
        try {
          await writeFile(snapshotPath, original, { mode: 0o600, flag: "wx" });
        } catch (error) {
          if (error.code !== "EEXIST") throw error;
          if (
            createHash("sha256")
              .update(await readFile(snapshotPath))
              .digest("hex") !== document.source.sha256
          ) {
            throw new Error("Stored Markdown snapshot checksum mismatch");
          }
        }
        const pagePayload = (page) => ({
          imageBase64: page.imageBase64,
          width: page.width,
          height: page.height,
          source: {
            kind: "markdown",
            ...document.source,
            snapshotPath,
            pageIndex: page.pageIndex,
            pageCount: document.pages.length,
            startLine: page.sourceStartLine,
            endLine: page.sourceEndLine,
          },
        });
        return output(
          await request(
            "/api/reviews",
            index === undefined
              ? {
                  title: args.title,
                  pages: document.pages.map(pagePayload),
                }
              : {
                  title: `${args.title.slice(0, 175)} · ${index}/${document.pages.length}`,
                  ...pagePayload(page),
                },
          ),
        );
      }
      if (
        args.markdown_page !== undefined ||
        args.expected_sha256 !== undefined
      ) {
        throw new Error(
          "markdown_page and expected_sha256 require markdown_path",
        );
      }
      return output(
        await request("/api/reviews", {
          title: args.title,
          ...(await renderPage(args)),
        }),
      );
    }),
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
          if (result.pages) {
            const { pages, ...metadata } = result;
            return {
              content: [
                {
                  type: "text",
                  text: JSON.stringify({
                    ...metadata,
                    pageCount: pages.length,
                  }),
                },
                ...pages.flatMap(({ compositeBase64, ...page }) => [
                  {
                    type: "text",
                    text: JSON.stringify({
                      label: `Annotated page ${page.pageIndex}/${pages.length}`,
                      ...page,
                    }),
                  },
                  {
                    type: "image",
                    mimeType: "image/png",
                    data: compositeBase64,
                  },
                ]),
              ],
            };
          }
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

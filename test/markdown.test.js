import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, readFile, rm } from "node:fs/promises";
import { createHash } from "node:crypto";
import os from "node:os";
import path from "node:path";
import sharp from "sharp";
import { layoutMarkdown, renderMarkdownFile } from "../src/markdown.js";

async function sourceFile(t, text) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "boox-markdown-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const file = path.join(directory, "source.md");
  await writeFile(file, text);
  return file;
}
const allRows = (layout) => layout.pages.flatMap((page) => page.rows);

test("Markdown renders semantic styles, source hash, page dimensions without mutating source", async (t) => {
  const markdown =
    "# Heading\n\n**Bold** and *italic* with `code` and ~~old~~.\n\n- First\n- Second\n\n> Quote\n";
  const file = await sourceFile(t, markdown);
  const before = await readFile(file);
  const result = await renderMarkdownFile(file);
  assert.equal(result.source.path, file);
  assert.equal(
    result.source.sha256,
    createHash("sha256").update(before).digest("hex"),
  );
  assert.deepEqual(await readFile(file), before);
  const metadata = await sharp(
    Buffer.from(result.pages[0].imageBase64, "base64"),
  ).metadata();
  assert.equal(metadata.width, 1404);
  assert.equal(metadata.height, 1872);
  const rows = allRows(await layoutMarkdown(markdown));
  const runs = rows.flatMap((row) => row.cells.flatMap((cell) => cell.runs));
  for (const [text, style] of [
    ["Bold", "bold"],
    ["italic", "italic"],
    ["code", "code"],
    ["old", "strike"],
  ])
    assert.ok(runs.some((run) => run.text.includes(text) && run[style]));
  assert.ok(rows.some((row) => row.quote));
  assert.ok(rows.some((row) => row.text.includes("• First")));
});

test("long code paginates with every character and exact source line mapping", async (t) => {
  const codeLines = Array.from(
    { length: 90 },
    (_, i) => `  const value${i} = "${"longidentifier".repeat(14)}";`,
  );
  const markdown = "# Code\n\n```js\n" + codeLines.join("\n") + "\n```\n";
  const file = await sourceFile(t, markdown);
  const layout = await layoutMarkdown(markdown, { width: 1000, height: 1000 });
  assert.ok(layout.pages.length > 5);
  for (const [index, original] of codeLines.entries()) {
    const lines = allRows(layout).filter(
      (row) => row.code && row.start === index + 4,
    );
    assert.equal(lines.map((row) => row.text).join(""), original);
    assert.ok(lines.every((row) => row.end === index + 4));
  }
  const rendered = await renderMarkdownFile(file, {
    width: 1000,
    height: 1000,
  });
  assert.equal(rendered.pages.length, layout.pages.length);
  for (const [index, page] of rendered.pages.entries()) {
    assert.equal(page.pageIndex, index + 1);
    assert.equal(page.pageCount, rendered.pages.length);
    assert.ok(page.sourceStartLine >= 1);
    assert.ok(page.sourceEndLine >= page.sourceStartLine);
  }
});

test("long GFM tables preserve cells across page breaks and wide column bands", async () => {
  const headers = Array.from({ length: 9 }, (_, i) => `Header${i}`);
  const cells = Array.from(
    { length: 9 },
    (_, i) => `cell${i} ${"details ".repeat(60)}`,
  );
  const markdown = `|${headers.join("|")}|\n|${headers.map(() => "---").join("|")}|\n|${cells.join("|")}|\n`;
  const layout = await layoutMarkdown(markdown, { width: 1000, height: 1000 });
  assert.ok(layout.pages.length > 1);
  const rows = allRows(layout);
  for (let i = 0; i < cells.length; i++) {
    assert.ok(rows.some((row) => row.text.includes(headers[i])));
    assert.ok(rows.some((row) => row.text.includes(`cell${i}`)));
  }
  const body = rows.filter((row) => row.table && !row.header);
  assert.equal(
    body
      .flatMap((row) =>
        row.cells.flatMap((cell) => cell.runs.map((run) => run.text)),
      )
      .join("")
      .match(/details/g).length,
    9 * 60,
  );
  assert.ok(body.every((row) => row.start === 3 && row.end === 3));
});

test("HTML, remote images and links remain inert visible text; hash mismatch and limits fail explicitly", async (t) => {
  const markdown =
    '<script>fetch("https://not-called.invalid")</script>\n\n![private](http://127.0.0.1/secret)\n\n[link](https://not-called.invalid)\n';
  const layout = await layoutMarkdown(markdown);
  const visible = allRows(layout)
    .map((row) => row.text)
    .join("");
  assert.ok(visible.includes("<script>"));
  assert.ok(visible.includes("Image not rendered: private"));
  assert.ok(visible.includes("http://127.0.0.1/secret"));
  assert.ok(visible.includes("https://not-called.invalid"));
  const file = await sourceFile(t, markdown);
  await assert.rejects(
    renderMarkdownFile(file, { expectedSha256: "0".repeat(64) }),
    /SHA-256 mismatch/,
  );
  await assert.rejects(renderMarkdownFile(file, { width: 100 }), /dimensions/);
  await writeFile(file, "x".repeat(256 * 1024 + 1));
  await assert.rejects(renderMarkdownFile(file), /exceeds/);
  await assert.rejects(layoutMarkdown("test", { deadline: 0 }), /40 seconds/);
});

test("code indentation remains visible in raster pixels", async (t) => {
  const markdown = "```yaml\nbase:\n  child: true\n```\n";
  const file = await sourceFile(t, markdown);
  const layout = await layoutMarkdown(markdown);
  const result = await renderMarkdownFile(file);
  const { data, info } = await sharp(
    Buffer.from(result.pages[0].imageBase64, "base64"),
  )
    .removeAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const codeRows = layout.pages[0].rows.filter((row) => row.code);
  const firstInk = (row) => {
    let minimum = Infinity;
    for (let y = Math.ceil(row.y); y < Math.floor(row.y + row.height); y++) {
      for (
        let x = layout.geometry.margin;
        x < layout.geometry.margin + layout.geometry.contentWidth;
        x++
      ) {
        const offset = (y * info.width + x) * info.channels;
        if (data[offset] < 80 && data[offset + 1] < 80 && data[offset + 2] < 80)
          minimum = Math.min(minimum, x);
      }
    }
    return minimum;
  };
  assert.ok(
    firstInk(codeRows[1]) - firstInk(codeRows[0]) >= 20,
    "two code spaces must produce visible indentation",
  );
});

test("local Markdown image renders pixels with source mapping; outside and active content stays blocked", async (t) => {
  const file = await sourceFile(t, "# Images\n\n![red square](picture.png)\n");
  const image = await sharp({
    create: { width: 80, height: 60, channels: 3, background: "#cc0000" },
  })
    .png()
    .toBuffer();
  await writeFile(path.join(path.dirname(file), "picture.png"), image);
  const layout = await layoutMarkdown(await readFile(file, "utf8"), {
    sourceDirectory: path.dirname(file),
  });
  const figure = allRows(layout).find((row) => row.figure);
  assert.ok(figure);
  assert.equal(figure.start, 3);
  assert.equal(figure.end, 3);
  const rendered = await renderMarkdownFile(file);
  const pixel = await sharp(
    Buffer.from(rendered.pages[0].imageBase64, "base64"),
  )
    .extract({
      left: layout.geometry.margin + 10,
      top: Math.round(figure.y) + 10,
      width: 1,
      height: 1,
    })
    .removeAlpha()
    .raw()
    .toBuffer();
  assert.ok(pixel[0] > 150 && pixel[1] < 30 && pixel[2] < 30);
  const { symlink } = await import("node:fs/promises");
  await symlink(os.tmpdir(), path.join(path.dirname(file), "escape"));
  await writeFile(
    path.join(path.dirname(file), "active.svg"),
    '<svg xmlns="http://www.w3.org/2000/svg"><image href="file:///etc/passwd"/></svg>',
  );
  const blocked = await layoutMarkdown(
    "![up](../secret.png)\n\n![absolute](/etc/passwd)\n\n![remote](https://example.invalid/private.png)\n\n![symlink](escape)\n\n![svg](active.svg)",
    { sourceDirectory: path.dirname(file) },
  );
  assert.equal(allRows(blocked).filter((row) => row.figure).length, 0);
  assert.equal(
    allRows(blocked)
      .map((row) => row.text)
      .join("")
      .match(/Image not rendered/g).length,
    5,
  );
});

test("Mermaid renders real diagram pixels instead of source code and preserves fence lines", async (t) => {
  const { access } = await import("node:fs/promises");
  const candidates = [
    process.env.BOOX_CHROME_PATH,
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/usr/bin/google-chrome",
  ].filter(Boolean);
  let available = false;
  for (const candidate of candidates) {
    try {
      await access(candidate);
      available = true;
      break;
    } catch {}
  }
  if (!available) {
    t.skip("Installed Chrome/Chromium required for Mermaid raster test");
    return;
  }
  const markdown =
    "# Diagram\n\n```mermaid\nflowchart LR\n  A[Tablet] --> B[Codex]\n```\n";
  const layout = await layoutMarkdown(markdown);
  const figure = allRows(layout).find((row) => row.figure);
  assert.ok(
    figure,
    allRows(layout)
      .map((row) => row.text)
      .join("\n"),
  );
  assert.equal(figure.start, 3);
  assert.equal(figure.end, 6);
  assert.equal(allRows(layout).filter((row) => row.code).length, 0);
  const statistics = await sharp(figure.figure).stats();
  assert.ok(statistics.channels[0].min < 50);
  assert.ok(statistics.channels[0].max > 200);
});

test("Mermaid cannot override strict renderer configuration", async () => {
  const layout = await layoutMarkdown(
    '```mermaid\n%%{init: {securityLevel: "loose"}}%%\nflowchart LR\n A-->B\n```',
  );
  assert.equal(allRows(layout).filter((row) => row.figure).length, 0);
  assert.match(
    allRows(layout)
      .map((row) => row.text)
      .join(""),
    /configuration directives are disabled/,
  );
});

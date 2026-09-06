import MarkdownIt from "markdown-it";
import sharp from "sharp";
import { open, realpath, access } from "node:fs/promises";
import { constants } from "node:fs";
import { createRequire } from "node:module";
import { createHash } from "node:crypto";
import path from "node:path";

const MAX_BYTES = 256 * 1024;
const MAX_PAGES = 200;
const parser = new MarkdownIt({
  html: false,
  linkify: false,
  typographer: false,
});
const escape = (text) =>
  String(text).replace(
    /[&<>"']/g,
    (c) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&apos;",
      })[c],
  );

function inlineRuns(tokens = []) {
  const result = [];
  const styles = { bold: 0, italic: 0, strike: 0 };
  const links = [];
  const add = (text, extra = {}) => {
    if (text)
      result.push({
        text,
        bold: styles.bold > 0,
        italic: styles.italic > 0,
        strike: styles.strike > 0,
        ...extra,
      });
  };
  for (const token of tokens) {
    if (token.type === "strong_open") styles.bold++;
    else if (token.type === "strong_close") styles.bold--;
    else if (token.type === "em_open") styles.italic++;
    else if (token.type === "em_close") styles.italic--;
    else if (token.type === "s_open") styles.strike++;
    else if (token.type === "s_close") styles.strike--;
    else if (token.type === "code_inline") add(token.content, { code: true });
    else if (token.type === "softbreak") add(" ");
    else if (token.type === "hardbreak") add("\n");
    else if (token.type === "link_open")
      links.push(token.attrGet("href") ?? "");
    else if (token.type === "link_close") {
      const href = links.pop();
      if (href) add(` (${href})`);
    } else if (token.type === "image")
      add(
        `[Image not rendered: ${token.content || "no alt text"} (${token.attrGet("src") || "no source"})]`,
        {
          italic: true,
          imageSource: token.attrGet("src") || "",
          imageAlt: token.content || "",
        },
      );
    else if (token.type === "text" || token.type === "html_inline")
      add(token.content);
    else if (token.content) add(`[${token.type}: ${token.content}]`);
  }
  return result;
}

function markup(runs) {
  return runs
    .map((run) => {
      let value = escape(run.text);
      if (run.code) value = `<span font_family="monospace">${value}</span>`;
      if (run.bold) value = `<b>${value}</b>`;
      if (run.italic) value = `<i>${value}</i>`;
      if (run.strike) value = `<s>${value}</s>`;
      return value;
    })
    .join("");
}

function joinRuns(runs) {
  const merged = [];
  for (const run of runs) {
    const previous = merged.at(-1);
    if (
      previous &&
      ["bold", "italic", "code", "strike"].every(
        (key) => previous[key] === run[key],
      )
    )
      previous.text += run.text;
    else merged.push({ ...run });
  }
  return merged;
}

function dimensions(width, height) {
  if (
    !Number.isInteger(width) ||
    !Number.isInteger(height) ||
    width < 600 ||
    height < 800 ||
    width > 4096 ||
    height > 4096 ||
    width * height > 16_000_000
  )
    throw new Error(
      "Markdown page dimensions must be 600–4096 by 800–4096, at most 16 million pixels",
    );
  const scale = width / 1404;
  // Keep native-size text and a uniform writable border for handwritten notes.
  const font = Math.max(20, Math.round(36 * scale));
  const margin = Math.round(96 * scale);
  return {
    width,
    height,
    font,
    margin,
    contentWidth: width - margin * 2,
    top: margin,
    bottom: margin,
  };
}

const require = createRequire(import.meta.url);

async function localImage(source, sourceDirectory) {
  if (!sourceDirectory) throw new Error("no source directory available");
  const decoded = decodeURIComponent(source);
  if (
    !decoded ||
    /^(?:[a-z][a-z0-9+.-]*:|[\\/])/i.test(decoded) ||
    decoded.includes("\0")
  )
    throw new Error("only relative local image paths are allowed");
  if (
    decoded.split(/[\\/]/).some((part) => part.startsWith(".") && part !== ".")
  )
    throw new Error("hidden paths and parent traversal are not allowed");
  const root = await realpath(sourceDirectory);
  const filename = await realpath(path.resolve(root, decoded));
  if (!filename.startsWith(root + path.sep))
    throw new Error("image escapes source directory");
  const file = await open(filename, constants.O_RDONLY | constants.O_NOFOLLOW);
  let bytes;
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.size > 10 * 1024 * 1024)
      throw new Error("image must be a regular file under 10 MiB");
    const buffer = Buffer.alloc(Math.min(stat.size + 1, 10 * 1024 * 1024 + 1));
    let length = 0;
    while (length < buffer.length) {
      const read = await file.read(
        buffer,
        length,
        buffer.length - length,
        null,
      );
      if (!read.bytesRead) break;
      length += read.bytesRead;
    }
    if (length > stat.size) throw new Error("image changed while reading");
    bytes = buffer.subarray(0, length);
  } finally {
    await file.close();
  }
  const raster =
    bytes
      .subarray(0, 8)
      .equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) ||
    (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) ||
    /^GIF8[79]a/.test(bytes.subarray(0, 6).toString("ascii")) ||
    (bytes.subarray(0, 4).toString("ascii") === "RIFF" &&
      bytes.subarray(8, 12).toString("ascii") === "WEBP");
  if (!raster) throw new Error("supported image formats: PNG, JPEG, WebP, GIF");
  const metadata = await sharp(bytes, {
    limitInputPixels: 16_000_000,
  }).metadata();
  if (!["png", "jpeg", "webp", "gif"].includes(metadata.format))
    throw new Error("supported image formats: PNG, JPEG, WebP, GIF");
  return sharp(bytes, { limitInputPixels: 16_000_000 })
    .rotate()
    .flatten({ background: "white" })
    .png()
    .toBuffer();
}

async function mermaidImage(source, width, maxHeight, deadline) {
  if (source.length > 16384) throw new Error("Mermaid diagram exceeds 16 KiB");
  if (/%%\s*\{|^\s*---/m.test(source))
    throw new Error("Mermaid configuration directives are disabled");
  const { default: puppeteer } = await import("puppeteer-core");
  const candidates = [
    process.env.BOOX_CHROME_PATH,
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/usr/bin/google-chrome",
  ].filter(Boolean);
  let executablePath;
  for (const candidate of candidates) {
    try {
      await access(candidate, constants.X_OK);
      executablePath = candidate;
      break;
    } catch {}
  }
  if (!executablePath)
    throw new Error("Chrome/Chromium required; configure BOOX_CHROME_PATH");
  const remaining = Math.max(1, Math.min(15000, deadline - Date.now()));
  const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    timeout: remaining,
    protocolTimeout: remaining,
    args: [
      "--disable-background-networking",
      "--disable-component-update",
      "--disable-sync",
      "--no-first-run",
    ],
  });
  const timer = setTimeout(
    () => void browser.close().catch(() => {}),
    remaining,
  );
  try {
    const page = await browser.newPage();
    await page.setRequestInterception(true);
    page.on("request", (request) => void request.abort());
    await page.setViewport({
      width: Math.round(width),
      height: 1200,
      deviceScaleFactor: 2,
    });
    await page.setContent(
      '<html><head></head><body style="margin:0;background:white"><div id="diagram"></div></body></html>',
    );
    await page.addScriptTag({
      path: path.join(
        path.dirname(require.resolve("mermaid/package.json")),
        "dist/mermaid.min.js",
      ),
    });
    await page.evaluate(
      async ({ source, maxHeight }) => {
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: "strict",
          theme: "neutral",
          fontFamily: "Arial",
          fontSize: 24,
          htmlLabels: false,
          flowchart: { htmlLabels: false, useMaxWidth: false },
          maxTextSize: 16384,
          maxEdges: 200,
          suppressErrorRendering: true,
        });
        const result = await mermaid.render("boox-diagram", source);
        document.getElementById("diagram").innerHTML = result.svg;
        const svg = document.querySelector("#diagram > svg");
        if (!svg) throw new Error("Mermaid did not produce a diagram");
        svg.style.maxWidth = "none";
        const box = svg.viewBox.baseVal;
        if (!box.width || !box.height || box.height / box.width > 15)
          throw new Error("Diagram dimensions unsupported");
        const targetWidth = Math.min(
          window.innerWidth,
          Math.max(box.width, 600),
        );
        const targetHeight = (targetWidth * box.height) / box.width;
        const scale = Math.min(1, maxHeight / targetHeight);
        svg.setAttribute("width", String(targetWidth * scale));
        svg.setAttribute("height", String(targetHeight * scale));
      },
      { source, maxHeight },
    );
    const diagram = await page.$("#diagram > svg");
    return Buffer.from(await diagram.screenshot({ type: "png" }));
  } finally {
    clearTimeout(timer);
    await browser.close();
  }
}

/** Internal layout is exported for tests of text preservation and source mapping. */
export async function layoutMarkdown(
  source,
  {
    width = 1404,
    height = 1872,
    deadline = Date.now() + 40000,
    sourceDirectory,
  } = {},
) {
  const geometry = dimensions(width, height);
  const { font, margin, contentWidth } = geometry;
  const tokens = parser.parse(source, {});
  const rows = [];
  const measurement = new Map();
  const measure = async (runs, size) => {
    if (Date.now() > deadline)
      throw new Error(
        "Markdown render exceeded 40 seconds; split the source document",
      );
    const value = markup(runs);
    if (!value || !runs.some((run) => /\S/u.test(run.text))) return 0;
    const key = `${size}:${value}`;
    if (!measurement.has(key)) {
      let measured = (
        await sharp({
          text: { text: value, font: `sans ${size}`, rgba: true },
        }).metadata()
      ).width;
      const leading = runs
        .map((run) => run.text)
        .join("")
        .match(/^ +/u)?.[0];
      if (leading && runs[0]?.code) {
        const sample = async (text) =>
          (
            await sharp({
              text: {
                text: markup([{ ...runs[0], text }]),
                font: `sans ${size}`,
                rgba: true,
              },
            }).metadata()
          ).width;
        measured += (await sample(`X${leading}X`)) - (await sample("XX"));
      }
      measurement.set(key, measured);
    }
    return measurement.get(key);
  };
  const wrap = async (runs, available, size) => {
    const lines = [];
    let line = [];
    const flush = () => {
      lines.push(joinRuns(line));
      line = [];
    };
    // Whitespace belongs to the layout too, including indentation in code.
    for (const run of runs) {
      const parts = run.text
        .split(/(\n|[^\S\n]+|[^\s]+)/u)
        .filter(Boolean)
        .flatMap((part) =>
          Array.from(part).length > 256 ? part.match(/.{1,256}/gu) : [part],
        );
      for (const part of parts) {
        if (part === "\n") {
          flush();
          continue;
        }
        const addition = { ...run, text: part };
        if ((await measure([...line, addition], size)) <= available) {
          line.push(addition);
          continue;
        }
        if (line.length && /\S/u.test(line.map((r) => r.text).join("")))
          flush();
        // Split long URLs, code identifiers and CJK text by Unicode code point.
        if ((await measure([addition], size)) > available) {
          for (const character of part) {
            const atom = { ...run, text: character };
            if (
              line.length &&
              (await measure([...line, atom], size)) > available
            )
              flush();
            line.push(atom);
          }
        } else line.push(addition);
      }
    }
    if (line.length || !lines.length) flush();
    return lines;
  };
  const addText = async (runs, range, options = {}) => {
    const size = options.size ?? font;
    const indent = options.indent ?? 0;
    const lineHeight = Math.ceil(size * 1.55);
    const lines = await wrap(
      runs,
      contentWidth - indent - (options.code ? 24 : 0),
      size,
    );
    for (const line of lines)
      rows.push({
        start: range[0] + 1,
        end: Math.max(range[0] + 1, range[1]),
        height: lineHeight,
        cells: [
          {
            x: indent + (options.code ? 12 : 0),
            width: contentWidth - indent,
            runs: line,
            size,
          },
        ],
        text: line.map((r) => r.text).join(""),
        quote: options.quote,
        code: options.code,
      });
  };
  let figurePixels = 0;
  const addFigure = async (input, range, label) => {
    const { data, info } = await sharp(input)
      .resize({
        width: Math.floor(contentWidth),
        height: height - geometry.top - geometry.bottom,
        fit: "inside",
      })
      .flatten({ background: "white" })
      .png()
      .toBuffer({ resolveWithObject: true });
    figurePixels += info.width * info.height;
    if (figurePixels > 32_000_000)
      throw new Error(
        "Document figure budget exceeded; split the source document",
      );
    rows.push({
      start: range[0] + 1,
      end: Math.max(range[0] + 1, range[1]),
      height: info.height,
      cells: [],
      text: label,
      figure: data,
      figureWidth: info.width,
    });
  };
  const gap = (amount = Math.ceil(font * 0.55)) =>
    rows.push({ height: amount, cells: [], text: "", gap: true });
  let quote = 0,
    heading = 0;
  const lists = [];
  let prefix = "";
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    if (token.type === "blockquote_open") {
      quote++;
      continue;
    }
    if (token.type === "blockquote_close") {
      quote--;
      gap();
      continue;
    }
    if (
      token.type === "bullet_list_open" ||
      token.type === "ordered_list_open"
    ) {
      lists.push({
        ordered: token.type === "ordered_list_open",
        number: Number(token.attrGet("start") || 1),
      });
      continue;
    }
    if (
      token.type === "bullet_list_close" ||
      token.type === "ordered_list_close"
    ) {
      lists.pop();
      gap();
      continue;
    }
    if (token.type === "list_item_close") {
      prefix = "";
      continue;
    }
    if (token.type === "list_item_open") {
      const list = lists.at(-1);
      prefix = list?.ordered ? `${list.number++}. ` : "• ";
      continue;
    }
    if (token.type === "heading_open") {
      heading = Number(token.tag.slice(1));
      gap();
      continue;
    }
    if (token.type === "heading_close") {
      heading = 0;
      gap();
      continue;
    }
    if (token.type === "paragraph_close") {
      gap();
      continue;
    }
    if (token.type === "inline") {
      let runs = inlineRuns(token.children);
      if (prefix) {
        runs.unshift({ text: prefix });
        prefix = "";
      }
      if (heading) runs = runs.map((run) => ({ ...run, bold: true }));
      const range = token.map ?? [0, 1];
      const segments = [];
      let textRuns = [];
      for (const run of runs) {
        if (run.imageSource !== undefined) {
          if (textRuns.length) segments.push({ runs: textRuns });
          segments.push({ image: run });
          textRuns = [];
        } else textRuns.push(run);
      }
      if (textRuns.length) segments.push({ runs: textRuns });
      for (const segment of segments) {
        if (segment.image) {
          try {
            await addFigure(
              await localImage(segment.image.imageSource, sourceDirectory),
              range,
              segment.image.imageAlt,
            );
          } catch (error) {
            await addText(
              [
                {
                  text: `[Image not rendered: ${segment.image.imageAlt || "no alt text"} (${segment.image.imageSource}) — ${error.code ? "file unavailable" : error.message}]`,
                  italic: true,
                },
              ],
              range,
            );
          }
          continue;
        }
        await addText(segment.runs, range, {
          size: heading
            ? Math.round(font * ({ 1: 1.65, 2: 1.4, 3: 1.2 }[heading] ?? 1.08))
            : font,
          indent: Math.min(
            contentWidth / 3,
            Math.max(0, lists.length - 1) * font + quote * font,
          ),
          quote: quote > 0,
        });
      }
      continue;
    }
    if (token.type === "fence" || token.type === "code_block") {
      gap();
      const range = token.map ?? [0, 1];
      if (
        token.type === "fence" &&
        token.info.trim().toLowerCase() === "mermaid"
      ) {
        try {
          await addFigure(
            await mermaidImage(
              token.content,
              contentWidth,
              height - geometry.top - geometry.bottom,
              deadline,
            ),
            range,
            "Mermaid diagram",
          );
          gap();
          continue;
        } catch (error) {
          await addText(
            [
              {
                text: `[Mermaid not rendered: ${String(error.message).slice(0, 240)}]`,
                italic: true,
              },
            ],
            range,
          );
        }
      }
      if (token.info)
        await addText([{ text: `Code · ${token.info}`, bold: true }], range, {
          size: Math.round(font * 0.8),
        });
      const lines = token.content.replace(/\n$/, "").split("\n");
      for (let index = 0; index < lines.length; index++) {
        const sourceLine = range[0] + (token.type === "fence" ? 1 : 0) + index;
        await addText(
          [{ text: lines[index].replace(/\t/g, "    ") || " ", code: true }],
          [sourceLine, sourceLine + 1],
          { code: true, size: Math.round(font * 0.88) },
        );
      }
      gap();
      continue;
    }
    if (token.type === "hr") {
      rows.push({
        start: token.map[0] + 1,
        end: token.map[1],
        height: font,
        cells: [],
        text: "",
        rule: true,
      });
      continue;
    }
    if (token.type === "table_open") {
      gap();
      const range = token.map ?? [0, 1];
      const tableRows = [];
      let row = null,
        cell = null,
        header = false;
      while (++i < tokens.length && tokens[i].type !== "table_close") {
        const part = tokens[i];
        if (part.type === "thead_open") header = true;
        if (part.type === "thead_close") header = false;
        if (part.type === "tr_open") {
          row = { cells: [], header, range: part.map };
          tableRows.push(row);
        }
        if (part.type === "th_open" || part.type === "td_open") {
          cell = [];
          row.cells.push(cell);
        }
        if (part.type === "inline")
          cell.push(
            ...inlineRuns(part.children).map((run) => ({
              ...run,
              bold: header || run.bold,
            })),
          );
      }
      const columns = Math.max(...tableRows.map((r) => r.cells.length));
      const perBand = Math.max(
        1,
        Math.floor(contentWidth / Math.max(130, font * 5)),
      );
      for (let first = 0; first < columns; first += perBand) {
        const count = Math.min(perBand, columns - first),
          cellWidth = contentWidth / count;
        if (columns > perBand)
          await addText(
            [
              {
                text: `Table columns ${first + 1}–${first + count} of ${columns}`,
                italic: true,
              },
            ],
            range,
            { size: Math.round(font * 0.8) },
          );
        for (let r = 0; r < tableRows.length; r++) {
          const tableRow = tableRows[r];
          const wrapped = await Promise.all(
            Array.from({ length: count }, (_, n) =>
              wrap(
                tableRow.cells[first + n] ?? [],
                cellWidth - 20,
                Math.round(font * 0.88),
              ),
            ),
          );
          const lineCount = Math.max(...wrapped.map((lines) => lines.length));
          const rowRange = tableRow.range ?? [
            range[0] + r + (r ? 1 : 0),
            range[0] + r + (r ? 2 : 1),
          ];
          for (let line = 0; line < lineCount; line++) {
            const cells = wrapped.map((lines, n) => ({
              x: n * cellWidth + 10,
              width: cellWidth,
              runs: lines[line] ?? [],
              size: Math.round(font * 0.88),
            }));
            rows.push({
              start: rowRange[0] + 1,
              end: rowRange[1],
              height: Math.ceil(font * 1.5),
              cells,
              table: true,
              tableFirst: line === 0,
              tableLast: line === lineCount - 1,
              header: tableRow.header,
              text: cells
                .map((c) => c.runs.map((run) => run.text).join(""))
                .join(" | "),
            });
          }
        }
        gap();
      }
      continue;
    }
    if (token.content && !token.type.endsWith("_close"))
      await addText(
        [{ text: `[Unsupported ${token.type}] ${token.content}` }],
        token.map ?? [0, 1],
      );
  }
  if (!rows.some((row) => !row.gap))
    await addText([{ text: "(Empty document)", italic: true }], [0, 1]);
  const pages = [];
  let page = { rows: [], sourceStartLine: Infinity, sourceEndLine: 1 },
    y = geometry.top;
  const finish = () => {
    if (!page.rows.some((row) => !row.gap)) return;
    pages.push(page);
    if (pages.length > MAX_PAGES)
      throw new Error(
        `Markdown exceeds ${MAX_PAGES} pages; split the source document`,
      );
    page = { rows: [], sourceStartLine: Infinity, sourceEndLine: 1 };
    y = geometry.top;
  };
  for (const row of rows) {
    if (row.height > height - geometry.top - geometry.bottom)
      throw new Error("Page too short for Markdown row");
    if (y + row.height > height - geometry.bottom) finish();
    if (row.gap && !page.rows.length) continue;
    page.rows.push({ ...row, y });
    y += row.height;
    if (row.start) {
      page.sourceStartLine = Math.min(page.sourceStartLine, row.start);
      page.sourceEndLine = Math.max(page.sourceEndLine, row.end);
    }
  }
  finish();
  return { geometry, pages };
}

async function rasterizePage(page, geometry, index, count, filename, deadline) {
  const { width, height, margin, contentWidth, font } = geometry;
  const overlays = [];
  const decorations = [];
  const textImage = async (runs, size, left, top) => {
    if (!runs.some((run) => /\S/u.test(run.text))) return;
    const input = await sharp({
      text: { text: markup(runs), font: `sans ${size}`, rgba: true },
    })
      .png()
      .toBuffer();
    const leading = runs
      .map((run) => run.text)
      .join("")
      .match(/^ +/u)?.[0];
    if (leading && runs[0]?.code) {
      const sample = async (value) =>
        (
          await sharp({
            text: {
              text: markup([{ ...runs[0], text: value }]),
              font: `sans ${size}`,
              rgba: true,
            },
          }).metadata()
        ).width;
      left += (await sample(`X${leading}X`)) - (await sample("XX"));
    }
    overlays.push({ input, left: Math.round(left), top: Math.round(top) });
  };
  for (const row of page.rows) {
    if (Date.now() > deadline)
      throw new Error(
        "Markdown render exceeded 40 seconds; split the source document",
      );
    if (row.figure)
      overlays.push({
        input: row.figure,
        left: margin,
        top: Math.round(row.y),
      });
    if (row.code || row.header)
      decorations.push(
        `<rect x="${margin}" y="${row.y}" width="${contentWidth}" height="${row.height}" fill="#eeeeee"/>`,
      );
    if (row.quote)
      decorations.push(
        `<line x1="${margin + font / 2}" y1="${row.y}" x2="${margin + font / 2}" y2="${row.y + row.height}" stroke="black" stroke-width="3"/>`,
      );
    if (row.rule)
      decorations.push(
        `<line x1="${margin}" y1="${row.y + row.height / 2}" x2="${margin + contentWidth}" y2="${row.y + row.height / 2}" stroke="black" stroke-width="2"/>`,
      );
    if (row.table)
      for (const cell of row.cells) {
        const x = margin + cell.x - 10,
          right = x + cell.width;
        decorations.push(
          `<path d="M${x},${row.y}V${row.y + row.height} M${right},${row.y}V${row.y + row.height}${row.tableFirst || row === page.rows[0] ? ` M${x},${row.y}H${right}` : ""}${row.tableLast || row === page.rows.at(-1) ? ` M${x},${row.y + row.height}H${right}` : ""}" fill="none" stroke="#555" stroke-width="1"/>`,
        );
      }
    for (const cell of row.cells)
      await textImage(
        cell.runs,
        cell.size,
        margin + cell.x,
        row.y + Math.max(2, (row.height - cell.size) / 2),
      );
  }
  const label = `${filename.length > 54 ? filename.slice(0, 51) + "…" : filename} · lines ${page.sourceStartLine}–${page.sourceEndLine} · ${index}/${count}`;
  await textImage(
    [{ text: label }],
    Math.max(14, Math.round(font * 0.63)),
    margin,
    height - Math.max(24, Math.round((32 * width) / 1404)),
  );
  const background = Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="white"/>${decorations.join("")}</svg>`,
  );
  return sharp(background).composite(overlays).png().toBuffer();
}

export async function renderMarkdownFile(
  filePath,
  { width = 1404, height = 1872, expectedSha256 } = {},
) {
  const absolutePath = path.resolve(filePath);
  const file = await open(absolutePath, "r");
  let bytes;
  try {
    const stat = await file.stat();
    if (!stat.isFile())
      throw new Error("Markdown source must be a regular file");
    if (stat.size > MAX_BYTES)
      throw new Error(`Markdown source exceeds ${MAX_BYTES} bytes`);
    const buffer = Buffer.alloc(MAX_BYTES + 1);
    let length = 0;
    while (length < buffer.length) {
      const result = await file.read(
        buffer,
        length,
        buffer.length - length,
        null,
      );
      if (!result.bytesRead) break;
      length += result.bytesRead;
    }
    if (length > MAX_BYTES)
      throw new Error(`Markdown source exceeds ${MAX_BYTES} bytes`);
    bytes = buffer.subarray(0, length);
  } finally {
    await file.close();
  }
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  if (expectedSha256 !== undefined && expectedSha256 !== sha256)
    throw new Error("Markdown source changed: SHA-256 mismatch");
  const source = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  const deadline = Date.now() + 40000;
  const layout = await layoutMarkdown(source, {
    width,
    height,
    deadline,
    sourceDirectory: path.dirname(await realpath(absolutePath)),
  });
  const pages = [];
  for (const [index, page] of layout.pages.entries()) {
    if (Date.now() > deadline)
      throw new Error(
        "Markdown render exceeded 40 seconds; split the source document",
      );
    pages.push({
      imageBase64: (
        await rasterizePage(
          page,
          layout.geometry,
          index + 1,
          layout.pages.length,
          path.basename(absolutePath),
          deadline,
        )
      ).toString("base64"),
      width,
      height,
      sourceStartLine: page.sourceStartLine,
      sourceEndLine: page.sourceEndLine,
      pageIndex: index + 1,
      pageCount: layout.pages.length,
    });
  }
  return { source: { path: absolutePath, sha256 }, pages };
}

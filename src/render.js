import sharp from "sharp";
import { readFile } from "node:fs/promises";
const escape = (value) =>
  String(value).replace(
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
export async function renderPage({
  image_path,
  text,
  scene,
  width = 1404,
  height = 1872,
}) {
  if ([image_path, text, scene].filter((v) => v !== undefined).length !== 1)
    throw new Error("Provide exactly one of image_path, text, scene");
  if (image_path) {
    const bytes = await readFile(image_path);
    const image = sharp(bytes, { limitInputPixels: 16_000_000 });
    const metadata = await image.metadata();
    if (metadata.format !== "png" || metadata.pages > 1)
      throw new Error("image_path must be a single PNG");
    return {
      imageBase64: bytes.toString("base64"),
      width: metadata.width,
      height: metadata.height,
    };
  }
  if (
    !Number.isInteger(width) ||
    !Number.isInteger(height) ||
    width < 100 ||
    height < 100 ||
    width > 4096 ||
    height > 4096 ||
    width * height > 16_000_000
  )
    throw new Error("Invalid page dimensions");
  let content = "";
  if (text !== undefined) {
    const size = 36,
      spacing = 56,
      margin = Math.round(96 * width / 1404);
    const max = Math.floor((width - margin * 2) / (size * 0.62));
    if (max < 1) throw new Error("Page too narrow for text");
    const lines = [];
    for (const paragraph of text.split("\n")) {
      let line = "";
      for (const word of paragraph.split(/\s+/)) {
        if (word.length > max)
          throw new Error(
            "Text contains a word too wide; use a wider page or shorter text",
          );
        if ((line + " " + word).trim().length > max) {
          lines.push(line);
          line = word;
        } else line = (line + " " + word).trim();
      }
      lines.push(line);
    }
    if (lines.length * spacing > height - margin * 2)
      throw new Error("Text exceeds page; split into shorter reviews");
    content = lines
      .map(
        (line, i) =>
          `<text x="${margin}" y="${margin + size + i * spacing}" font-size="${size}" font-family="sans-serif">${escape(line)}</text>`,
      )
      .join("");
  } else {
    if (!Array.isArray(scene) || scene.length > 500)
      throw new Error("Scene must contain at most 500 objects");
    const number = (v, min = 0, max = 4096) => {
      if (!Number.isFinite(v) || v < min || v > max)
        throw new Error("Invalid scene coordinate");
      return v;
    };
    for (const item of scene) {
      if (item.type === "text")
        content += `<text x="${number(item.x)}" y="${number(item.y)}" font-size="${number(item.size ?? 32, 8, 120)}" font-family="sans-serif">${escape(String(item.text).slice(0, 2000))}</text>`;
      else if (item.type === "rect")
        content += `<rect x="${number(item.x)}" y="${number(item.y)}" width="${number(item.width)}" height="${number(item.height)}" fill="white" stroke="black" stroke-width="3"/>`;
      else if (item.type === "line" || item.type === "arrow")
        content += `<line x1="${number(item.x)}" y1="${number(item.y)}" x2="${number(item.x2)}" y2="${number(item.y2)}" stroke="black" stroke-width="3" ${item.type === "arrow" ? 'marker-end="url(#arrow)"' : ""}/>`;
      else throw new Error("Scene supports text, rect, line, arrow");
    }
  }
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="black"/></marker></defs><rect width="100%" height="100%" fill="white"/>${content}</svg>`;
  return {
    imageBase64: (await sharp(Buffer.from(svg)).png().toBuffer()).toString(
      "base64",
    ),
    width,
    height,
  };
}

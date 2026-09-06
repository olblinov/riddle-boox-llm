import { mkdir, readFile, writeFile, rename } from "node:fs/promises";
import { randomBytes, randomUUID } from "node:crypto";
import path from "node:path";
import sharp from "sharp";

export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
export const fail = (status, message) => {
  throw new HttpError(status, message);
};
export async function png(base64, width, height) {
  if (
    typeof base64 !== "string" ||
    base64.length > 16 * 1024 * 1024 ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(base64)
  )
    fail(400, "Invalid PNG base64");
  const bytes = Buffer.from(base64, "base64");
  if (bytes.subarray(0, 8).toString("hex") !== "89504e470d0a1a0a")
    fail(400, "PNG required");
  let metadata;
  try {
    metadata = await sharp(bytes, { limitInputPixels: 16_000_000 }).metadata();
    await sharp(bytes).stats();
  } catch {
    fail(400, "Invalid PNG");
  }
  if (
    metadata.width !== width ||
    metadata.height !== height ||
    metadata.pages > 1
  )
    fail(400, "PNG dimensions must match review");
  return bytes;
}
export class Store {
  constructor(directory) {
    this.directory = directory;
    this.state = { current: null, reviews: {} };
    this.queue = Promise.resolve();
  }
  async init() {
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    try {
      this.token = (
        await readFile(path.join(this.directory, "token"), "utf8")
      ).trim();
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      this.token = randomBytes(32).toString("hex");
      await writeFile(path.join(this.directory, "token"), this.token + "\n", {
        mode: 0o600,
        flag: "wx",
      });
    }
    if (this.token.length < 32)
      throw new Error("Pairing token must contain at least 32 characters");
    try {
      this.state = JSON.parse(
        await readFile(path.join(this.directory, "state.json"), "utf8"),
      );
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    return this;
  }
  async save() {
    const file = path.join(this.directory, "state.json");
    await writeFile(file + ".tmp", JSON.stringify(this.state), { mode: 0o600 });
    await rename(file + ".tmp", file);
  }
  mutate(action) {
    const run = this.queue.then(async () => {
      const before = structuredClone(this.state);
      try {
        const result = await action();
        await this.save();
        return result;
      } catch (error) {
        this.state = before;
        throw error;
      }
    });
    this.queue = run.catch(() => {});
    return run;
  }
  get(id) {
    return this.state.reviews[id] ?? fail(404, "Review not found");
  }
  metadata(review) {
    const { imageBase64, feedback, ...metadata } = review;
    return metadata;
  }
  current() {
    const review = this.state.reviews[this.state.current];
    return review ? this.metadata(review) : null;
  }
  async create(body) {
    const { title, imageBase64, width, height } = body;
    if (typeof title !== "string" || !title.trim() || title.length > 200)
      fail(400, "Title required, maximum 200 characters");
    if (
      !Number.isInteger(width) ||
      !Number.isInteger(height) ||
      width < 100 ||
      height < 100 ||
      width > 4096 ||
      height > 4096 ||
      width * height > 16_000_000
    )
      fail(400, "Invalid page dimensions");
    await png(imageBase64, width, height);
    return this.mutate(() => {
      if (this.current()?.status === "pending")
        fail(409, "A review is already pending");
      const id = randomUUID();
      const review = {
        id,
        title,
        width,
        height,
        status: "pending",
        createdAt: new Date().toISOString(),
        imageUrl: `/api/reviews/${id}/image`,
        imageBase64,
      };
      this.state.reviews[id] = review;
      this.state.current = id;
      return this.metadata(review);
    });
  }
  async submit(id, body) {
    const review = this.get(id);
    const { submissionId, compositeBase64, strokes, note = "" } = body;
    if (
      typeof submissionId !== "string" ||
      !submissionId.length ||
      submissionId.length > 128
    )
      fail(400, "Submission ID required");
    if (typeof note !== "string" || note.length > 10000)
      fail(400, "Invalid note");
    if (!Array.isArray(strokes) || strokes.length > 10000)
      fail(400, "Invalid strokes");
    let points = 0;
    for (const stroke of strokes) {
      if (
        !Number.isFinite(stroke.width) ||
        stroke.width <= 0 ||
        stroke.width > 100 ||
        !Array.isArray(stroke.points) ||
        !stroke.points.length
      )
        fail(400, "Invalid stroke");
      points += stroke.points.length;
      if (points > 200000) fail(400, "Too many stroke points");
      for (const point of stroke.points)
        if (
          !Number.isFinite(point.x) ||
          !Number.isFinite(point.y) ||
          point.x < 0 ||
          point.y < 0 ||
          point.x > review.width ||
          point.y > review.height ||
          !Number.isFinite(point.pressure) ||
          point.pressure < 0 ||
          point.pressure > 1
        )
          fail(400, "Invalid stroke point");
    }
    await png(compositeBase64, review.width, review.height);
    return this.mutate(() => {
      const current = this.get(id);
      if (current.feedback?.submissionId === submissionId) {
        if (
          JSON.stringify(current.feedback) !==
          JSON.stringify({ submissionId, compositeBase64, strokes, note })
        )
          fail(409, "Submission ID already used with different feedback");
        return { ok: true, reviewId: id, status: "submitted" };
      }
      if (this.state.current !== id || current.status !== "pending")
        fail(409, "Review is no longer pending");
      current.feedback = { submissionId, compositeBase64, strokes, note };
      current.status = "submitted";
      return { ok: true, reviewId: id, status: "submitted" };
    });
  }
  feedback(id) {
    const review = this.get(id);
    return review.feedback
      ? { status: "submitted", reviewId: id, ...review.feedback }
      : { status: review.status };
  }
  cancel(id) {
    return this.mutate(() => {
      const review = this.get(id);
      if (review.status === "submitted")
        fail(409, "Submitted review cannot be cancelled");
      review.status = "cancelled";
      return { ok: true, status: "cancelled" };
    });
  }
}

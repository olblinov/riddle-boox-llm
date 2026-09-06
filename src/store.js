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
    this.retentionDays = 90;
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
  async history(now = Date.now()) {
    return this.mutate(() => {
      const cutoff = now - this.retentionDays * 86400000;
      for (const [id, review] of Object.entries(this.state.reviews)) {
        if (review.status !== "submitted") continue;
        // Older releases did not record submission time. Start their retention now.
        review.submittedAt ??= new Date(now).toISOString();
        if (Date.parse(review.submittedAt) < cutoff) {
          delete this.state.reviews[id];
          if (this.state.current === id) this.state.current = null;
        }
      }
      return {
        retentionDays: this.retentionDays,
        reviews: Object.values(this.state.reviews)
          .filter((review) => review.status === "submitted")
          .sort((a, b) => b.submittedAt.localeCompare(a.submittedAt))
          .map((review) => ({
            id: review.id, title: review.title, submittedAt: review.submittedAt,
            pageCount: review.pages?.length ?? 1,
          })),
      };
    });
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
    const { imageBase64, feedback, pages, ...metadata } = review;
    if (pages) metadata.pages = pages.map(({ imageBase64, ...page }) => page);
    return metadata;
  }
  feedbackInbox() {
    return { reviews: Object.values(this.state.reviews)
      .filter(r => r.status === "submitted" && !r.feedbackReadAt)
      .map(r => ({id:r.id,title:r.title,submissionId:r.feedback.submissionId,
        submittedAt:r.submittedAt,pageCount:r.pages?.length ?? 1,source:r.source,origin:r.origin})) };
  }
  acknowledgeFeedback(id, submissionId) {
    return this.mutate(() => {
      const r = this.get(id);
      if (r.status !== "submitted" || r.feedback?.submissionId !== submissionId)
        fail(409, "Submission does not match review");
      r.feedbackReadAt ??= new Date().toISOString();
      return {ok:true,reviewId:id,submissionId,feedbackReadAt:r.feedbackReadAt};
    });
  }
  pendingQueue() {
    return Object.values(this.state.reviews).filter(r => r.status === "pending");
  }
  advanceQueue() {
    if (this.state.reviews[this.state.current]?.status === "pending") return;
    const next = this.pendingQueue()[0];
    if (next) this.state.current = next.id;
  }
  activate(id, expectedCurrentReviewId) {
    return this.mutate(() => {
      if (expectedCurrentReviewId !== null &&
          (typeof expectedCurrentReviewId !== "string" || !/^[a-f0-9-]{36}$/.test(expectedCurrentReviewId)))
        fail(400, "Expected current review ID required");
      const target = this.get(id);
      if (target.status !== "pending") fail(409, "Only pending documents can be opened");
      const active = this.current()?.status === "pending" ? this.state.current : null;
      // Retry after a lost response is harmless if the target is already active.
      if (active !== id && active !== expectedCurrentReviewId)
        fail(409, "Active review changed; refresh queue");
      this.state.current = id;
      return {ok:true, review:this.metadata(target)};
    });
  }
  reviewQueue() {
    return { activeReviewId: this.current()?.status === "pending" ? this.state.current : null,
      reviews: this.pendingQueue().map((r, i) => ({...this.metadata(r), queuePosition: i + 1})) };
  }
  current() {
    const review = this.state.reviews[this.state.current];
    return review ? this.metadata(review) : null;
  }
  validateSource(source) {
    if (source !== undefined) {
      if (
        !source ||
        source.kind !== "markdown" ||
        typeof source.path !== "string" ||
        !path.isAbsolute(source.path) ||
        source.path.length > 4096 ||
        typeof source.snapshotPath !== "string" ||
        !path.isAbsolute(source.snapshotPath) ||
        source.snapshotPath.length > 4096 ||
        !/^[a-f0-9]{64}$/.test(source.sha256) ||
        !Number.isInteger(source.pageIndex) ||
        !Number.isInteger(source.pageCount) ||
        source.pageIndex < 1 ||
        source.pageIndex > source.pageCount ||
        source.pageCount > 200 ||
        !Number.isInteger(source.startLine) ||
        !Number.isInteger(source.endLine) ||
        source.startLine < 1 ||
        source.endLine < source.startLine
      ) {
        fail(400, "Invalid Markdown source metadata");
      }
    }
  }
  async validatePage(page) {
    if (!page || typeof page !== "object" || Array.isArray(page))
      fail(400, "Invalid document page");
    const { imageBase64, width, height, source } = page;
    this.validateSource(source);
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
    return { imageBase64, width, height, ...(source ? { source } : {}) };
  }
  async create(body) {
    const { title, pages, origin } = body;
    if (origin !== undefined && (!origin || typeof origin !== "object" ||
      typeof origin.threadId !== "string" || !/^[a-f0-9-]{36}$/.test(origin.threadId) ||
      typeof origin.workspace !== "string" || !path.isAbsolute(origin.workspace) || origin.workspace.length > 4096))
      fail(400, "Invalid review origin");
    if (typeof title !== "string" || !title.trim() || title.length > 200)
      fail(400, "Title required, maximum 200 characters");
    let validated;
    if (pages !== undefined) {
      if (!Array.isArray(pages) || pages.length < 1 || pages.length > 200)
        fail(400, "Document requires 1–200 pages");
      if (body.imageBase64 !== undefined)
        fail(400, "Provide pages or a single image, not both");
      validated = [];
      for (const page of pages) validated.push(await this.validatePage(page));
    } else validated = [await this.validatePage(body)];
    return this.mutate(() => {
      if (this.pendingQueue().length >= 100)
        fail(409, "Review queue is full (100 documents)");
      const id = randomUUID();
      const first = validated[0];
      const review = {
        id,
        title,
        width: first.width,
        height: first.height,
        status: "pending",
        createdAt: new Date().toISOString(),
        ...(origin ? {origin: {threadId:origin.threadId,workspace:origin.workspace}} : {}),
        imageUrl: `/api/reviews/${id}/image`,
        ...(first.source ? { source: first.source } : {}),
        ...(pages !== undefined
          ? {
              pageCount: validated.length,
              pages: validated.map((page, index) => ({
                ...page,
                pageIndex: index + 1,
                imageUrl: `/api/reviews/${id}/image?page=${index + 1}`,
              })),
            }
          : { imageBase64: first.imageBase64 }),
      };
      this.state.reviews[id] = review;
      this.advanceQueue();
      return { ...this.metadata(review), queuePosition: this.pendingQueue().findIndex(r => r.id === id) + 1 };
    });
  }
  image(id, pageIndex = 1) {
    const review = this.get(id);
    if (
      !Number.isInteger(pageIndex) ||
      pageIndex < 1 ||
      pageIndex > (review.pages?.length ?? 1)
    )
      fail(400, "Invalid page index");
    return review.pages
      ? review.pages[pageIndex - 1].imageBase64
      : review.imageBase64;
  }
  async validateFeedbackPage(page, review) {
    if (!page || typeof page !== "object" || Array.isArray(page))
      fail(400, "Invalid feedback page");
    const { compositeBase64, strokes, note = "", canvasBounds } = page;
    const bounds = canvasBounds ?? {x: 0, y: 0, width: review.width, height: review.height};
    if (canvasBounds !== undefined && (!canvasBounds || typeof canvasBounds !== "object" || Array.isArray(canvasBounds)))
      fail(400, "Invalid canvas bounds");
    const {x, y, width, height} = bounds;
    if (![x,y,width,height].every(Number.isInteger) || x > 0 || y > 0 || x < -512 || y < -512 ||
        width < review.width - x || height < review.height - y || width > 4096 || height > 4096 ||
        width * height > 16000000 || x + width > review.width + 512 || y + height > review.height + 512)
      fail(400, "Invalid canvas bounds");
    if (typeof note !== "string" || note.length > 10000)
      fail(400, "Invalid note");
    if (!Array.isArray(strokes) || strokes.length > 10000)
      fail(400, "Invalid strokes");
    let points = 0;
    for (const stroke of strokes) {
      if (
        !stroke ||
        !Number.isFinite(stroke.width) ||
        stroke.width <= 0 ||
        stroke.width > 100 ||
        !Array.isArray(stroke.points) ||
        !stroke.points.length
      )
        fail(400, "Invalid stroke");
      points += stroke.points.length;
      if (points > 200000) fail(400, "Too many stroke points");
      for (const point of stroke.points) {
        if (
          !point ||
          !Number.isFinite(point.x) ||
          !Number.isFinite(point.y) ||
          point.x < x ||
          point.y < y ||
          point.x > x + width ||
          point.y > y + height ||
          !Number.isFinite(point.pressure) ||
          point.pressure < 0 ||
          point.pressure > 1
        )
          fail(400, "Invalid stroke point");
      }
    }
    await png(compositeBase64, width, height);
    return { compositeBase64, strokes, note, ...(canvasBounds ? {canvasBounds: {x,y,width,height}} : {}) };
  }
  async submit(id, body) {
    const review = this.get(id);
    const { submissionId, note = "" } = body;
    if (
      typeof submissionId !== "string" ||
      !submissionId.length ||
      submissionId.length > 128
    )
      fail(400, "Submission ID required");
    if (typeof note !== "string" || note.length > 10000)
      fail(400, "Invalid note");
    let feedback;
    if (review.pages) {
      if (
        !Array.isArray(body.pages) ||
        body.pages.length !== review.pages.length
      )
        fail(400, "Submit exactly all document pages in order");
      if (body.compositeBase64 !== undefined || body.strokes !== undefined)
        fail(400, "Document feedback requires pages");
      const pages = [];
      for (const [index, page] of body.pages.entries()) {
        if (page?.pageIndex !== index + 1)
          fail(400, "Submit exactly all document pages in order");
        pages.push({
          pageIndex: index + 1,
          ...(await this.validateFeedbackPage(page, review.pages[index])),
        });
      }
      feedback = { submissionId, pages, note };
    } else {
      if (body.pages !== undefined)
        fail(400, "Single-page review requires legacy feedback format");
      feedback = {
        submissionId,
        ...(await this.validateFeedbackPage(body, review)),
      };
    }
    return this.mutate(() => {
      const current = this.get(id);
      if (current.feedback?.submissionId === submissionId) {
        if (JSON.stringify(current.feedback) !== JSON.stringify(feedback))
          fail(409, "Submission ID already used with different feedback");
        return { ok: true, reviewId: id, status: "submitted", submittedAt: current.submittedAt ??= new Date().toISOString() };
      }
      if (this.state.current !== id || current.status !== "pending")
        fail(409, "Review is no longer pending");
      current.feedback = feedback;
      current.status = "submitted";
      current.submittedAt = new Date().toISOString();
      this.advanceQueue();
      return { ok: true, reviewId: id, status: "submitted", submittedAt: current.submittedAt ??= new Date().toISOString() };
    });
  }
  feedback(id) {
    const review = this.get(id);
    if (!review.feedback) return { status: review.status };
    const result = {
      status: "submitted",
      reviewId: id,
      ...(review.source ? { source: review.source } : {}),
      ...review.feedback,
    };
    if (review.pages)
      result.pages = review.feedback.pages.map((page, index) => ({
        ...page,
        ...(review.pages[index].source
          ? { source: review.pages[index].source }
          : {}),
      }));
    return result;
  }
  cancel(id) {
    return this.mutate(() => {
      const review = this.get(id);
      if (review.status === "submitted")
        fail(409, "Submitted review cannot be cancelled");
      review.status = "cancelled";
      this.advanceQueue();
      return { ok: true, status: "cancelled" };
    });
  }
}

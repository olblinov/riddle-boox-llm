"use strict";
const $ = (id) => document.getElementById(id);
const ui = Object.fromEntries(
  [
    "pair",
    "token",
    "status",
    "title",
    "undo",
    "clear",
    "mouse",
    "zoom",
    "send",
    "note",
    "notice",
    "notice-text",
    "next",
    "viewport",
    "sheet",
    "page",
    "ink",
    "empty",
    "previous-page",
    "next-page",
    "page-number",
  ].map((id) => [id, $(id)]),
);
const context = ui.ink.getContext("2d");
let token = "",
  review = null,
  strokes = [],
  active = null,
  image = null,
  sending = false,
  connected = false,
  polling = false,
  latest = null,
  submissionId = null,
  submitted = false,
  frozenBody = null,
  pageStates = [],
  pagePosition = 0,
  loadingPage = false;
const imageCache = new Map();
const DRAFT = "boox-review-draft-v1";
const status = (text) => {
  ui.status.textContent = text;
};
const uuid = () =>
  globalThis.crypto?.randomUUID?.() ||
  `${Date.now()}-${Math.random().toString(36).slice(2)}`;
const storage = {
  get(key) {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, value);
      return true;
    } catch {
      status(
        "Device storage is full or unavailable. Keep this page open until comments are sent.",
      );
      return false;
    }
  },
};
function pages() {
  return review?.pages?.length
    ? review.pages
    : review
      ? [{ ...review, pageIndex: 1 }]
      : [];
}
function rememberPage() {
  if (pageStates[pagePosition])
    Object.assign(pageStates[pagePosition], { strokes, note: ui.note.value });
}
function allVisited() {
  return pageStates.length > 0 && pageStates.every((page) => page.visited);
}
function hasDraft() {
  rememberPage();
  return (
    !!frozenBody ||
    !!active ||
    pageStates.some((page) => page.strokes.length || page.note)
  );
}
function save() {
  rememberPage();
  if (!review) return;
  return storage.set(
    DRAFT,
    JSON.stringify({
      review,
      strokes,
      note: ui.note.value,
      submissionId,
      submitted,
      frozenBody,
      pageStates,
      pagePosition,
    }),
  );
}
function controls() {
  const editable =
    !!review && !submitted && !sending && !frozenBody && !loadingPage;
  ui.undo.disabled = !editable || !strokes.length;
  ui.clear.disabled = !editable || !strokes.length;
  ui.send.disabled =
    !review ||
    submitted ||
    sending ||
    loadingPage ||
    (!frozenBody && !allVisited());
  ui["previous-page"].disabled =
    !review || pagePosition === 0 || loadingPage || sending;
  ui["next-page"].disabled =
    !review || pagePosition >= pages().length - 1 || loadingPage || sending;
  ui["page-number"].textContent = review
    ? `Page ${pagePosition + 1} of ${pages().length} · ${pageStates.filter((page) => page.visited).length} visited`
    : "";
  ui.next.disabled = !!frozenBody && !submitted;
  ui.note.disabled = !editable;
  ui.send.textContent = sending
    ? "Sending…"
    : submitted
      ? "Comments sent"
      : frozenBody
        ? "Retry same comments"
        : review?.pages?.length
          ? "Send all pages"
          : "Send comments";
}
async function api(path, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  let response;
  try {
    response = await fetch(path, {
      signal: controller.signal,
      ...options,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...options.headers,
      },
    });
  } finally {
    clearTimeout(timeout);
  }
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      message = (await response.json()).error || message;
    } catch {}
    throw new Error(message);
  }
  return response;
}
function drawStroke(stroke, target = context) {
  const points = stroke.points;
  if (!points.length) return;
  target.strokeStyle = "#000";
  target.fillStyle = "#000";
  target.lineCap = "round";
  target.lineJoin = "round";
  if (points.length === 1) {
    target.beginPath();
    target.arc(points[0].x, points[0].y, stroke.width / 2, 0, Math.PI * 2);
    target.fill();
    return;
  }
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1],
      b = points[i];
    target.lineWidth =
      stroke.width * (0.6 + 0.8 * ((a.pressure + b.pressure) / 2));
    target.beginPath();
    target.moveTo(a.x, a.y);
    target.lineTo(b.x, b.y);
    target.stroke();
  }
}
function redraw() {
  context.clearRect(0, 0, ui.ink.width, ui.ink.height);
  strokes.forEach((stroke) => drawStroke(stroke));
  if (active) drawStroke(active);
}
function fit() {
  if (!review || !image) return;
  const page = pages()[pagePosition];
  const width =
    Math.max(100, ui.viewport.clientWidth) * (Number(ui.zoom.value) || 1);
  ui.sheet.style.width = `${width}px`;
  // Match physical display pixels while keeping all ink in source-image coordinates.
  const desiredRatio = Math.max(
    1,
    (width * (window.devicePixelRatio || 1)) / page.width,
  );
  const ratio = Math.min(
    desiredRatio,
    Math.sqrt(8_000_000 / (page.width * page.height)),
  );
  ui.ink.width = Math.ceil(page.width * ratio);
  ui.ink.height = Math.ceil(page.height * ratio);
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  redraw();
}
function trimImageCache() {
  for (const [key, cached] of imageCache) {
    if (imageCache.size <= 3) break;
    if (cached === image) continue;
    imageCache.delete(key);
    URL.revokeObjectURL(cached.src);
  }
}
async function loadImage(page) {
  if (imageCache.has(page.imageUrl)) {
    const cached = imageCache.get(page.imageUrl);
    imageCache.delete(page.imageUrl);
    imageCache.set(page.imageUrl, cached);
    return cached;
  }
  const response = await api(page.imageUrl);
  const objectUrl = URL.createObjectURL(await response.blob());
  const loaded = new Image();
  try {
    await new Promise((resolve, reject) => {
      loaded.onload = resolve;
      loaded.onerror = () => reject(new Error("Could not decode review image"));
      loaded.src = objectUrl;
    });
    if (
      loaded.naturalWidth !== page.width ||
      loaded.naturalHeight !== page.height
    )
      throw new Error("Review image dimensions do not match");
    imageCache.set(page.imageUrl, loaded);
    trimImageCache();
    return loaded;
  } catch (error) {
    URL.revokeObjectURL(objectUrl);
    throw error;
  }
}
async function showPage(position) {
  if (loadingPage || sending || position < 0 || position >= pages().length)
    return;
  if (active) finish({ pointerId: active.pointerId });
  rememberPage();
  loadingPage = true;
  controls();
  try {
    const loaded = await loadImage(pages()[position]);
    pagePosition = position;
    image = loaded;
    trimImageCache();
    strokes = pageStates[position].strokes;
    ui.note.value = pageStates[position].note;
    pageStates[position].visited = true;
    ui.page.src = loaded.src;
    ui.sheet.style.display = "block";
    ui.empty.hidden = true;
    ui.viewport.scrollTop = 0;
    ui.viewport.scrollLeft = 0;
    fit();
    save();
  } finally {
    loadingPage = false;
    controls();
  }
}
async function openReview(next, restore = false) {
  if (loadingPage || sending) return;
  // Fetch before replacing state so a failed load cannot discard the old draft.
  const first = next.pages?.length
    ? next.pages[restore ? pagePosition : 0]
    : next;
  loadingPage = true;
  controls();
  try {
    await loadImage(first);
  } finally {
    loadingPage = false;
    controls();
  }
  review = next;
  if (!restore) {
    frozenBody = null;
    strokes = [];
    ui.note.value = "";
    submissionId = uuid();
    submitted = next.status === "submitted";
    pagePosition = 0;
    pageStates = pages().map(() => ({ strokes: [], note: "", visited: false }));
  }
  ui.title.textContent = next.title;
  ui.notice.hidden = true;
  await showPage(pagePosition);
  status(
    submitted
      ? "Comments sent. Waiting for the next document."
      : frozenBody
        ? "Submission outcome unconfirmed. Editing locked. Retry same comments to confirm delivery."
        : "Read every page, add comments, then send the whole document. Pen draws; finger scrolls.",
  );
}
function changed() {
  if (frozenBody) return;
  submissionId = uuid();
  save();
  controls();
}
function point(event) {
  const rect = ui.ink.getBoundingClientRect();
  return {
    x: Math.max(
      0,
      Math.min(
        pages()[pagePosition].width,
        ((event.clientX - rect.left) * pages()[pagePosition].width) /
          rect.width,
      ),
    ),
    y: Math.max(
      0,
      Math.min(
        pages()[pagePosition].height,
        ((event.clientY - rect.top) * pages()[pagePosition].height) /
          rect.height,
      ),
    ),
    pressure: event.pressure > 0 ? event.pressure : 0.5,
  };
}
ui.ink.addEventListener("pointerdown", (event) => {
  if (
    !review ||
    submitted ||
    sending ||
    frozenBody ||
    active ||
    loadingPage ||
    !(
      event.pointerType === "pen" ||
      (event.pointerType === "mouse" && ui.mouse.checked)
    )
  )
    return;
  event.preventDefault();
  ui.ink.setPointerCapture(event.pointerId);
  active = {
    pointerId: event.pointerId,
    width: Math.max(2, pages()[pagePosition].width / 400),
    points: [point(event)],
  };
  redraw();
});
ui.ink.addEventListener("pointermove", (event) => {
  if (!active || active.pointerId !== event.pointerId) return;
  event.preventDefault();
  const events = event.getCoalescedEvents?.();
  for (const item of events?.length ? events : [event])
    active.points.push(point(item));
  redraw();
});
function finish(event) {
  if (!active || active.pointerId !== event.pointerId) return;
  const { pointerId, ...stroke } = active;
  strokes.push(stroke);
  active = null;
  redraw();
  changed();
}
ui.ink.addEventListener("pointerup", finish);
ui.ink.addEventListener("pointercancel", finish);
ui.ink.addEventListener("lostpointercapture", finish);
ui.undo.onclick = () => {
  if (frozenBody) return;
  strokes.pop();
  redraw();
  changed();
};
ui.clear.onclick = () => {
  if (frozenBody) return;
  if (!confirm("Clear all pen comments on this page?")) return;
  strokes = [];
  redraw();
  changed();
};
ui.note.addEventListener("input", changed);
ui["previous-page"].onclick = () =>
  showPage(pagePosition - 1).catch((error) => status(error.message));
ui["next-page"].onclick = () =>
  showPage(pagePosition + 1).catch((error) => status(error.message));
ui.zoom.onchange = fit;
window.addEventListener("resize", fit);
ui.send.onclick = async () => {
  if (
    !review ||
    sending ||
    submitted ||
    loadingPage ||
    (!frozenBody && !allVisited())
  )
    return;
  if (active) finish({ pointerId: active.pointerId });
  sending = true;
  controls();
  save();
  try {
    if (!frozenBody) {
      rememberPage();
      const rendered = [];
      for (let index = 0; index < pages().length; index++) {
        const page = pages()[index];
        const base = await loadImage(page);
        const composite = document.createElement("canvas");
        composite.width = page.width;
        composite.height = page.height;
        const c = composite.getContext("2d");
        c.fillStyle = "#fff";
        c.fillRect(0, 0, page.width, page.height);
        c.drawImage(base, 0, 0);
        pageStates[index].strokes.forEach((stroke) => drawStroke(stroke, c));
        rendered.push({
          pageIndex: page.pageIndex,
          compositeBase64: composite.toDataURL("image/png").split(",")[1],
          strokes: pageStates[index].strokes,
          note: pageStates[index].note,
        });
      }
      const payload = review.pages?.length
        ? { submissionId, pages: rendered }
        : {
            submissionId,
            compositeBase64: rendered[0].compositeBase64,
            strokes: rendered[0].strokes,
            note: rendered[0].note,
          };
      const body = JSON.stringify(payload);
      if (new TextEncoder().encode(body).byteLength > 24 * 1024 * 1024)
        throw new Error(
          "Document feedback exceeds 24 MiB. Ask Codex to split this document into smaller reviews; comments are preserved",
        );
      frozenBody = body;
      if (!save()) {
        frozenBody = null;
        throw new Error(
          "Cannot persist submission. Free browser storage before sending",
        );
      }
    }
    controls();
    await api(`/api/reviews/${encodeURIComponent(review.id)}/feedback`, {
      method: "POST",
      body: frozenBody,
    });
    submitted = true;
    save();
    status("Comments sent. Waiting for Codex.");
  } catch (error) {
    status(
      `Send unconfirmed: ${error.message}. ${frozenBody ? "Editing locked. Retry same comments to confirm delivery." : "Nothing sent. Draft remains open."}`,
    );
  } finally {
    sending = false;
    controls();
  }
};
async function poll() {
  if (!connected || polling || sending || loadingPage) return;
  polling = true;
  try {
    const result = await (await api("/api/reviews/current")).json();
    latest = result.review;
    if (!latest) {
      if (!review) status("Connected. Waiting for Codex to send a page.");
      return;
    }
    if (latest.id === review?.id) return;
    if (review && !submitted && hasDraft()) {
      ui.notice.hidden = false;
      ui["notice-text"].textContent =
        "Another page is available. Your unsent comments remain here.";
      return;
    }
    await openReview(latest);
  } catch (error) {
    status(`Connection problem: ${error.message}. Existing ink is preserved.`);
  } finally {
    polling = false;
  }
}
ui.next.onclick = async () => {
  if (!latest || (frozenBody && !submitted)) return;
  if (
    !submitted &&
    hasDraft() &&
    !confirm("Open the new page and discard unsent comments on this page?")
  )
    return;
  try {
    await openReview(latest);
  } catch (error) {
    status(error.message);
  }
};
ui.pair.onsubmit = async (event) => {
  event.preventDefault();
  token = ui.token.value.trim();
  connected = false;
  status("Connecting…");
  try {
    await api("/api/health");
    connected = true;
    try {
      sessionStorage.setItem("boox-token", token);
    } catch {}
    const draft = storage.get(DRAFT);
    if (!review && draft) {
      try {
        const saved = JSON.parse(draft);
        review = saved.review;
        strokes = saved.strokes || [];
        ui.note.value = saved.note || "";
        submissionId = saved.submissionId || uuid();
        submitted = !!saved.submitted;
        frozenBody = saved.frozenBody || null;
        pageStates = saved.pageStates || [
          { strokes, note: ui.note.value, visited: true },
        ];
        pagePosition = Math.max(
          0,
          Math.min(saved.pagePosition || 0, pageStates.length - 1),
        );
        await openReview(review, true);
      } catch (error) {
        status(
          `Saved draft could not be reopened: ${error.message}. Draft remains in storage.`,
        );
      }
    }
    await poll();
  } catch (error) {
    status(`Connection failed: ${error.message}`);
  }
};
try {
  ui.token.value = sessionStorage.getItem("boox-token") || "";
} catch {}
controls();
if (ui.token.value) ui.pair.requestSubmit();
setInterval(poll, 2000);

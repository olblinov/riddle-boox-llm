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
  frozenBody = null;
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
function save() {
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
    }),
  );
}
function controls() {
  const editable = !!review && !submitted && !sending && !frozenBody;
  ui.undo.disabled = !editable || !strokes.length;
  ui.clear.disabled = !editable || !strokes.length;
  ui.send.disabled = !review || submitted || sending;
  ui.next.disabled = !!frozenBody && !submitted;
  ui.note.disabled = !editable;
  ui.send.textContent = sending
    ? "Sending…"
    : submitted
      ? "Comments sent"
      : frozenBody
        ? "Retry same comments"
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
function drawStroke(stroke) {
  const points = stroke.points;
  if (!points.length) return;
  context.strokeStyle = "#000";
  context.fillStyle = "#000";
  context.lineCap = "round";
  context.lineJoin = "round";
  if (points.length === 1) {
    context.beginPath();
    context.arc(points[0].x, points[0].y, stroke.width / 2, 0, Math.PI * 2);
    context.fill();
    return;
  }
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1],
      b = points[i];
    context.lineWidth =
      stroke.width * (0.6 + 0.8 * ((a.pressure + b.pressure) / 2));
    context.beginPath();
    context.moveTo(a.x, a.y);
    context.lineTo(b.x, b.y);
    context.stroke();
  }
}
function redraw() {
  context.clearRect(0, 0, ui.ink.width, ui.ink.height);
  strokes.forEach(drawStroke);
  if (active) drawStroke(active);
}
function fit() {
  if (!review) return;
  const padding = innerWidth < 650 ? 16 : 32;
  ui.sheet.style.width = `${Math.max(100, ui.viewport.clientWidth - padding) * Number(ui.zoom.value)}px`;
}
async function openReview(next, restore = false) {
  const old = review;
  const response = await api(next.imageUrl);
  const blob = await response.blob();
  const objectUrl = URL.createObjectURL(blob);
  const loaded = new Image();
  try {
    await new Promise((resolve, reject) => {
      loaded.onload = resolve;
      loaded.onerror = () => reject(new Error("Could not decode review image"));
      loaded.src = objectUrl;
    });
    if (
      loaded.naturalWidth !== next.width ||
      loaded.naturalHeight !== next.height
    )
      throw new Error("Review image dimensions do not match");
    review = next;
    image = loaded;
    ui.page.src = objectUrl;
    if (!restore || old?.id !== next.id) {
      frozenBody = null;
      strokes = [];
      ui.note.value = "";
      submissionId = uuid();
      submitted = next.status === "submitted";
    }
    ui.ink.width = next.width;
    ui.ink.height = next.height;
    ui.title.textContent = next.title;
    ui.sheet.style.display = "block";
    ui.empty.hidden = true;
    ui.notice.hidden = true;
    fit();
    redraw();
    controls();
    save();
    status(
      submitted
        ? "Comments sent. Waiting for the next page."
        : frozenBody
          ? "Submission outcome unconfirmed. Editing locked. Retry same comments to confirm delivery."
          : "Use pen to comment. Use finger to scroll. Tap Send comments when done.",
    );
  } catch (error) {
    URL.revokeObjectURL(objectUrl);
    throw error;
  }
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
        review.width,
        ((event.clientX - rect.left) * review.width) / rect.width,
      ),
    ),
    y: Math.max(
      0,
      Math.min(
        review.height,
        ((event.clientY - rect.top) * review.height) / rect.height,
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
    width: Math.max(2, review.width / 400),
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
ui.zoom.onchange = fit;
window.addEventListener("resize", fit);
ui.send.onclick = async () => {
  if (!review || sending || submitted) return;
  if (active) finish({ pointerId: active.pointerId });
  sending = true;
  controls();
  save();
  try {
    if (!frozenBody) {
      const composite = document.createElement("canvas");
      composite.width = review.width;
      composite.height = review.height;
      const c = composite.getContext("2d");
      c.fillStyle = "#fff";
      c.fillRect(0, 0, composite.width, composite.height);
      c.drawImage(image, 0, 0);
      c.drawImage(ui.ink, 0, 0);
      const payload = {
        submissionId,
        compositeBase64: composite.toDataURL("image/png").split(",")[1],
        strokes,
        note: ui.note.value,
      };
      frozenBody = JSON.stringify(payload);
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
  if (!connected || polling || sending) return;
  polling = true;
  try {
    const result = await (await api("/api/reviews/current")).json();
    latest = result.review;
    if (!latest) {
      if (!review) status("Connected. Waiting for Codex to send a page.");
      return;
    }
    if (latest.id === review?.id) return;
    if (
      review &&
      !submitted &&
      (frozenBody || strokes.length || ui.note.value || active)
    ) {
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
    (strokes.length || ui.note.value) &&
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

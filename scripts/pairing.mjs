#!/usr/bin/env node
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { networkInterfaces } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const token = (
  await readFile(path.join(root, ".runtime/token"), "utf8")
).trim();
const addresses = Object.entries(networkInterfaces())
  .filter(([name]) => /^(en|eth|wlan)/.test(name))
  .flatMap(([, entries]) =>
    entries
      .filter((x) => x.family === "IPv4" && !x.internal)
      .map((x) => x.address),
  );
const escape = (s) =>
  s.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll('"', "&quot;");
const urls = addresses.map((address) => `http://${address}:4317`);
const html = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Pair BOOX review</title><style>body{font:20px system-ui;max-width:850px;margin:50px auto;padding:20px;line-height:1.6}input{font:18px monospace;width:100%;padding:12px;box-sizing:border-box}code{background:#eee;padding:4px}h1{font-size:30px}</style><h1>Pair your BOOX</h1><p>Install BOOX Review APK, open Pair, then enter:</p><p>Mac bridge address</p>${urls.map((url) => `<input readonly value="${escape(url)}" onclick="this.select()">`).join("") || "<p>No Wi-Fi/Ethernet IPv4 address found. Connect Mac to same network as tablet and rerun pairing script.</p>"}<p>Pairing token</p><input readonly value="${escape(token)}" onclick="this.select()"><p>Keep token private. This local file contains it and is excluded from git. HTTP traffic is unencrypted; use trusted Wi-Fi or USB.</p><p>USB alternative: run <code>adb reverse tcp:4317 tcp:4317</code>, then use <code>http://127.0.0.1:4317</code> in Android app.</p><p>In Codex: <code>Use $boox-review to send output to my BOOX and wait for my pen comments.</code></p></html>`;
await mkdir(path.join(root, "outputs"), { recursive: true });
await writeFile(path.join(root, "outputs/pairing.html"), html, { mode: 0o600 });
console.log(
  "Saved outputs/pairing.html with local connection details. Do not commit or share this file.",
);

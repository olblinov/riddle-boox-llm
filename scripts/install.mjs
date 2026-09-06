#!/usr/bin/env node
import { mkdir, readFile, writeFile, symlink, lstat } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { homedir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const label = "com.olblinov.boox-review";
const runtime = path.join(root, ".runtime");
const xml = (s) =>
  s
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
await mkdir(runtime, { recursive: true, mode: 0o700 });
const skillParent = path.join(homedir(), ".agents", "skills");
await mkdir(skillParent, { recursive: true });
const destination = path.join(skillParent, "boox-review");
try {
  await lstat(destination);
  if (
    (await readFile(path.join(destination, "SKILL.md"), "utf8")) !==
    (await readFile(path.join(root, "skills/boox-review/SKILL.md"), "utf8"))
  )
    throw Error("Existing different boox-review skill; refusing to overwrite.");
} catch (error) {
  if (error.code === "ENOENT")
    await symlink(path.join(root, "skills/boox-review"), destination);
  else throw error;
}
execFileSync(
  "codex",
  [
    "mcp",
    "add",
    "boox",
    "--env",
    `BOOX_DATA_DIR=${runtime}`,
    "--env",
    "BOOX_BRIDGE_URL=http://127.0.0.1:4317",
    "--",
    process.execPath,
    path.join(root, "src/mcp.js"),
  ],
  { stdio: "inherit" },
);
if (process.platform === "darwin") {
  const agents = path.join(homedir(), "Library/LaunchAgents");
  await mkdir(agents, { recursive: true });
  const plist = path.join(agents, `${label}.plist`);
  const content = `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict><key>Label</key><string>${label}</string><key>ProgramArguments</key><array><string>${xml(process.execPath)}</string><string>${xml(path.join(root, "src/bridge.js"))}</string></array><key>WorkingDirectory</key><string>${xml(root)}</string><key>EnvironmentVariables</key><dict><key>BOOX_HOST</key><string>0.0.0.0</string><key>BOOX_DATA_DIR</key><string>${xml(runtime)}</string></dict><key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>StandardOutPath</key><string>${xml(path.join(runtime, "bridge.log"))}</string><key>StandardErrorPath</key><string>${xml(path.join(runtime, "bridge.log"))}</string></dict></plist>\n`;
  await writeFile(plist, content, { mode: 0o600 });
  try {
    execFileSync("launchctl", ["bootout", `gui/${process.getuid()}/${label}`], {
      stdio: "ignore",
    });
  } catch {}
  execFileSync("launchctl", ["bootstrap", `gui/${process.getuid()}`, plist], {
    stdio: "inherit",
  });
  console.log(
    "Installed local bridge login service and BOOX MCP. Reload Codex tools before first use.",
  );
} else
  console.log(
    "MCP and skill installed. Start bridge separately with BOOX_HOST=0.0.0.0 npm start.",
  );

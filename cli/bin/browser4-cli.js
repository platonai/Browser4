#!/usr/bin/env node

import fs from "fs";
import path from "path";
import { spawn } from "child_process";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const scriptDir = path.dirname(__filename);
const exeName = process.platform === "win32" ? "browser4-cli.exe" : "browser4-cli";
const exePath = path.join(scriptDir, "target", "release", exeName);

if (!fs.existsSync(exePath)) {
  const scriptName = path.basename(__filename);
  console.error(`[${scriptName}] ERROR: executable not found: "${exePath}"`);
  console.error(`[${scriptName}] Run: cargo build --release (in sdks/browser4-cli)`);
  process.exit(1);
}

const child = spawn(exePath, process.argv.slice(2), {
  stdio: "inherit",
});

child.on("error", (error) => {
  console.error(`[${path.basename(__filename)}] ERROR: failed to launch executable: ${error.message}`);
  process.exit(1);
});

child.on("exit", (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }

  process.exit(code === null ? 1 : code);
});


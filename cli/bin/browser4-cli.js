#!/usr/bin/env node

import fs from "fs";
import path from "path";
import { arch } from "os";
import { execSync, spawn } from "child_process";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const scriptDir = path.dirname(__filename);

function isMusl() {
  if (process.platform !== "linux") {
    return false;
  }

  try {
    const result = execSync("ldd --version 2>&1 || true", { encoding: "utf8" });
    return result.toLowerCase().includes("musl");
  } catch {
    return fs.existsSync("/lib/ld-musl-x86_64.so.1") || fs.existsSync("/lib/ld-musl-aarch64.so.1");
  }
}

function resolveExecutableCandidates() {
  const platformKey = `${process.platform === "linux" && isMusl() ? "linux-musl" : process.platform}-${arch()}`;
  const nativeBinaryName = `browser4-cli-${platformKey}${process.platform === "win32" ? ".exe" : ""}`;
  const sourceBinaryName = `browser4-cli${process.platform === "win32" ? ".exe" : ""}`;

  return [
    path.join(scriptDir, nativeBinaryName),
    path.join(scriptDir, "target", "release", sourceBinaryName),
    path.join(scriptDir, "..", "browser4-cli", "target", "release", sourceBinaryName),
  ];
}

const candidatePaths = resolveExecutableCandidates();
const exePath = candidatePaths.find((candidate) => fs.existsSync(candidate));

if (!exePath) {
  const scriptName = path.basename(__filename);
  console.error(`[${scriptName}] ERROR: executable not found. Checked:`);
  for (const candidate of candidatePaths) {
    console.error(`  - ${candidate}`);
  }
  console.error(`[${scriptName}] Run: npm install browser4-cli or cargo build --release --manifest-path cli/browser4-cli/Cargo.toml`);
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


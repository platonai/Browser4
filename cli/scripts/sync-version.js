#!/usr/bin/env node

/**
 * Syncs the version from the project root VERSION file to all CLI config files.
 *
 * The VERSION file at the repository root is the single source of truth for
 * the entire project.  This script:
 *   - Reads the version from <repo-root>/VERSION
 *   - Strips the Maven-style "-SNAPSHOT" suffix
 *   - Writes the clean semver to cli/package.json and cli/browser4-cli/Cargo.toml
 *   - Updates Cargo.lock to match
 *
 * Usage:
 *   node cli/scripts/sync-version.js          # sync all files
 *   node cli/scripts/sync-version.js --check  # exit 1 if out of sync (CI lint)
 */

import {execSync} from "child_process";
import {existsSync, readFileSync, writeFileSync} from "fs";
import {dirname, join, resolve} from "path";
import {fileURLToPath} from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const scriptsDir = __dirname;                                // cli/scripts
const cliDir = resolve(scriptsDir, "..");                    // cli
const cargoDir = join(cliDir, "browser4-cli");               // cli/browser4-cli

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Walk upward from `startDir` until a file named `filename` is found. */
function findFileUpwards(startDir, filename) {
  let dir = resolve(startDir);
  while (true) {
    const candidate = join(dir, filename);
    if (existsSync(candidate)) return candidate;
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

/** Read the first non-empty line of a file, trimmed. */
function readFirstLine(path) {
  const raw = readFileSync(path, "utf-8");
  const lines = raw.split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.length > 0) return trimmed;
  }
  return null;
}

/** Strip the "-SNAPSHOT" suffix if present. */
function stripSnapshot(version) {
  if (version.endsWith("-SNAPSHOT")) {
    return version.slice(0, -"-SNAPSHOT".length);
  }
  return version;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

const checkOnly = process.argv.includes("--check");

// 1. Locate the project root VERSION file
const versionPath = findFileUpwards(cliDir, "VERSION");
if (!versionPath) {
  console.error("ERROR: Could not find VERSION file in any parent directory.");
  process.exit(1);
}

const repoRoot = dirname(versionPath);
const rawVersion = readFirstLine(versionPath);
if (!rawVersion) {
  console.error("ERROR: VERSION file is empty.");
  process.exit(1);
}

const version = stripSnapshot(rawVersion);
if (checkOnly) console.log(`VERSION file (${versionPath}): ${version}`);

// 2. Update cli/package.json
const packageJsonPath = join(cliDir, "package.json");
const packageJson = JSON.parse(readFileSync(packageJsonPath, "utf-8"));
const pkgName = packageJson.name;
const pkgVersion = packageJson.version;

if (pkgVersion !== version) {
  if (checkOnly) {
    console.error(`MISMATCH: ${packageJsonPath} version is "${pkgVersion}", expected "${version}"`);
    process.exitCode = 1;
  } else {
    packageJson.version = version;
    writeFileSync(packageJsonPath, JSON.stringify(packageJson, null, 2) + "\n");
    console.log(`  Updated ${packageJsonPath}: ${pkgVersion} -> ${version}`);
  }
} else {
  if (!checkOnly) console.log(`  ${packageJsonPath} already up to date`);
}

// 3. Update cli/browser4-cli/Cargo.toml
const cargoTomlPath = join(cargoDir, "Cargo.toml");
let cargoToml = readFileSync(cargoTomlPath, "utf-8");
const cargoVersionRegex = /^version\s*=\s*"[^"]*"/m;
const cargoMatch = cargoToml.match(cargoVersionRegex);

if (!cargoMatch) {
  console.error("ERROR: Could not find version field in Cargo.toml");
  process.exit(1);
}

const cargoVersion = cargoMatch[0].match(/"([^"]*)"/)[1];
if (cargoVersion !== version) {
  if (checkOnly) {
    console.error(`MISMATCH: ${cargoTomlPath} version is "${cargoVersion}", expected "${version}"`);
    process.exitCode = 1;
  } else {
    cargoToml = cargoToml.replace(cargoVersionRegex, `version = "${version}"`);
    writeFileSync(cargoTomlPath, cargoToml);
    console.log(`  Updated ${cargoTomlPath}: ${cargoVersion} -> ${version}`);
  }
} else {
  if (!checkOnly) console.log(`  ${cargoTomlPath} already up to date`);
}

// 4. Update Cargo.lock (only in sync mode)
if (!checkOnly && cargoVersion !== version) {
  try {
    execSync("cargo update -p browser4-cli --offline", {
      cwd: cargoDir,
      stdio: "pipe",
    });
    console.log("  Updated Cargo.lock");
  } catch {
    try {
      execSync("cargo update -p browser4-cli", {
        cwd: cargoDir,
        stdio: "pipe",
      });
      console.log("  Updated Cargo.lock");
    } catch (e) {
      console.error(`  Warning: Could not update Cargo.lock: ${e.message}`);
    }
  }
}

// 5. Report
if (checkOnly) {
  if (process.exitCode === 1) {
    console.error("\nVersion mismatch detected! Run 'node cli/scripts/sync-version.js' to fix.");
  } else {
    console.log(`\nAll versions in sync: ${version}`);
  }
} else {
  console.log(`\nVersion sync complete: ${pkgName}@${version}`);
}

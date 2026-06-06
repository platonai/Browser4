#!/usr/bin/env node

/**
 * Syncs the CLI version from cli/package.json to the Cargo manifest.
 *
 * cli/package.json is the single source of truth for the CLI version.  This
 * allows the backend Maven project and the CLI to be published separately with
 * different versions.  This script:
 *   - Reads the version from cli/package.json
 *   - Strips the Maven-style "-SNAPSHOT" suffix
 *   - Writes the clean semver to cli/browser4-cli/Cargo.toml
 *   - Updates Cargo.lock to match
 *
 * Usage:
 *   node cli/scripts/sync-version.js          # sync all files
 *   node cli/scripts/sync-version.js --check  # exit 1 if out of sync (CI lint)
 */

import {execSync} from "child_process";
import {readFileSync, writeFileSync} from "fs";
import {dirname, join, resolve} from "path";
import {fileURLToPath} from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const scriptsDir = __dirname;                                // cli/scripts
const cliDir = resolve(scriptsDir, "..");                    // cli
const cargoDir = join(cliDir, "browser4-cli");               // cli/browser4-cli

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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

// 1. Read the CLI version from cli/package.json (the source of truth)
const packageJsonPath = join(cliDir, "package.json");
const packageJson = JSON.parse(readFileSync(packageJsonPath, "utf-8"));
const pkgName = packageJson.name;
const pkgVersion = packageJson.version;

if (!pkgVersion) {
  console.error("ERROR: cli/package.json does not contain a version field.");
  process.exit(1);
}

const version = stripSnapshot(pkgVersion);
if (checkOnly) console.log(`cli/package.json: ${version}`);

// 2. Update cli/browser4-cli/Cargo.toml
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

// 3. Update Cargo.lock (only in sync mode)
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

// 4. Report
if (checkOnly) {
  if (process.exitCode === 1) {
    console.error("\nVersion mismatch detected! Run 'node cli/scripts/sync-version.js' to fix.");
  } else {
    console.log(`\nAll versions in sync: ${version}`);
  }
} else {
  console.log(`\nVersion sync complete: ${pkgName}@${version}`);
}

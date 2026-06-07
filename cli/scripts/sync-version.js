#!/usr/bin/env node

/**
 * Syncs the CLI version from cli/package.json to the Cargo manifest.
 *
 * cli/package.json is the single source of truth for the CLI version.  This
 * allows the backend Maven project and the CLI to be published separately with
 * different versions.  This script:
 *   - Reads the version from cli/package.json
 *   - Strips the Maven-style "-SNAPSHOT" suffix
 *   - Fetches the latest published version from npm and compares it
 *     against the local version, warning when the bump is neither a
 *     patch nor a minor increment.
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

/**
 * Parse a semver string into { major, minor, patch } integers.
 * Returns null for non-semver strings (e.g. snapshots, prereleases).
 */
function parseSemver(version) {
  const m = version.match(/^(\d+)\.(\d+)\.(\d+)$/);
  if (!m) return null;
  return { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3]) };
}

/**
 * Fetch the latest published version of a package from the npm registry.
 * Returns the version string, or null if the package hasn't been published
 * yet or the registry is unreachable.
 */
function getLatestPublishedVersion(packageName) {
  try {
    const raw = execSync(`npm view "${packageName}" version --json`, {
      stdio: ["ignore", "pipe", "ignore"],
      timeout: 10_000,
    });
    return JSON.parse(raw.toString().trim());
  } catch {
    // Package may not be published yet, or network is unavailable.
    return null;
  }
}

/**
 * Check whether `local` is an expected bump from `published`:
 *   - same version (already published)
 *   - next patch
 *   - next minor
 *
 * Returns { ok: true } or { ok: false, reason: "..." }.
 */
function checkVersionBump(published, local) {
  const pub = parseSemver(published);
  const loc = parseSemver(local);

  if (!pub || !loc) {
    // Can't compare — pre-release / snapshot versions are fine.
    return { ok: true };
  }

  // Same version — already published.
  if (loc.major === pub.major && loc.minor === pub.minor && loc.patch === pub.patch) {
    return { ok: true, note: `version ${local} is already published` };
  }

  // Next patch: X.Y.Z → X.Y.(Z+1)
  if (loc.major === pub.major && loc.minor === pub.minor && loc.patch === pub.patch + 1) {
    return { ok: true };
  }

  // Next minor: X.Y.Z → X.(Y+1).0
  if (loc.major === pub.major && loc.minor === pub.minor + 1 && loc.patch === 0) {
    return { ok: true };
  }

  // Local is behind published.
  if (
    loc.major < pub.major ||
    (loc.major === pub.major && loc.minor < pub.minor) ||
    (loc.major === pub.major && loc.minor === pub.minor && loc.patch < pub.patch)
  ) {
    return {
      ok: false,
      reason: `local version ${local} is behind the published version ${published}`,
    };
  }

  // Anything else — skipping versions, major bump, etc.
  return {
    ok: false,
    reason: `version bump from ${published} to ${local} is neither a patch nor a minor increment`,
  };
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

// 2. Compare against the latest published version on npm
const publishedVersion = getLatestPublishedVersion(pkgName);
if (publishedVersion) {
  console.log(`Latest published: ${pkgName}@${publishedVersion}`);
  const bump = checkVersionBump(publishedVersion, version);
  if (!bump.ok) {
    console.warn(`\x1b[33mWARNING: ${bump.reason}\x1b[0m`);
  } else if (bump.note) {
    console.log(`  (${bump.note})`);
  }
} else {
  console.log(`Latest published: (not found — package may not be published yet)`);
}

// 3. Sync cli/browser4-cli/Cargo.toml
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
  let lockUpdated = false;
  try {
    execSync("cargo update -p browser4-cli --offline", {
      cwd: cargoDir,
      stdio: "pipe",
    });
    console.log("  Updated Cargo.lock");
    lockUpdated = true;
  } catch {
    try {
      execSync("cargo update -p browser4-cli", {
        cwd: cargoDir,
        stdio: "pipe",
      });
      console.log("  Updated Cargo.lock");
      lockUpdated = true;
    } catch (e) {
      console.error(`  Warning: Could not update Cargo.lock via cargo: ${e.message}`);
    }
  }

  // Fallback: edit Cargo.lock directly when cargo update fails (e.g. due to
  // lock file version mismatch between the installed cargo and the lock file).
  if (!lockUpdated) {
    const cargoLockPath = join(cargoDir, "Cargo.lock");
    try {
      let lockContent = readFileSync(cargoLockPath, "utf-8");
      // Match the [[package]] block for browser4-cli and replace its version.
      // Works for both v3 and v4 lock file formats.
      const lockPkgRegex = new RegExp(
        `(\\[\\[package\\]\\][\\r\\n]+\\s*name\\s*=\\s*"browser4-cli"[\\r\\n]+\\s*version\\s*=\\s*)"[^"]*"`,
        "m"
      );
      if (lockPkgRegex.test(lockContent)) {
        lockContent = lockContent.replace(lockPkgRegex, `$1"${version}"`);
        writeFileSync(cargoLockPath, lockContent);
        console.log(`  Updated Cargo.lock directly: ${cargoVersion} -> ${version}`);
        lockUpdated = true;
      } else {
        console.error("  Warning: Could not find browser4-cli entry in Cargo.lock");
      }
    } catch (e2) {
      console.error(`  Warning: Could not update Cargo.lock directly: ${e2.message}`);
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

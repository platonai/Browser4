#!/usr/bin/env node

/**
 * Unified version maintenance script for Browser4.
 *
 * Browser4 has TWO independent version tracks:
 *   1. Backend (Maven) — VERSION file          → pom.xml, READMEs
 *   2. CLI (npm/Cargo)  — cli/VERSION-CLI file → package.json, Cargo.toml
 *
 * Usage:
 *   # Backend version (VERSION file)
 *   node bin/version.mjs show              Print backend version
 *   node bin/version.mjs show -v           Print backend version + git metadata
 *   node bin/version.mjs release           Strip -SNAPSHOT for release
 *   node bin/version.mjs bump <part>       Bump major/minor/patch, update pom.xml, commit
 *   node bin/version.mjs bump <part> --dry-run     Show what would change
 *   node bin/version.mjs bump <part> --skip-precheck  Skip publish-status check
 *   node bin/version.mjs auto              Bump backend to next patch; bump CLI if changes detected
 *   node bin/version.mjs auto --dry-run    Show what would change without applying
 *   node bin/version.mjs auto --commit     Apply and commit+push
 *
 *   # CLI version (cli/VERSION-CLI file)
 *   node bin/version.mjs cli show          Print CLI version
 *   node bin/version.mjs cli sync          Sync VERSION-CLI → package.json, Cargo.toml
 *   node bin/version.mjs cli sync --check  Check-only mode (CI, exit 1 if mismatch)
 *   node bin/version.mjs cli auto          Bump CLI to next patch if changes detected in cli/
 *   node bin/version.mjs cli auto --dry-run  Show what would change
 *
 *   # Cross-cutting
 *   node bin/version.mjs check             Full version consistency check (both systems)
 */

import { execSync } from "child_process";
import { existsSync, readFileSync, readdirSync, writeFileSync } from "fs";
import { join, relative, resolve } from "path";
import { createInterface } from "readline";
import { fileURLToPath } from "url";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const __dirname = resolve(fileURLToPath(import.meta.url), "..");

/** Find the repository root via git, falling back to walking up from this script. */
function getRepoRoot() {
  try {
    return execSync("git rev-parse --show-toplevel", {
      stdio: ["ignore", "pipe", "ignore"],
    })
      .toString()
      .trim();
  } catch {
    let dir = __dirname;
    while (dir !== resolve(dir, "..")) {
      if (existsSync(join(dir, "VERSION"))) return dir;
      dir = resolve(dir, "..");
    }
    console.error("ERROR: Cannot find repository root.");
    process.exit(1);
  }
}

const REPO_ROOT = getRepoRoot();

function stripSnapshot(version) {
  return version.endsWith("-SNAPSHOT")
    ? version.slice(0, -"-SNAPSHOT".length)
    : version;
}

function parseSemver(version) {
  const m = version.match(/^(\d+)\.(\d+)\.(\d+)(?:-(.+))?$/);
  if (!m) return null;
  return {
    major: Number(m[1]),
    minor: Number(m[2]),
    patch: Number(m[3]),
    prerelease: m[4] || null,
  };
}

function readBackendVersion() {
  const path = join(REPO_ROOT, "VERSION");
  if (!existsSync(path)) {
    console.error("ERROR: VERSION file not found at", path);
    process.exit(1);
  }
  return readFileSync(path, "utf-8").trim();
}

function readCliVersion() {
  const path = join(REPO_ROOT, "cli", "VERSION-CLI");
  if (!existsSync(path)) {
    console.error("ERROR: cli/VERSION-CLI not found at", path);
    process.exit(1);
  }
  const v = readFileSync(path, "utf-8").trim();
  if (!v) {
    console.error("ERROR: cli/VERSION-CLI is empty.");
    process.exit(1);
  }
  return v;
}

function getLatestNpmVersion(packageName) {
  try {
    const raw = execSync(`npm view "${packageName}" version --json`, {
      stdio: ["ignore", "pipe", "ignore"],
      timeout: 10_000,
    });
    return JSON.parse(raw.toString().trim());
  } catch {
    return null;
  }
}

function checkVersionBump(published, local) {
  const pub = parseSemver(published);
  const loc = parseSemver(local);
  if (!pub || !loc) return { ok: true };

  if (loc.major === pub.major && loc.minor === pub.minor && loc.patch === pub.patch) {
    return { ok: true, note: `version ${local} is already published` };
  }
  if (loc.major === pub.major && loc.minor === pub.minor && loc.patch === pub.patch + 1) {
    return { ok: true };
  }
  if (loc.major === pub.major && loc.minor === pub.minor + 1 && loc.patch === 0) {
    return { ok: true };
  }
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
  return {
    ok: false,
    reason: `version bump from ${published} to ${local} is neither a patch nor a minor increment`,
  };
}

/** Bump a semver version string by the given part. */
function bumpSemverPart(version, part) {
  const parsed = parseSemver(version);
  if (!parsed) return null;
  let { major, minor, patch } = parsed;
  switch (part) {
    case "major": major++; minor = 0; patch = 0; break;
    case "minor": minor++; patch = 0; break;
    case "patch": patch++; break;
    default: return null;
  }
  return `${major}.${minor}.${patch}`;
}

/**
 * Check if there are any changes under cli/ since the last release.
 *
 * "Since last release" means: all changes on the current branch vs the base
 * branch (origin/main or origin/master), PLUS any uncommitted work (staged
 * or unstaged).  VERSION-CLI itself is excluded to avoid circular bumps.
 */
function hasCliChanges() {
  try {
    const baseBranch = getBaseBranch();
    const parts = [];

    // Committed changes on this branch vs base
    if (baseBranch) {
      try {
        const branchDiff = execSync(
          `git diff --name-only ${baseBranch}..HEAD -- cli/`,
          { cwd: REPO_ROOT, stdio: ["ignore", "pipe", "ignore"] }
        ).toString().trim();
        if (branchDiff) parts.push(branchDiff);
      } catch { /* ignore */ }
    }

    // Unstaged changes
    try {
      const unstaged = execSync("git diff --name-only -- cli/", {
        cwd: REPO_ROOT, stdio: ["ignore", "pipe", "ignore"],
      }).toString().trim();
      if (unstaged) parts.push(unstaged);
    } catch { /* ignore */ }

    // Staged changes
    try {
      const staged = execSync("git diff --cached --name-only -- cli/", {
        cwd: REPO_ROOT, stdio: ["ignore", "pipe", "ignore"],
      }).toString().trim();
      if (staged) parts.push(staged);
    } catch { /* ignore */ }

    const allChanges = parts
      .flatMap((s) => s.split("\n"))
      .filter(Boolean)
      .filter((f) => f !== "cli/VERSION-CLI");

    return allChanges.length > 0;
  } catch {
    return false;
  }
}

/** Walk dir up to maxDepth finding files named `targetName`. */
function findFiles(dir, targetName, maxDepth) {
  const results = [];
  const walk = (current, depth) => {
    if (depth > maxDepth) return;
    let entries;
    try {
      entries = readdirSync(current, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = join(current, entry.name);
      if (entry.isDirectory()) {
        // Skip well-known non-source directories
        if (entry.name === "node_modules" || entry.name === "target" || entry.name === ".git") continue;
        walk(full, depth + 1);
      } else if (entry.isFile() && entry.name === targetName) {
        results.push(full);
      }
    }
  };
  walk(dir, 0);
  return results;
}

/** Print a message using the maintenance check result format. */
function checkItem(label, status, message) {
  const icon = status === "passed" ? "✓" : status === "failed" ? "✗" : status === "error" ? "‼" : "○";
  console.log(`  ${icon} ${label}: ${message}`);
  return status;
}

// ---------------------------------------------------------------------------
// Subcommand: show (backend version)
// ---------------------------------------------------------------------------

function cmdShow(args) {
  const verbose = args.includes("-v") || args.includes("--verbose");

  if (verbose) {
    const version = readBackendVersion();
    let hash = "", branch = "", date = "";
    try {
      hash = execSync("git show-ref --head --hash=7 head", {
        stdio: ["ignore", "pipe", "ignore"],
      }).toString().trim().substring(0, 7);
    } catch { /* ignore */ }
    try {
      branch = execSync("git rev-parse --abbrev-ref HEAD", {
        stdio: ["ignore", "pipe", "ignore"],
      }).toString().trim();
    } catch { /* ignore */ }
    try {
      date = execSync("git log -1 --pretty=%ad --date=short", {
        stdio: ["ignore", "pipe", "ignore"],
      }).toString().trim();
    } catch { /* ignore */ }
    console.log(`v${version} ${hash} ${branch} ${date}`);
  } else {
    console.log(`v${readBackendVersion()}`);
  }
}

// ---------------------------------------------------------------------------
// Subcommand: cli (CLI version operations)
// ---------------------------------------------------------------------------

function cmdCli(args) {
  if (args.length === 0 || args[0] === "-h" || args[0] === "--help") {
    console.log("CLI version commands (source: cli/VERSION-CLI):");
    console.log("  cli show          Print CLI version");
    console.log("  cli sync          Sync VERSION-CLI → package.json, Cargo.toml, Cargo.lock");
    console.log("  cli sync --check  Check-only mode (exit 1 if anything is out of sync)");
    console.log("  cli auto          Bump CLI to next patch if changes detected in cli/");
    console.log("  cli auto --dry-run  Show what would change without applying");
    process.exit(0);
  }

  const sub = args[0];
  const rest = args.slice(1);

  switch (sub) {
    case "show":
      console.log(stripSnapshot(readCliVersion()));
      break;
    case "sync":
      cmdCliSync(rest);
      break;
    case "auto":
      cmdCliAuto(rest);
      break;
    default:
      console.error(`Unknown CLI command: cli ${sub}`);
      console.error("Available: cli show, cli sync, cli auto");
      process.exit(1);
  }
}

// ---------------------------------------------------------------------------
// Subcommand: cli auto
// ---------------------------------------------------------------------------

function cmdCliAuto(args) {
  const dryRun = args.includes("--dry-run");

  // Read local version
  const localCli = stripSnapshot(readCliVersion());
  if (!parseSemver(localCli)) {
    console.error(`ERROR: CLI version '${localCli}' does not match X.Y.Z format.`);
    process.exit(1);
  }

  // Determine the base version from the last npm release
  const publishedNpm = getLatestNpmVersion("browser4-cli");
  const baseCli = publishedNpm || localCli;

  if (!hasCliChanges()) {
    console.log("CLI: no changes detected in cli/, nothing to bump.");
    return;
  }

  const nextCli = bumpSemverPart(baseCli, "patch");
  if (nextCli === localCli) {
    console.log(`CLI: already at next patch after ${baseCli} (${localCli}), nothing to bump.`);
    return;
  }

  console.log(`CLI auto-bump: ${localCli} -> ${nextCli} (changes detected in cli/)`);

  if (dryRun) {
    console.log("");
    console.log("========== DRY-RUN MODE ==========");
    console.log("No changes will be made.");
    console.log("");
    console.log("Would perform:");
    console.log(`  1. Update cli/VERSION-CLI: '${localCli}' -> '${nextCli}'`);
    console.log("  2. Sync cli/package.json and cli/browser4-cli/Cargo.toml");
    console.log("==================================");
    return;
  }

  // Apply
  writeFileSync(join(REPO_ROOT, "cli", "VERSION-CLI"), nextCli + "\n");
  cmdCliSync([]);
  console.log(`\nCLI auto-bump complete: ${localCli} -> ${nextCli}`);
}

// ---------------------------------------------------------------------------
// Subcommand: cli sync
// ---------------------------------------------------------------------------

function cmdCliSync(args) {
  const checkOnly = args.includes("--check");
  const pkgName = "browser4-cli";

  // 1. Read the CLI version
  const versionRaw = readCliVersion();
  const version = stripSnapshot(versionRaw);
  if (checkOnly) console.log(`cli/VERSION-CLI: ${version}`);

  // 2. Compare against latest published npm version
  const publishedVersion = getLatestNpmVersion(pkgName);
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

  // 3. Sync cli/package.json
  const packageJsonPath = join(REPO_ROOT, "cli", "package.json");
  const packageJson = JSON.parse(readFileSync(packageJsonPath, "utf-8"));
  if (!packageJson.version) {
    console.error("ERROR: cli/package.json does not contain a version field.");
    process.exit(1);
  }
  if (packageJson.version !== version) {
    if (checkOnly) {
      console.error(`MISMATCH: cli/package.json version is "${packageJson.version}", expected "${version}"`);
      process.exitCode = 1;
    } else {
      const old = packageJson.version;
      packageJson.version = version;
      writeFileSync(packageJsonPath, JSON.stringify(packageJson, null, 2) + "\n");
      console.log(`  Updated cli/package.json: ${old} -> ${version}`);
    }
  } else {
    if (!checkOnly) console.log(`  cli/package.json already up to date`);
  }

  // 4. Sync cli/browser4-cli/Cargo.toml
  const cargoDir = join(REPO_ROOT, "cli", "browser4-cli");
  const cargoTomlPath = join(cargoDir, "Cargo.toml");
  let cargoToml = readFileSync(cargoTomlPath, "utf-8");
  const cargoVersionRegex = /^version\s*=\s*"[^"]*"/m;
  const cargoMatch = cargoToml.match(cargoVersionRegex);
  if (!cargoMatch) {
    console.error("ERROR: Could not find version field in Cargo.toml");
    process.exit(1);
  }
  const cargoVersion = cargoMatch[0].match(/"([^"]*)"/)[1];
  let cargoChanged = false;
  if (cargoVersion !== version) {
    if (checkOnly) {
      console.error(`MISMATCH: cli/browser4-cli/Cargo.toml version is "${cargoVersion}", expected "${version}"`);
      process.exitCode = 1;
    } else {
      cargoToml = cargoToml.replace(cargoVersionRegex, `version = "${version}"`);
      writeFileSync(cargoTomlPath, cargoToml);
      console.log(`  Updated cli/browser4-cli/Cargo.toml: ${cargoVersion} -> ${version}`);
      cargoChanged = true;
    }
  } else {
    if (!checkOnly) console.log(`  cli/browser4-cli/Cargo.toml already up to date`);
  }

  // 5. Update Cargo.lock (sync mode only, when Cargo.toml changed)
  if (!checkOnly && cargoChanged) {
    updateCargoLock(cargoDir, cargoVersion, version);
  }

  // 6. Report
  if (checkOnly) {
    if (process.exitCode === 1) {
      console.error("\nVersion mismatch detected! Run 'node bin/version.mjs cli sync' to fix.");
    } else {
      console.log(`\nAll versions in sync with cli/VERSION-CLI: ${version}`);
    }
  } else {
    console.log(`\nVersion sync complete: ${pkgName}@${version}`);
  }
}

function updateCargoLock(cargoDir, oldVersion, newVersion) {
  let lockUpdated = false;
  try {
    execSync("cargo update -p browser4-cli --offline", { cwd: cargoDir, stdio: "pipe" });
    console.log("  Updated Cargo.lock");
    lockUpdated = true;
  } catch {
    try {
      execSync("cargo update -p browser4-cli", { cwd: cargoDir, stdio: "pipe" });
      console.log("  Updated Cargo.lock");
      lockUpdated = true;
    } catch (e) {
      console.error(`  Warning: Could not update Cargo.lock via cargo: ${e.message}`);
    }
  }
  if (!lockUpdated) {
    const cargoLockPath = join(cargoDir, "Cargo.lock");
    try {
      let lockContent = readFileSync(cargoLockPath, "utf-8");
      const lockPkgRegex = new RegExp(
        `(\\[\\[package\\]\\][\\r\\n]+\\s*name\\s*=\\s*"browser4-cli"[\\r\\n]+\\s*version\\s*=\\s*)"[^"]*"`,
        "m"
      );
      if (lockPkgRegex.test(lockContent)) {
        lockContent = lockContent.replace(lockPkgRegex, `$1"${newVersion}"`);
        writeFileSync(cargoLockPath, lockContent);
        console.log(`  Updated Cargo.lock directly: ${oldVersion} -> ${newVersion}`);
      } else {
        console.error("  Warning: Could not find browser4-cli entry in Cargo.lock");
      }
    } catch (e2) {
      console.error(`  Warning: Could not update Cargo.lock directly: ${e2.message}`);
    }
  }
}

// ---------------------------------------------------------------------------
// Subcommand: release
// ---------------------------------------------------------------------------

function cmdRelease() {
  const snapshotVersion = readBackendVersion();

  if (!snapshotVersion.endsWith("-SNAPSHOT")) {
    console.error(`ERROR: VERSION file contains "${snapshotVersion}" which is not a SNAPSHOT version.`);
    console.error("Is this version already released?");
    process.exit(1);
  }

  const version = stripSnapshot(snapshotVersion);
  console.log(`Converting from SNAPSHOT to release: ${snapshotVersion} -> ${version}`);

  // Write release version to VERSION file
  writeFileSync(join(REPO_ROOT, "VERSION"), version + "\n");
  console.log(`  Updated VERSION: ${snapshotVersion} -> ${version}`);

  // Replace in pom.xml, README.md, README.zh.md, llm-config.md
  const filePatterns = ["pom.xml", "llm-config.md", "README.md", "README.zh.md"];
  for (const pattern of filePatterns) {
    const files = findFiles(REPO_ROOT, pattern, 8);
    for (const file of files) {
      let content = readFileSync(file, "utf-8");
      if (content.includes(snapshotVersion)) {
        content = content.replaceAll(snapshotVersion, version);
        writeFileSync(file, content);
        console.log(`  Updated ${relative(REPO_ROOT, file)}: ${snapshotVersion} -> ${version}`);
      }
    }
  }

  // Also sync CLI version metadata
  console.log("Syncing CLI version metadata...");
  cmdCliSync([]);

  console.log(`\nRelease version conversion complete: ${version}`);
}

// ---------------------------------------------------------------------------
// Subcommand: bump
// ---------------------------------------------------------------------------

/** Prompt for confirmation on the terminal. Non-TTY returns true (auto-confirm). */
async function confirm(prompt) {
  if (!process.stdin.isTTY) return true;
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => {
    rl.question(prompt, (answer) => {
      rl.close();
      const a = answer.trim();
      resolve(a === "" || a === "Y" || a === "y");
    });
  });
}

async function cmdBump(args) {
  const partIdx = args.findIndex(
    (a) => a === "major" || a === "minor" || a === "patch"
  );
  if (partIdx === -1) {
    console.error("ERROR: Must specify part to bump: major, minor, or patch");
    console.error("Usage: node bin/version.mjs bump <major|minor|patch> [--dry-run] [--skip-precheck]");
    process.exit(1);
  }
  const part = args[partIdx];
  const dryRun = args.includes("--dry-run");
  const skipPrecheck = args.includes("--skip-precheck");

  // Ensure we're not on main/master
  let currentBranch;
  try {
    currentBranch = execSync("git rev-parse --abbrev-ref HEAD", {
      stdio: ["ignore", "pipe", "ignore"],
    }).toString().trim();
  } catch {
    console.error("ERROR: Cannot determine current git branch.");
    process.exit(1);
  }
  if (currentBranch === "master" || currentBranch === "main") {
    console.error(`You are on the '${currentBranch}' branch. Please switch to a feature branch first.`);
    process.exit(1);
  }

  // Read current version
  const snapshotVersion = readBackendVersion();
  const version = stripSnapshot(snapshotVersion);
  const parsed = parseSemver(version);
  if (!parsed) {
    console.error(`ERROR: Version '${version}' does not match X.Y.Z format.`);
    process.exit(1);
  }

  const nextVersion = bumpSemverPart(version, part);
  const nextSnapshot = `${nextVersion}-SNAPSHOT`;

  // Precheck: verify current version is published (unless skipped)
  if (!skipPrecheck && !dryRun) {
    console.log("");
    console.log(`Running publish-status precheck for version v${version}...`);
    console.log("---------------------------------------------------------");
    const checkScript = join(REPO_ROOT, "bin", "release", "check-publish-status.ps1");
    if (existsSync(checkScript)) {
      const pwsh = process.platform === "win32" ? "pwsh.exe" : "pwsh";
      try {
        execSync(`${pwsh} -NoProfile -File "${checkScript}" -Version ${version}`, {
          cwd: REPO_ROOT,
          stdio: "inherit",
        });
      } catch {
        console.error("");
        console.error(
          `Precheck failed: version v${version} has not been fully published. ` +
          "Ensure the version is the latest GitHub release and pulsar-bom is on Maven Central. " +
          "Use --skip-precheck to bypass this check."
        );
        process.exit(1);
      }
    } else {
      console.warn(`check-publish-status.ps1 not found at '${checkScript}'. Skipping precheck.`);
    }
    console.log("---------------------------------------------------------");
    console.log("");
  } else if (skipPrecheck && !dryRun) {
    console.log("");
    console.log("Precheck skipped (--skip-precheck). Proceeding without publish-status verification.");
    console.log("");
  }

  console.log(`Current version: ${snapshotVersion}`);
  console.log(`New version:     ${nextSnapshot}`);

  // Try-run mode: show what would happen and exit
  if (dryRun) {
    console.log("");
    console.log("========== DRY-RUN MODE ==========");
    console.log("No changes will be made.");
    console.log("");
    console.log("The following actions would be performed:");
    console.log(`  1. Update VERSION file: '${snapshotVersion}' -> '${nextSnapshot}'`);
    console.log(`  2. Run Maven versions:set -DnewVersion=${nextSnapshot} on all modules`);
    console.log(`  3. Update root pom.xml <tag>: 'v${version}' -> 'v${nextVersion}'`);
    console.log("  4. git add .");
    console.log(`  5. git commit -m 'Bump version to v${nextVersion}'`);
    console.log("  6. git push");
    console.log("");
    console.log("Files that would be modified:");
    console.log("  - VERSION");
    console.log("  - pom.xml (root: <tag> update)");
    console.log("  - pom.xml (all modules: <version> update via Maven)");
    console.log("==================================");
    process.exit(0);
  }

  // Update VERSION file
  writeFileSync(join(REPO_ROOT, "VERSION"), nextSnapshot + "\n");

  // Run Maven versions:set
  const isWindows = process.platform === "win32";
  const mvnCmd = isWindows
    ? join(REPO_ROOT, "mvnw.cmd")
    : join(REPO_ROOT, "mvnw");
  const mvnArgs = [
    "versions:set",
    `-DnewVersion=${nextSnapshot}`,
    "-DprocessAllModules",
    "-DgenerateBackupPoms=false",
  ];
  try {
    if (isWindows) {
      execSync(`cmd /c "${mvnCmd}" ${mvnArgs.join(" ")}`, {
        cwd: REPO_ROOT,
        stdio: "inherit",
      });
    } else {
      execSync(`"${mvnCmd}" ${mvnArgs.join(" ")}`, {
        cwd: REPO_ROOT,
        stdio: "inherit",
      });
    }
  } catch {
    console.error("Maven versions:set command failed. Reverting VERSION file.");
    writeFileSync(join(REPO_ROOT, "VERSION"), snapshotVersion + "\n");
    process.exit(1);
  }

  // Update root pom.xml <tag>
  const pomXmlPath = join(REPO_ROOT, "pom.xml");
  if (existsSync(pomXmlPath)) {
    let pomContent = readFileSync(pomXmlPath, "utf-8");
    pomContent = pomContent.replace(`<tag>v${version}</tag>`, `<tag>v${nextVersion}</tag>`);
    writeFileSync(pomXmlPath, pomContent);
  }

  // Confirm and commit
  const comment = `Bump version to v${nextVersion}`;
  console.log(`Ready to commit with comment: <${comment}>`);
  const ok = await confirm("Are you sure to continue? [Y/n] ");
  if (!ok) {
    console.log("Operation cancelled. Run 'git checkout .' to revert changes.");
    process.exit(0);
  }

  try {
    execSync("git add .", { cwd: REPO_ROOT, stdio: "inherit" });
    execSync(`git commit -m "${comment}"`, { cwd: REPO_ROOT, stdio: "inherit" });
    execSync("git push", { cwd: REPO_ROOT, stdio: "inherit" });
    console.log(`Version bumped to ${nextVersion} and changes pushed to remote.`);
  } catch (e) {
    console.error("Git operation failed:", e.message);
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------
// Helpers: release info & change summary (used by auto)
// ---------------------------------------------------------------------------

/** Get the latest GitHub release tag, falling back to git tags. */
function getLatestReleaseTag() {
  try {
    // Try gh CLI first (gives us actual GitHub Releases)
    const raw = execSync("gh release list --limit 1 --json tagName,name --jq '.[0].tagName'", {
      cwd: REPO_ROOT, stdio: ["ignore", "pipe", "ignore"],
      timeout: 10_000,
    }).toString().trim();
    if (raw) return raw;
  } catch { /* fall through */ }

  // Fallback: latest semver-sorted tag
  try {
    const tag = execSync("git tag --sort=-v:refname | grep -E '^v?[0-9]+\\.[0-9]+\\.[0-9]+' | head -1", {
      cwd: REPO_ROOT, stdio: ["ignore", "pipe", "ignore"],
    }).toString().trim();
    if (tag) return tag;
  } catch { /* fall through */ }

  // Last resort: git describe
  try {
    const desc = execSync("git describe --tags --abbrev=0", {
      cwd: REPO_ROOT, stdio: ["ignore", "pipe", "ignore"],
    }).toString().trim();
    if (desc) return desc;
  } catch { /* ignore */ }
  return null;
}

/**
 * Print a section of git log / diff stat lines, indented and capped.
 * @param {string} title
 * @param {string} lines  — raw multi-line output from git
 * @param {number} max    — max lines to show
 */
function printGitSummary(title, lines, max) {
  const entries = lines.trim().split("\n").filter(Boolean);
  if (!entries.length) {
    console.log(`  ${title}: (none)`);
    return;
  }
  console.log(`  ${title} (${entries.length}):`);
  for (const line of entries.slice(0, max)) {
    // Strip leading whitespace from diff-stat lines
    console.log(`    ${line.trim()}`);
  }
  if (entries.length > max) {
    console.log(`    ... and ${entries.length - max} more`);
  }
}

/** Get the upstream base branch (origin/main or origin/master). */
function getBaseBranch() {
  for (const name of ["origin/main", "origin/master"]) {
    try {
      execSync(`git rev-parse --verify ${name}`, { cwd: REPO_ROOT, stdio: "ignore" });
      return name;
    } catch { /* try next */ }
  }
  // Fall back to local
  for (const name of ["main", "master"]) {
    try {
      execSync(`git rev-parse --verify ${name}`, { cwd: REPO_ROOT, stdio: "ignore" });
      return name;
    } catch { /* try next */ }
  }
  return null;
}

// ---------------------------------------------------------------------------
// Subcommand: auto (bump backend patch + conditionally bump CLI patch)
// ---------------------------------------------------------------------------

async function cmdAuto(args) {
  const dryRun = args.includes("--dry-run");
  const commit = args.includes("--commit");

  // ---- Verify we're not on main/master ----
  let currentBranch;
  try {
    currentBranch = execSync("git rev-parse --abbrev-ref HEAD", {
      stdio: ["ignore", "pipe", "ignore"],
    }).toString().trim();
  } catch {
    console.error("ERROR: Cannot determine current git branch.");
    process.exit(1);
  }
  if (currentBranch === "master" || currentBranch === "main") {
    console.error(
      `You are on the '${currentBranch}' branch. Auto-bump is disabled on protected branches.`
    );
    process.exit(1);
  }

  // ================================================================
  // PHASE 1 — Gather information
  // ================================================================

  // Local versions
  const snapshotVersion = readBackendVersion();
  const localBackend = stripSnapshot(snapshotVersion);
  if (!parseSemver(localBackend)) {
    console.error(`ERROR: Backend version '${localBackend}' does not match X.Y.Z format.`);
    process.exit(1);
  }

  const cliVersionPath = join(REPO_ROOT, "cli", "VERSION-CLI");
  const localCli = existsSync(cliVersionPath) ? stripSnapshot(readCliVersion()) : "";

  // Release info (query BEFORE computing next versions)
  const lastReleaseTag = getLatestReleaseTag();
  const publishedNpm = getLatestNpmVersion("browser4-cli");

  // Parse last-release versions (strip leading "v" and any prerelease suffix
  // like "-ci.1" from the git tag, e.g. "v4.11.11-ci.1" → "4.11.11")
  const lastReleaseBackend = lastReleaseTag
    ? lastReleaseTag.replace(/^v/, "").replace(/-.*$/, "")
    : localBackend;
  const lastReleaseCli = publishedNpm || localCli;

  // ---- Compute next versions from last RELEASE (not local) ----
  const nextBackend = bumpSemverPart(lastReleaseBackend, "patch");
  const nextSnapshot = `${nextBackend}-SNAPSHOT`;
  const backendChanged = nextBackend !== localBackend;

  // CLI: only bump if cli/ has changes AND next > local
  let cliBumped = false;
  let cliOldVersion = localCli;
  let cliNewVersion = localCli;
  if (localCli && hasCliChanges()) {
    const parsedCli = parseSemver(localCli);
    if (parsedCli) {
      const nextCli = bumpSemverPart(lastReleaseCli, "patch");
      if (nextCli !== localCli) {
        cliNewVersion = nextCli;
        cliBumped = true;
      }
    }
  }

  // Changes summary (commits + files since last tag, or vs base branch)
  const sinceRef = lastReleaseTag || getBaseBranch() || "HEAD~10";
  let commitLog = "", fileStat = "";
  try {
    commitLog = execSync(`git log --oneline ${sinceRef}..HEAD`, {
      cwd: REPO_ROOT, stdio: ["ignore", "pipe", "ignore"],
    }).toString().trim();
  } catch { /* ignore */ }
  try {
    fileStat = execSync(`git diff --stat ${sinceRef}..HEAD`, {
      cwd: REPO_ROOT, stdio: ["ignore", "pipe", "ignore"],
    }).toString().trim();
  } catch { /* ignore */ }

  // ================================================================
  // PHASE 2 — Display
  // ================================================================

  console.log("");
  console.log("══════════════════════════════════════════════");
  console.log("           AUTO-BUMP — Plan");
  console.log("══════════════════════════════════════════════");
  console.log("");

  // --- Last Release ---
  console.log("┌─ Last Release");
  if (lastReleaseTag) {
    console.log(`│  GitHub:  ${lastReleaseTag}  (backend=${lastReleaseBackend})`);
  } else {
    console.log(`│  GitHub:  (no release found)`);
  }
  console.log(`│  npm:     ${publishedNpm ? `browser4-cli@${publishedNpm}` : "(not published yet)"}`);
  console.log(`│  Local:   backend=${snapshotVersion}  cli=${localCli || "N/A"}`);
  console.log("");

  // --- Proposed Bumps ---
  console.log("┌─ Proposed Version Bumps (next patch after last release)");
  if (backendChanged) {
    console.log(`│  Backend: ${snapshotVersion}  →  ${nextSnapshot}`);
  } else {
    console.log(`│  Backend: ${snapshotVersion}  (already at next after ${lastReleaseTag || "last release"})`);
  }
  if (cliBumped) {
    console.log(`│  CLI:     ${cliOldVersion}  →  ${cliNewVersion}`);
  } else if (localCli) {
    console.log(`│  CLI:     ${localCli}  (no changes or already at next)`);
  } else {
    console.log(`│  CLI:     (VERSION-CLI not found)`);
  }
  console.log("");

  // --- Changes Since Last Release ---
  console.log("┌─ Changes Since Last Release");
  if (sinceRef && (commitLog || fileStat)) {
    printGitSummary("Commits", commitLog, 15);
    if (fileStat) {
      console.log("");
      printGitSummary("Files", fileStat, 12);
    }
  } else {
    console.log("  (could not determine change set)");
  }
  console.log("");

  // ================================================================
  // PHASE 3 — Act
  // ================================================================

  if (dryRun) {
    console.log("══════════════════════════════════════════════");
    console.log("  DRY-RUN — No changes have been made.");
    console.log("══════════════════════════════════════════════");
    console.log("");
    return;
  }

  // If nothing to bump, exit cleanly
  if (!backendChanged && !cliBumped) {
    console.log("Nothing to bump — versions are already up to date.");
    return;
  }

  // Confirm
  const ok = await confirm("Proceed with auto-bump? [Y/n] ");
  if (!ok) {
    console.log("Auto-bump cancelled.");
    process.exit(0);
  }
  console.log("");

  // ---- Apply backend bump ----
  if (backendChanged) {
    writeFileSync(join(REPO_ROOT, "VERSION"), nextSnapshot + "\n");

    // Run Maven versions:set
    const isWindows = process.platform === "win32";
    const mvnCmd = isWindows ? join(REPO_ROOT, "mvnw.cmd") : join(REPO_ROOT, "mvnw");
    const mvnArgs = [
      "versions:set",
      `-DnewVersion=${nextSnapshot}`,
      "-DprocessAllModules",
      "-DgenerateBackupPoms=false",
    ];
    try {
      if (isWindows) {
        execSync(`cmd /c "${mvnCmd}" ${mvnArgs.join(" ")}`, {
          cwd: REPO_ROOT,
          stdio: "inherit",
        });
      } else {
        execSync(`"${mvnCmd}" ${mvnArgs.join(" ")}`, {
          cwd: REPO_ROOT,
          stdio: "inherit",
        });
      }
    } catch {
      console.error("Maven versions:set failed. Reverting VERSION file.");
      writeFileSync(join(REPO_ROOT, "VERSION"), snapshotVersion + "\n");
      process.exit(1);
    }

    // Update root pom.xml <tag>
    const pomXmlPath = join(REPO_ROOT, "pom.xml");
    if (existsSync(pomXmlPath)) {
      let pomContent = readFileSync(pomXmlPath, "utf-8");
      pomContent = pomContent.replace(
        new RegExp(`<tag>v${localBackend.replace(/\./g, "\\.")}</tag>`),
        `<tag>v${nextBackend}</tag>`
      );
      writeFileSync(pomXmlPath, pomContent);
    }
  }

  // ---- Apply CLI bump ----
  if (cliBumped) {
    writeFileSync(join(REPO_ROOT, "cli", "VERSION-CLI"), cliNewVersion + "\n");
    // Sync VERSION-CLI changes into package.json, Cargo.toml, Cargo.lock
    cmdCliSync([]);
  }

  // ---- Summary ----
  const bumped = [];
  if (backendChanged) bumped.push(`Backend: ${snapshotVersion} -> ${nextSnapshot}`);
  if (cliBumped) bumped.push(`CLI: ${cliOldVersion} -> ${cliNewVersion}`);
  if (bumped.length) {
    console.log(`\nAuto-bump complete: ${bumped.join(", ")}`);
  }

  // ---- Commit (optional) ----
  if (commit && bumped.length) {
    const parts = [];
    if (backendChanged) parts.push(`Backend: ${nextSnapshot}`);
    if (cliBumped) parts.push(`CLI: ${cliNewVersion}`);
    const msg = `Auto-bump versions\n\n${parts.join("\n")}`;
    try {
      execSync("git add .", { cwd: REPO_ROOT, stdio: "inherit" });
      execSync(`git commit -m "${msg}"`, { cwd: REPO_ROOT, stdio: "inherit" });
      execSync("git push", { cwd: REPO_ROOT, stdio: "inherit" });
      console.log("Changes committed and pushed to remote.");
    } catch (e) {
      console.error("Git operation failed:", e.message);
      process.exit(1);
    }
  }
}

// ---------------------------------------------------------------------------
// Subcommand: check
// ---------------------------------------------------------------------------

function cmdCheck() {
  console.log("Version Consistency Check");
  console.log("==========================");
  console.log("");

  let allPassed = true;

  // 1. VERSION file
  const versionPath = join(REPO_ROOT, "VERSION");
  let versionFileVersion = "";
  if (existsSync(versionPath)) {
    versionFileVersion = readFileSync(versionPath, "utf-8").trim();
    checkItem("VERSION", "passed", versionFileVersion);
  } else {
    checkItem("VERSION", "error", "VERSION file not found");
    allPassed = false;
  }

  // 2. Root pom.xml <version> (project version, not parent)
  const pomPath = join(REPO_ROOT, "pom.xml");
  let pomVersion = "";
  if (existsSync(pomPath)) {
    let pomContent = readFileSync(pomPath, "utf-8");
    const pomWithoutParent = pomContent.replace(/<parent>[\s\S]*?<\/parent>/m, "");
    const pomVersionMatch = pomWithoutParent.match(/<version>([^<]+)<\/version>/);
    if (pomVersionMatch) {
      pomVersion = pomVersionMatch[1];
      if (pomVersion === versionFileVersion) {
        checkItem("pom.xml", "passed", pomVersion);
      } else {
        checkItem("pom.xml", "failed", `${pomVersion} (expected ${versionFileVersion})`);
        allPassed = false;
      }
    } else {
      checkItem("pom.xml", "error", "Cannot parse <version>");
      allPassed = false;
    }
  } else {
    checkItem("pom.xml", "error", "File not found");
    allPassed = false;
  }

  // 3. SNAPSHOT consistency
  const versionIsSnapshot = versionFileVersion.endsWith("-SNAPSHOT");
  const pomIsSnapshot = pomVersion.endsWith("-SNAPSHOT");
  if (pomVersion && versionIsSnapshot !== pomIsSnapshot) {
    checkItem("SNAPSHOT consistency", "failed", "VERSION and pom.xml disagree on SNAPSHOT status");
    allPassed = false;
  } else {
    checkItem("SNAPSHOT consistency", "passed", versionIsSnapshot ? "SNAPSHOT" : "RELEASE");
  }

  // 4. CLI: Cargo.toml version
  const cargoPath = join(REPO_ROOT, "cli", "browser4-cli", "Cargo.toml");
  if (existsSync(cargoPath)) {
    const cargoContent = readFileSync(cargoPath, "utf-8");
    const cargoMatch = cargoContent.match(/\[package\][\s\S]*?version\s*=\s*"([^"]+)"/);
    if (cargoMatch) {
      const cargoVersion = cargoMatch[1];
      if (cargoVersion.match(/^\d+\.\d+\.\d+/)) {
        checkItem("cli/Cargo.toml", "passed", `${cargoVersion} (independent CLI version)`);
      } else {
        checkItem("cli/Cargo.toml", "failed", `${cargoVersion} (not valid semver)`);
        allPassed = false;
      }

      // Also check against package.json
      const packageJsonPath = join(REPO_ROOT, "cli", "package.json");
      if (existsSync(packageJsonPath)) {
        const pj = JSON.parse(readFileSync(packageJsonPath, "utf-8"));
        if (pj.version === cargoVersion) {
          checkItem("cli/package.json", "passed", `${pj.version} (matches Cargo.toml)`);
        } else {
          checkItem("cli/package.json", "failed", `${pj.version} (expected ${cargoVersion})`);
          allPassed = false;
        }
      }
    } else {
      checkItem("cli/Cargo.toml", "error", "Cannot parse package.version");
      allPassed = false;
    }
  } else {
    checkItem("cli/Cargo.toml", "skipped", "File not found");
  }

  console.log("");
  if (allPassed) {
    console.log("✓ All version checks passed.");
  } else {
    console.log("✗ Some version checks failed.");
    process.exitCode = 1;
  }
}

// ---------------------------------------------------------------------------
// Main: parse subcommand
// ---------------------------------------------------------------------------

function printUsage() {
  console.log("Usage: node bin/version.mjs <command> [options]");
  console.log("");
  console.log("Browser4 has two independent version tracks:");
  console.log("");
  console.log("  Backend version (source: VERSION file → pom.xml, READMEs)");
  console.log("    show              Print backend version");
  console.log("    show -v           Print backend version + git hash, branch, date");
  console.log("    release           Strip -SNAPSHOT for release deployment");
  console.log("    bump <part>       Bump major/minor/patch, update pom.xml, commit");
  console.log("    bump <part> --dry-run    Show what would change without applying");
  console.log("    bump <part> --skip-precheck  Skip publish-status verification");
  console.log("    auto              Bump backend to next patch; bump CLI if cli/ changed");
  console.log("    auto --dry-run    Show what would change without applying");
  console.log("    auto --commit     Apply changes and commit+push");
  console.log("");
  console.log("  CLI version (source: cli/VERSION-CLI → package.json, Cargo.toml)");
  console.log("    cli show          Print CLI version");
  console.log("    cli sync          Sync VERSION-CLI to dependent files");
  console.log("    cli sync --check  Check-only mode (exit 1 if out of sync)");
  console.log("    cli auto          Bump CLI to next patch if changes detected in cli/");
  console.log("    cli auto --dry-run  Show what would change");
  console.log("");
  console.log("  Cross-cutting");
  console.log("    check             Check version consistency across all files");
}

const args = process.argv.slice(2);

// Only show top-level help when no command given, or when -h/--help is the
// first argument (before any subcommand).
if (args.length === 0 || args[0] === "-h" || args[0] === "--help") {
  printUsage();
  process.exit(0);
}

const command = args[0];
const rest = args.slice(1);

switch (command) {
  case "show":
    cmdShow(rest);
    break;
  case "cli":
    cmdCli(rest);
    break;
  case "release":
    cmdRelease();
    break;
  case "bump":
    await cmdBump(rest);
    break;
  case "auto":
    await cmdAuto(rest);
    break;
  case "check":
    cmdCheck();
    break;
  default:
    console.error(`Unknown command: ${command}`);
    console.error("");
    printUsage();
    process.exit(1);
}

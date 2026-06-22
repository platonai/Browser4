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
 *
 *   # CLI version (cli/VERSION-CLI file)
 *   node bin/version.mjs cli show          Print CLI version
 *   node bin/version.mjs cli sync          Sync VERSION-CLI → package.json, Cargo.toml
 *   node bin/version.mjs cli sync --check  Check-only mode (CI, exit 1 if mismatch)
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
  const m = version.match(/^(\d+)\.(\d+)\.(\d+)$/);
  if (!m) return null;
  return { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3]) };
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
    default:
      console.error(`Unknown CLI command: cli ${sub}`);
      console.error("Available: cli show, cli sync");
      process.exit(1);
  }
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

  let { major, minor, patch } = parsed;

  // Calculate next version
  switch (part) {
    case "major":
      major++;
      minor = 0;
      patch = 0;
      break;
    case "minor":
      minor++;
      patch = 0;
      break;
    case "patch":
      patch++;
      break;
  }
  const nextVersion = `${major}.${minor}.${patch}`;
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
  console.log("");
  console.log("  CLI version (source: cli/VERSION-CLI → package.json, Cargo.toml)");
  console.log("    cli show          Print CLI version");
  console.log("    cli sync          Sync VERSION-CLI to dependent files");
  console.log("    cli sync --check  Check-only mode (exit 1 if out of sync)");
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
  case "check":
    cmdCheck();
    break;
  default:
    console.error(`Unknown command: ${command}`);
    console.error("");
    printUsage();
    process.exit(1);
}

#!/usr/bin/env node

/**
 * Verifies that package.json, browser4-cli/Cargo.toml, and
 * packages/dashboard/package.json all match the project root VERSION file.
 *
 * The VERSION file at the repository root is the single source of truth.
 * Used in CI to catch version drift.
 */

import { existsSync, readFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const cliDir = join(__dirname, '..');

// Walk upward to find the project root VERSION file
function findVersionFile(startDir) {
  let dir = startDir;
  while (true) {
    const candidate = join(dir, 'VERSION');
    if (existsSync(candidate)) return candidate;
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

const versionPath = findVersionFile(cliDir);
if (!versionPath) {
  console.error('ERROR: Could not find VERSION file in any parent directory.');
  process.exit(1);
}

const repoRoot = dirname(versionPath);

// Read expected version from VERSION file (strip -SNAPSHOT)
const versionRaw = readFileSync(versionPath, 'utf-8').trim();
const expectedVersion = versionRaw.endsWith('-SNAPSHOT')
  ? versionRaw.slice(0, -'-SNAPSHOT'.length)
  : versionRaw;

console.log(`VERSION file (${versionPath}): ${expectedVersion}`);

// Check cli/package.json
const packageJson = JSON.parse(readFileSync(join(cliDir, 'package.json'), 'utf-8'));
const packageVersion = packageJson.version;

// Check browser4-cli/Cargo.toml
const cargoToml = readFileSync(join(cliDir, 'browser4-cli/Cargo.toml'), 'utf-8');
const cargoVersionMatch = cargoToml.match(/^version\s*=\s*"([^"]*)"/m);
if (!cargoVersionMatch) {
  console.error('ERROR: Could not find version in browser4-cli/Cargo.toml');
  process.exit(1);
}
const cargoVersion = cargoVersionMatch[1];

// Check packages/dashboard/package.json (if it exists)
let dashboardVersion = null;
const dashboardPkgPath = join(repoRoot, 'packages/dashboard/package.json');
if (existsSync(dashboardPkgPath)) {
  const dashboardPkg = JSON.parse(readFileSync(dashboardPkgPath, 'utf-8'));
  dashboardVersion = dashboardPkg.version;
}

// Report mismatches
const mismatches = [];

if (packageVersion !== expectedVersion) {
  mismatches.push(`  cli/package.json:                    ${packageVersion}`);
}
if (cargoVersion !== expectedVersion) {
  mismatches.push(`  cli/browser4-cli/Cargo.toml:         ${cargoVersion}`);
}
if (dashboardVersion !== null && dashboardVersion !== expectedVersion) {
  mismatches.push(`  packages/dashboard/package.json:     ${dashboardVersion}`);
}

if (mismatches.length > 0) {
  console.error('');
  console.error('Version mismatch detected against VERSION file!');
  console.error(`  Expected (from VERSION):             ${expectedVersion}`);
  for (const m of mismatches) console.error(m);
  console.error('');
  console.error("Run 'node cli/scripts/sync-version.js' to fix.");
  process.exit(1);
}

console.log(`All versions in sync: ${expectedVersion}`);

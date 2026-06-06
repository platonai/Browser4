#!/usr/bin/env node

import { execSync } from 'child_process';
import { readFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
export const cliRootDir = join(__dirname, '..');
const packageJsonPath = join(cliRootDir, 'package.json');

/**
 * Reads browser4-cli package metadata.
 *
 * @returns {{name: string, version: string}}
 */
export function readPackageMetadata() {
  const packageJson = JSON.parse(readFileSync(packageJsonPath, 'utf-8'));
  return {
    name: packageJson.name,
    version: packageJson.version,
  };
}

/**
 * Queries npm for the published version of the package.
 *
 * @param {string} packageName Package name to inspect.
 * @returns {{status: string, version: string}}
 */
export function getRemoteVersion(packageName) {
  const overriddenVersion = process.env.BROWSER4_CLI_NPM_REMOTE_VERSION;
  if (overriddenVersion) {
    console.log(`Using overridden npm version from BROWSER4_CLI_NPM_REMOTE_VERSION=${overriddenVersion}`);
    return {
      status: 'overridden',
      version: overriddenVersion,
    };
  }

  try {
    const version = execSync(`npm view "${packageName}" version`, {
      cwd: cliRootDir,
      stdio: ['ignore', 'pipe', 'pipe'],
      encoding: 'utf-8',
    }).trim();

    return {
      status: 'success',
      version,
    };
  } catch (error) {
    const stderr = error.stderr?.toString().trim();
    const stdout = error.stdout?.toString().trim();
    const message = stderr || stdout || error.message;

    console.warn(`Warning: unable to query npm version for ${packageName}: ${message}`);
    return {
      status: 'failed',
      version: 'unknown',
    };
  }
}

/**
 * Computes whether npm publish should proceed.
 *
 * @returns {{packageName: string, cliVersion: string, remoteVersion: string, lookupStatus: string, shouldPublish: boolean}}
 */
export function getPublishDecision() {
  const metadata = readPackageMetadata();
  const remoteInfo = getRemoteVersion(metadata.name);

  return {
    packageName: metadata.name,
    cliVersion: metadata.version,
    remoteVersion: remoteInfo.version,
    lookupStatus: remoteInfo.status,
    shouldPublish: remoteInfo.version !== 'unknown' && metadata.version !== remoteInfo.version,
  };
}

/**
 * Logs a human-readable publish decision.
 *
 * @param {string} phase Current phase label.
 * @param {{packageName: string, cliVersion: string, remoteVersion: string, lookupStatus: string, shouldPublish: boolean}} decision Publish decision.
 */
export function logPublishDecision(phase, decision) {
  console.log(
    `[${phase}] package=${decision.packageName} local=${decision.cliVersion} remote=${decision.remoteVersion} status=${decision.lookupStatus} shouldPublish=${decision.shouldPublish}`
  );
}


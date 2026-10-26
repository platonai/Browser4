#!/usr/bin/env node

/**
 * Publishes browser4-cli only when the local package version differs from npm.
 *
 * Usage:
 *   node scripts/publish-if-needed.js
 *   node scripts/publish-if-needed.js --dry-run
 *
 * Optional env for testing:
 *   BROWSER4_CLI_NPM_REMOTE_VERSION=<version>
 */

import { execSync } from 'child_process';
import { cliRootDir, getPublishDecision, logPublishDecision } from './npm-publish-check.js';

const args = new Set(process.argv.slice(2));
const isDryRun = args.has('--dry-run');

function runCommand(command) {
  console.log(`> ${command}`);
  execSync(command, {
    cwd: cliRootDir,
    stdio: 'inherit',
  });
}

function main() {
  const initialDecision = getPublishDecision();
  logPublishDecision('pre-check', initialDecision);

  if (!initialDecision.shouldPublish) {
    console.log(`Skipping publish because ${initialDecision.packageName}@${initialDecision.cliVersion} already exists on npm.`);
    return;
  }

  if (isDryRun) {
    console.log('Dry run enabled; version differs, so publish would proceed.');
    return;
  }

  runCommand('npm run version:sync');

  const postSyncDecision = getPublishDecision();
  logPublishDecision('post-sync-check', postSyncDecision);

  if (!postSyncDecision.shouldPublish) {
    console.log(`Skipping publish after sync because ${postSyncDecision.packageName}@${postSyncDecision.cliVersion} already exists on npm.`);
    return;
  }

  runCommand('npm run build:all-platforms');

  const prePublishDecision = getPublishDecision();
  logPublishDecision('pre-publish-check', prePublishDecision);

  if (!prePublishDecision.shouldPublish) {
    console.log(`Skipping publish before npm publish because ${prePublishDecision.packageName}@${prePublishDecision.cliVersion} already exists on npm.`);
    return;
  }

  const isPrerelease = prePublishDecision.cliVersion.includes('-');
  const tagFlag = isPrerelease ? '--tag next' : '';
  runCommand(`npm publish ${tagFlag}`);

  // Keep `next` from sticking to the last prerelease: a stable publish to
  // `latest` must move it forward too. Delegate to the same script release.yml
  // runs, so there is one implementation of "only move forward, never point a
  // tag at a version that is not on npm".
  if (!isPrerelease) {
    if (hasBash()) {
      runCommand(
        `bash scripts/sync-npm-dist-tag.sh --package ${prePublishDecision.packageName} --version ${prePublishDecision.cliVersion}`
      );
    } else {
      console.log(
        `Skipping npm dist-tag sync for 'next': bash is not available on this machine. ` +
        `Move it with scripts/sync-npm-dist-tag.sh in Git Bash, or run the "npm Dist-Tag" workflow ` +
        `(gh workflow run npm-dist-tag.yml -f version=${prePublishDecision.cliVersion}).`
      );
    }
  }
}

/**
 * Whether a bash shell is available for the dist-tag sync helper.
 *
 * @returns {boolean}
 */
function hasBash() {
  try {
    execSync('bash --version', { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

main();


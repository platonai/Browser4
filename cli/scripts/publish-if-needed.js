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

  runCommand('npm publish');
}

main();


#!/usr/bin/env node

import { appendFileSync } from 'fs';
import { getPublishDecision, logPublishDecision } from './npm-publish-check.js';

const args = new Set(process.argv.slice(2));
const shouldWriteGithubOutput = args.has('--github-output');
const shouldPrintJson = args.has('--json');
const shouldPrintJsonOnly = args.has('--json-only');
const shouldPrintShell = args.has('--shell');
const decision = getPublishDecision();

if (!shouldPrintJsonOnly && !shouldPrintShell) {
  logPublishDecision('check', decision);
}

if (shouldPrintJson) {
  console.log(JSON.stringify(decision));
}

if (shouldPrintJsonOnly) {
  console.log(JSON.stringify(decision));
}

if (shouldPrintShell) {
  console.log(`PACKAGE_NAME='${decision.packageName}'`);
  console.log(`CLI_VERSION='${decision.cliVersion}'`);
  console.log(`REMOTE_VERSION='${decision.remoteVersion}'`);
  console.log(`LOOKUP_STATUS='${decision.lookupStatus}'`);
  console.log(`SHOULD_PUBLISH='${decision.shouldPublish ? 'true' : 'false'}'`);
}

if (shouldWriteGithubOutput) {
  const githubOutputPath = process.env.GITHUB_OUTPUT;
  if (!githubOutputPath) {
    console.error('GITHUB_OUTPUT is required when using --github-output');
    process.exit(1);
  }

  appendFileSync(
    githubOutputPath,
    [
      `package_name=${decision.packageName}`,
      `cli_version=${decision.cliVersion}`,
      `remote_version=${decision.remoteVersion}`,
      `lookup_status=${decision.lookupStatus}`,
      `should_publish=${decision.shouldPublish ? 'true' : 'false'}`,
      '',
    ].join('\n')
  );
}

if (!decision.shouldPublish && !shouldPrintJsonOnly && !shouldPrintShell) {
  console.log(`Skipping publish because ${decision.packageName}@${decision.cliVersion} already exists on npm.`);
}


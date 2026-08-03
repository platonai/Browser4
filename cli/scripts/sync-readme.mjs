#!/usr/bin/env node

import { copyFileSync, existsSync, unlinkSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const cliRootDir = resolve(__dirname, '..');
const repoRootDir = resolve(cliRootDir, '..');
const rootReadme = resolve(repoRootDir, 'README.md');
const cliReadme = resolve(cliRootDir, 'README.md');
const backupReadme = resolve(cliRootDir, '.README.prepack.backup.md');

function syncReadme() {
  if (!existsSync(backupReadme)) {
    copyFileSync(cliReadme, backupReadme);
  }
  copyFileSync(rootReadme, cliReadme);
  console.log(`Synced ${rootReadme} -> ${cliReadme}`);
}

function restoreReadme() {
  if (!existsSync(backupReadme)) {
    console.log(`No README backup found at ${backupReadme}; nothing to restore.`);
    return;
  }

  copyFileSync(backupReadme, cliReadme);
  unlinkSync(backupReadme);
  console.log(`Restored ${cliReadme} from ${backupReadme}`);
}

const mode = process.argv[2] ?? 'sync';

if (mode === 'sync') {
  syncReadme();
} else if (mode === 'restore') {
  restoreReadme();
} else {
  console.error(`Unsupported mode: ${mode}. Use "sync" or "restore".`);
  process.exit(1);
}

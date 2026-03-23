#!/usr/bin/env node
/**
 * browser4-cli entry point.
 *
 * Provides a thin wrapper around the Browser4 REST/MCP API so that AI agents
 * and automation scripts can drive a Browser4 server from the command line.
 */

import { Command } from 'commander';
import { Browser4Client } from './client';
import { VERSION } from './version';

const program = new Command();

program
  .name('browser4')
  .description('Command-line interface for the Browser4 browser automation platform')
  .version(VERSION);

// ─── Global options ───────────────────────────────────────────────────────────

program
  .option('--base-url <url>', 'Browser4 server base URL', 'http://localhost:8182')
  .option('--session-id <id>', 'Re-use an existing Browser4 session');

// ─── session ──────────────────────────────────────────────────────────────────

const sessionCmd = program.command('session').description('Manage Browser4 sessions');

sessionCmd
  .command('create')
  .description('Create a new browser session and print its ID')
  .action(async () => {
    const opts = program.opts();
    const client = new Browser4Client({ baseUrl: opts.baseUrl });
    const id = await client.createSession();
    console.log(id);
  });

sessionCmd
  .command('close')
  .description('Close an existing browser session')
  .argument('<sessionId>', 'Session ID to close')
  .action(async (sessionId: string) => {
    const opts = program.opts();
    const client = new Browser4Client({ baseUrl: opts.baseUrl });
    await client.closeSession(sessionId);
    console.log(`Session ${sessionId} closed.`);
  });

// ─── tool ─────────────────────────────────────────────────────────────────────

program
  .command('tool')
  .description('Call an MCP tool on the Browser4 server')
  .argument('<toolName>', 'MCP tool name (e.g. navigate, click, aria_snapshot)')
  .argument('[args...]', 'Tool arguments as key=value pairs')
  .action(async (toolName: string, rawArgs: string[]) => {
    const opts = program.opts();
    const client = new Browser4Client({
      baseUrl: opts.baseUrl,
      sessionId: opts.sessionId
    });

    const args: Record<string, unknown> = {};
    for (const pair of rawArgs) {
      const eq = pair.indexOf('=');
      if (eq === -1) {
        args[pair] = true;
      } else {
        args[pair.slice(0, eq)] = pair.slice(eq + 1);
      }
    }

    const result = await client.callTool(toolName, args);
    console.log(JSON.stringify(result, null, 2));
    if (!result.success) {
      process.exitCode = 1;
    }
  });

// ─── Parse ────────────────────────────────────────────────────────────────────

program.parseAsync(process.argv).catch((err: unknown) => {
  console.error('Error:', err instanceof Error ? err.message : err);
  process.exitCode = 1;
});

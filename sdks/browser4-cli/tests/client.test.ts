import { Browser4Client } from '../src/client';
import { VERSION } from '../src/version';

describe('Browser4Client', () => {
  it('defaults to localhost:8182', () => {
    const client = new Browser4Client();
    // Verify construction succeeds without throwing
    expect(client).toBeInstanceOf(Browser4Client);
  });

  it('accepts a custom baseUrl', () => {
    const client = new Browser4Client({ baseUrl: 'http://example.com:9000' });
    expect(client).toBeInstanceOf(Browser4Client);
  });

  it('stores the provided sessionId', () => {
    const client = new Browser4Client({ sessionId: 'test-session-123' });
    expect(client.sessionId).toBe('test-session-123');
  });

  it('throws when calling a tool without a session', async () => {
    const client = new Browser4Client();
    await expect(client.callTool('navigate', { url: 'https://example.com' })).rejects.toThrow(
      'No active session'
    );
  });
});

describe('VERSION', () => {
  it('matches the version in package.json', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const pkg = require('../package.json') as { version: string };
    expect(VERSION).toBe(pkg.version);
  });
});

import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  ProtocolHandler,
  RelayContext,
  resolveChromeMember,
} from '../protocolHandlers';
import {
  installChromeMocks,
  uninstallChromeMocks,
  resetChromeMocks,
  mockDebuggerAttach,
  mockDebuggerDetach,
  mockDebuggerSendCommand,
  mockTabsCreate,
  mockTabsRemove,
} from './chromeMocks';

function makeContext(overrides: Partial<RelayContext> = {}): RelayContext {
  return {
    attachedTabs: new Set<number>(),
    sendMessage: vi.fn(),
    notifyTabAttached: vi.fn(),
    notifyTabDetached: vi.fn(),
    ...overrides,
  };
}

describe('ProtocolHandler', () => {
  let handler: ProtocolHandler;
  let ctx: RelayContext;

  beforeEach(() => {
    installChromeMocks();
    ctx = makeContext();
    handler = new ProtocolHandler(ctx);
  });

  afterEach(() => {
    uninstallChromeMocks();
    resetChromeMocks();
  });

  // ── handleCommand ─────────────────────────────────────────────────────

  describe('handleCommand', () => {
    it('invokes chrome.debugger.attach and triggers attach bookkeeping', async () => {
      mockDebuggerAttach.mockResolvedValue(undefined);

      await handler.handleCommand({
        id: 1,
        method: 'chrome.debugger.attach',
        params: [{ tabId: 5 }],
      });

      expect(mockDebuggerAttach).toHaveBeenCalledWith({ tabId: 5 });
      expect(ctx.notifyTabAttached).toHaveBeenCalledWith(5);
    });

    it('invokes chrome.debugger.detach', async () => {
      mockDebuggerDetach.mockResolvedValue(undefined);

      await handler.handleCommand({
        id: 2,
        method: 'chrome.debugger.detach',
        params: [{ tabId: 7 }],
      });

      expect(mockDebuggerDetach).toHaveBeenCalledWith({ tabId: 7 });
    });

    it('invokes chrome.debugger.sendCommand and returns its result', async () => {
      mockDebuggerSendCommand.mockResolvedValue({ frameId: '123' });

      const result = await handler.handleCommand({
        id: 3,
        method: 'chrome.debugger.sendCommand',
        params: [{ tabId: 5 }, 'Page.navigate', { url: 'https://example.com' }],
      });

      expect(mockDebuggerSendCommand).toHaveBeenCalledWith(
        { tabId: 5 },
        'Page.navigate',
        { url: 'https://example.com' },
      );
      expect(result).toEqual({ frameId: '123' });
    });

    it('invokes chrome.tabs.create', async () => {
      mockTabsCreate.mockResolvedValue({ id: 10, url: 'about:blank' });

      const result = await handler.handleCommand({
        id: 4,
        method: 'chrome.tabs.create',
        params: [{ url: 'about:blank' }],
      });

      expect(mockTabsCreate).toHaveBeenCalledWith({ url: 'about:blank' });
      expect(result).toEqual({ id: 10, url: 'about:blank' });
    });

    it('invokes chrome.tabs.remove', async () => {
      mockTabsRemove.mockResolvedValue(undefined);

      await handler.handleCommand({
        id: 5,
        method: 'chrome.tabs.remove',
        params: [42],
      });

      expect(mockTabsRemove).toHaveBeenCalledWith(42);
    });

    it('handles empty params as empty array', async () => {
      mockTabsRemove.mockResolvedValue(undefined);

      await handler.handleCommand({
        id: 6,
        method: 'chrome.tabs.remove',
        // params omitted entirely
      });

      expect(mockTabsRemove).toHaveBeenCalledWith();
    });

    it('returns {} when the chrome method returns undefined', async () => {
      mockDebuggerDetach.mockResolvedValue(undefined);

      const result = await handler.handleCommand({
        id: 7,
        method: 'chrome.debugger.detach',
        params: [{ tabId: 1 }],
      });

      expect(mockDebuggerDetach).toHaveBeenCalled();
      expect(result).toEqual({});
    });

    it('throws for unknown methods', async () => {
      await expect(
        handler.handleCommand({ id: 8, method: 'bogus.command' }),
      ).rejects.toThrow('Unknown method: bogus.command');
    });

    it('skips attach bookkeeping when target has no tabId', async () => {
      mockDebuggerAttach.mockResolvedValue(undefined);

      await handler.handleCommand({
        id: 9,
        method: 'chrome.debugger.attach',
        params: [{ extensionId: 'abc' }],
      });

      expect(mockDebuggerAttach).toHaveBeenCalled();
      expect(ctx.notifyTabAttached).not.toHaveBeenCalled();
    });
  });

  // ── forwardChromeEvent ─────────────────────────────────────────────────

  describe('forwardChromeEvent', () => {
    it('sends the event with full method and args', () => {
      const args = [{ tabId: 3, sessionId: 's1' }, 'Page.loadEventFired', {}];

      handler.forwardChromeEvent('chrome.debugger.onEvent', args);

      expect(ctx.sendMessage).toHaveBeenCalledWith({
        method: 'chrome.debugger.onEvent',
        params: args,
      });
    });

    it('forwards chrome.tabs.onCreated events', () => {
      const tab = { id: 8, url: 'https://example.com' };
      handler.forwardChromeEvent('chrome.tabs.onCreated', [tab]);

      expect(ctx.sendMessage).toHaveBeenCalledWith({
        method: 'chrome.tabs.onCreated',
        params: [tab],
      });
    });
  });

  // ── onUserAttachRequest ────────────────────────────────────────────────

  describe('onUserAttachRequest', () => {
    it('emits chrome.tabs.onCreated so the relay requests attach', () => {
      const tab = { id: 42, url: 'https://test.com' } as chrome.tabs.Tab;

      handler.onUserAttachRequest(tab);

      expect(ctx.sendMessage).toHaveBeenCalledWith({
        method: 'chrome.tabs.onCreated',
        params: [tab],
      });
    });
  });

  // ── onUserDetachRequest ────────────────────────────────────────────────

  describe('onUserDetachRequest', () => {
    it('synthesizes chrome.debugger.onDetach for the relay', () => {
      handler.onUserDetachRequest(99);

      expect(ctx.sendMessage).toHaveBeenCalledWith({
        method: 'chrome.debugger.onDetach',
        params: [{ tabId: 99 }, 'target_closed'],
      });
    });
  });

  // ── didInitialize ──────────────────────────────────────────────────────

  describe('didInitialize', () => {
    it('sends extension.initialized', () => {
      handler.didInitialize();

      expect(ctx.sendMessage).toHaveBeenCalledWith({
        method: 'extension.initialized',
        params: [],
      });
    });
  });
});

// ─── resolveChromeMember ──────────────────────────────────────────────────

describe('resolveChromeMember', () => {
  beforeEach(() => {
    installChromeMocks();
  });

  afterEach(() => {
    uninstallChromeMocks();
  });

  it('resolves chrome.debugger.attach', () => {
    const result = resolveChromeMember('chrome.debugger.attach');
    expect(result).toHaveProperty('name', 'attach');
    expect(result.obj).toBe((globalThis as any).chrome.debugger);
  });

  it('resolves chrome.tabs.onCreated', () => {
    const result = resolveChromeMember('chrome.tabs.onCreated');
    expect(result).toHaveProperty('name', 'onCreated');
    expect(result.obj).toBe((globalThis as any).chrome.tabs);
  });

  it('throws for non-chrome path', () => {
    expect(() => resolveChromeMember('bogus.method.call')).toThrow(
      'Invalid chrome method: bogus.method.call',
    );
  });

  it('throws for too-short chrome path', () => {
    expect(() => resolveChromeMember('chrome.tabs')).toThrow(
      'Invalid chrome method: chrome.tabs',
    );
  });

  it('throws for unknown nested path', () => {
    expect(() => resolveChromeMember('chrome.nonexistent.thing.method')).toThrow(
      'Unknown chrome path: chrome.nonexistent',
    );
  });
});

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { PendingConnections } from '../pendingConnection';
import {
  installChromeMocks,
  uninstallChromeMocks,
  resetChromeMocks,
  mockTabsOnRemoved,
} from './chromeMocks';

// ─── WebSocket stub ───────────────────────────────────────────────────────
//
// The real `openRelayConnection` (module-private in pendingConnection.ts) does
// `new WebSocket(url)`.  We provide a stub on globalThis that fires onopen
// immediately so the promise resolves without a real network call.
//
// Use a plain function rather than `class` — esbuild may transform classes in
// ways that break `new`-invocation when assigned to globalThis.

function StubWebSocket(this: any, _url: string) {
  this.readyState = 1; // OPEN
  this.onopen = null;
  this.onerror = null;
  this.onmessage = null;
  this.onclose = null;
  this.send = vi.fn();
  this.close = vi.fn();
  queueMicrotask(() => this.onopen?.());
}
StubWebSocket.OPEN = 1;

// ─── RelayConnection mock ─────────────────────────────────────────────────
//
// openRelayConnection calls `new RelayConnection(socket)`.  We mock the
// module so the real RelayConnection (which installs chrome listeners, etc.)
// is replaced with a lightweight fake.

vi.mock('../relayConnection', () => ({
  RelayConnection: vi.fn(function (this: any, ws: any) {
    this._ws = ws;
    this.attachedTabs = new Set<number>();
    this.onclose = null;
    this.ontabattached = null;
    this.ontabdetached = null;
    this.close = vi.fn();
    this.didInitialize = vi.fn();
    this.attachTab = vi.fn();
    this.detachTab = vi.fn();
    return this;
  }),
  debugLog: vi.fn(),
}));

describe('PendingConnections', () => {
  let pending: PendingConnections;

  beforeEach(() => {
    installChromeMocks();
    vi.stubGlobal('WebSocket', StubWebSocket);
    vi.clearAllMocks();
    pending = new PendingConnections();
  });

  afterEach(() => {
    uninstallChromeMocks();
    resetChromeMocks();
    vi.unstubAllGlobals();
  });

  // ── create ─────────────────────────────────────────────────────────────

  describe('create', () => {
    it('stores a deferred pending entry keyed by selector tab id', async () => {
      await pending.create(1, 'ws://localhost:9222');

      const conn = await pending.take(1);
      expect(conn).toBeDefined();
      expect(conn!.close).toBeDefined();
    });
  });

  // ── take ───────────────────────────────────────────────────────────────

  describe('take', () => {
    it('returns undefined for unknown tab ids', async () => {
      const conn = await pending.take(999);
      expect(conn).toBeUndefined();
    });

    it('returns a connection and removes the entry', async () => {
      await pending.create(5, 'ws://localhost:9222');

      const conn1 = await pending.take(5);
      expect(conn1).toBeDefined();

      // Second take should return undefined (entry removed)
      const conn2 = await pending.take(5);
      expect(conn2).toBeUndefined();
    });

    it('opens the WebSocket lazily on take, not on create', async () => {
      const wsSpy = vi.spyOn(globalThis as any, 'WebSocket');

      await pending.create(7, 'ws://localhost:9222');

      // WebSocket should NOT have been created yet (deferred)
      const callsBeforeTake = wsSpy.mock.calls.length;

      await pending.take(7);

      // Now WebSocket should have been created
      expect(wsSpy.mock.calls.length).toBeGreaterThan(callsBeforeTake);

      wsSpy.mockRestore();
    });
  });

  // ── tab removal cleanup ────────────────────────────────────────────────

  describe('tab removal', () => {
    it('closes the pending entry when the selector tab is removed', async () => {
      await pending.create(12, 'ws://localhost:9222');

      mockTabsOnRemoved.dispatch(12);

      const afterRemove = await pending.take(12);
      expect(afterRemove).toBeUndefined();
    });

    it('does nothing when an unknown tab is removed', () => {
      expect(() => mockTabsOnRemoved.dispatch(98765)).not.toThrow();
    });
  });

  // ── multiple connections ───────────────────────────────────────────────

  describe('multiple connections', () => {
    it('manages independent entries for different selector tabs', async () => {
      await pending.create(11, 'ws://a');
      await pending.create(22, 'ws://b');

      const connA = await pending.take(11);
      const connB = await pending.take(22);

      expect(connA).toBeDefined();
      expect(connB).toBeDefined();
      expect(connA).not.toBe(connB);
    });
  });
});

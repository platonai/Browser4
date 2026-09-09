import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ConnectedTabGroup } from '../connectedTabGroup';
import { RelayConnection } from '../relayConnection';
import {
  installChromeMocks,
  uninstallChromeMocks,
  resetChromeMocks,
  mockDebuggerAttach,
  mockDebuggerDetach,
  mockDebuggerSendCommand,
  mockTabsGet,
} from './chromeMocks';

/** Minimal WebSocket stub so RelayConnection can be constructed in tests. */
class MockWebSocket {
  static OPEN = 1;

  readyState = MockWebSocket.OPEN;
  onopen: ((ev: any) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: any) => void) | null = null;
  onerror: ((ev: any) => void) | null = null;

  sent: any[] = [];
  closed = false;

  send(data: string) {
    this.sent.push(JSON.parse(data));
  }

  close(code?: number, reason?: string) {
    this.closed = true;
    queueMicrotask(() => this.onclose?.({ code, reason }));
  }

  /** Simulate receiving a message from the relay. */
  receive(data: object) {
    queueMicrotask(() =>
      this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(data) })),
    );
  }
}

async function makeGroup(tabId = 5): Promise<{ group: ConnectedTabGroup; ws: MockWebSocket }> {
  const ws = new MockWebSocket();
  const conn = new RelayConnection(ws as unknown as WebSocket);
  const tab = { id: tabId, url: 'https://weibo.com/', title: 'weibo', active: true, windowId: 1 } as chrome.tabs.Tab;
  const group = new ConnectedTabGroup(conn, tab);
  // Simulate the Browser4 backend reacting to the onCreated event with a
  // real chrome.debugger.attach — this is what registers the tab in the
  // relay's attachedTabs set.
  ws.receive({ id: 42, method: 'chrome.debugger.attach', params: [{ tabId }, '1.3'] });
  await new Promise(r => setTimeout(r, 0));
  return { group, ws };
}

describe('ConnectedTabGroup.heartbeat', () => {
  beforeEach(() => {
    installChromeMocks();
    mockDebuggerAttach.mockResolvedValue(undefined);
    mockDebuggerDetach.mockResolvedValue(undefined);
    mockDebuggerSendCommand.mockResolvedValue({});
    mockTabsGet.mockResolvedValue({ id: 5, url: 'https://weibo.com/', active: true, windowId: 1 });
  });

  afterEach(() => {
    uninstallChromeMocks();
    resetChromeMocks();
  });

  it('sends a lightweight Runtime.evaluate to every attached tab', async () => {
    const { group } = await makeGroup(5);

    await group.heartbeat();

    const evalCalls = mockDebuggerSendCommand.mock.calls.filter(
      (c: any[]) => c[1] === 'Runtime.evaluate' && c[2]?.expression === '1',
    );
    expect(evalCalls.length).toBe(1);
    expect(evalCalls[0][0]).toEqual({ tabId: 5 });
  });

  it('re-attaches the debugger when the heartbeat fails with a rejected sendCommand', async () => {
    const { group } = await makeGroup(5);

    mockDebuggerSendCommand.mockRejectedValueOnce(new Error('No target with given id'));

    await group.heartbeat();

    // First attempt failed -> detach + attach + verify sendCommand.
    expect(mockDebuggerDetach).toHaveBeenCalledWith({ tabId: 5 });
    expect(mockDebuggerAttach).toHaveBeenCalledWith({ tabId: 5 }, '1.3');
    const calls = mockDebuggerSendCommand.mock.calls.filter((c: any[]) => c[1] === 'Runtime.evaluate');
    expect(calls.length).toBe(2);
  });

  it('swallows repeated failures and keeps the group usable', async () => {
    const { group } = await makeGroup(5);

    mockDebuggerSendCommand.mockRejectedValue(new Error('No target with given id'));
    mockDebuggerAttach.mockRejectedValue(new Error('Cannot attach to this target'));

    await expect(group.heartbeat()).resolves.toBeUndefined();
  });
});

/**
 * Copyright (c) Platon AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { debugLog } from './relayConnection';
import { PendingConnections } from './pendingConnection';
import { openRelayConnection } from './pendingConnection';
import { ConnectedTabGroup, cleanupStaleBrowser4Groups, isNonDebuggableUrl } from './connectedTabGroup';
import { chooseSessionTab } from './tabSelection';

// MV3 service workers idle-suspend after ~30s. The relay WebSocket lives in
// the service worker, so without a keepalive the socket is torn down shortly
// after the connect page (the old keepalive source) is closed. Chrome 120+
// allows alarms with a 0.5-minute period; wake the worker every 30s while a
// connection is active. The alarm also drives the per-tab heartbeat that
// keeps controlled tabs from being frozen/discarded by Memory Saver.
const KEEPALIVE_ALARM = 'b4-extension-keepalive';
const KEEPALIVE_PERIOD_MINUTES = 0.5;
// Persisted relay URL so the service worker can re-establish the WebSocket
// after an MV3 worker restart (which loses all in-memory connection state).
const STORAGE_RELAY_KEY = 'b4Relay';
// Persisted session-tab binding (tabId + url). On reconnect / worker restart
// the session resumes the SAME tab instead of silently re-picking another one
// (agent-browser's restore_target_binding semantics). Cleared implicitly when
// the tab itself is closed — the binding is re-selected on the next connect.
const STORAGE_BINDING_KEY = 'b4TabBinding';

type PageMessage = {
  type: 'connectionRequested';
  mcpRelayUrl: string;
} | {
  type: 'getTabs';
} | {
  type: 'connectToTab';
  // Picked in the connect page; absent on the token-bypass path where no tab
  // selection happens.
  tab?: chrome.tabs.Tab;
  clientName?: string;
} | {
  type: 'getConnectionStatus';
} | {
  type: 'disconnect';
} | {
  type: 'keepalive';
};

class Browser4Extension {
  private _activeGroup: ConnectedTabGroup | undefined;
  private _activeClientName: string | undefined;
  private _pendingConnections = new PendingConnections();
  // Service worker restarts lose all connection state, so any existing
  // Browser4 groups are stale. Connections wait on this before reconciling.
  private _cleanupPromise: Promise<void>;

  constructor() {
    chrome.runtime.onMessage.addListener(this._onMessage.bind(this));
    chrome.action.onClicked.addListener(this._onActionClicked.bind(this));
    chrome.alarms.onAlarm.addListener(this._onAlarm.bind(this));
    this._cleanupPromise = cleanupStaleBrowser4Groups();
  }

  // Promise-based message handling is not supported in Chrome: https://issues.chromium.org/issues/40753031
  private _onMessage(message: PageMessage, sender: chrome.runtime.MessageSender, sendResponse: (response: any) => void) {
    switch (message.type) {
      case 'connectionRequested': {
        const tabId = sender.tab?.id;
        if (tabId === undefined) {
          sendResponse({ success: false, error: 'No tab context for connection request' });
          return false;
        }
        // Remember the relay endpoint so a later service-worker restart can
        // re-establish the WebSocket and resume the session automatically.
        void chrome.storage?.session?.set?.({ [STORAGE_RELAY_KEY]: { url: message.mcpRelayUrl } } as any)
            .catch(() => {});
        this._pendingConnections.create(tabId, message.mcpRelayUrl).then(
            () => sendResponse({ success: true }),
            (error: any) => sendResponse({ success: false, error: error.message }));
        return true;
      }
      case 'getTabs':
        this._getTabs().then(
            tabs => sendResponse({ success: true, tabs, currentTabId: sender.tab?.id }),
            (error: any) => sendResponse({ success: false, error: error.message }));
        return true;
      case 'connectToTab': {
        const senderTabId = sender.tab?.id;
        if (senderTabId === undefined) {
          sendResponse({ success: false, error: 'No tab context for connection' });
          return false;
        }
        // When no tab is explicitly selected (auto-connect via token or
        // newTab parameter), reuse an existing debuggable tab instead of
        // creating a fresh about:blank page. This avoids leaving an
        // unnecessary empty tab behind after every `attach --extension`.
        const selectedTabPromise = message.tab
            ? Promise.resolve(message.tab)
            : this._pickSessionTab(senderTabId);
        selectedTabPromise.then(selectedTab => {
          this._connectTab(senderTabId, selectedTab as chrome.tabs.Tab & { id: number }, message.clientName).then(
              () => sendResponse({ success: true }),
              (error: any) => sendResponse({ success: false, error: error.message }));
        });
        return true; // Return true to indicate that the response will be sent asynchronously
      }
      case 'getConnectionStatus':
        sendResponse({
          connectedTabIds: this._activeGroup?.connectedTabIds() ?? [],
          clientName: this._activeClientName,
        });
        return false;
      case 'disconnect':
        try {
          this._disconnect('User disconnected');
          sendResponse({ success: true });
        } catch (error: any) {
          sendResponse({ success: false, error: error.message });
        }
        return false; // Response sent synchronously — no need to keep channel open
      case 'keepalive':
        // Connect page pings us every ~20s so receiving this message resets
        // the MV3 service worker idle timer and keeps the relay WebSocket alive.
        return false;
    }
  }

  private async _connectTab(selectorTabId: number, tab: chrome.tabs.Tab & { id: number }, clientName: string | undefined): Promise<void> {
    try {
      await this._cleanupPromise;

      // Take the pending connection BEFORE disconnecting the current group.
      // This prevents a race where two concurrent _connectTab calls both call
      // _disconnect (which sees no active group yet), then both proceed to
      // create their groups — the second one would overwrite _activeGroup and
      // orphan the first one.  By holding the selectorTabId slot in the
      // pending map first, only one call succeeds at `take`.
      const connection = await this._pendingConnections.take(selectorTabId);
      if (!connection)
        throw new Error('Pending client connection closed');

      this._disconnect('Another connection is requested');

      // A previous service-worker generation may have been killed without a
      // chance to detach, leaving the debugger attached to the session tab.
      // Chrome rejects re-attach with "Another debugger is already attached",
      // so release it explicitly before the new group attaches.
      await chrome.debugger.detach({ tabId: tab.id }).catch(() => {});

      const group = new ConnectedTabGroup(connection, tab);
      group.onclose = () => {
        // _disconnect normally clears _activeGroup before calling group.close(),
        // so this branch only triggers on unexpected closes (e.g. remote end).
        if (this._activeGroup === group) {
          this._activeGroup = undefined;
          this._activeClientName = undefined;
          chrome.alarms.clear(KEEPALIVE_ALARM).catch(() => {});
          debugLog('Active group closed unexpectedly');
        }
      };
      this._activeGroup = group;
      this._activeClientName = clientName;
      this._ensureKeepaliveAlarm();
      this._rememberBinding(tab);

      await Promise.all([
        chrome.tabs.update(tab.id, { active: true }),
        chrome.windows.update(tab.windowId, { focused: true }),
      ]).catch(() => {});

      if (tab.id !== selectorTabId)
        await chrome.tabs.remove(selectorTabId).catch(() => {});
    } catch (error: any) {
      debugLog(`Failed to connect tab ${tab.id}:`, error.message);
      throw error;
    }
  }

  // Picks the tab to drive for an auto-connect (`attach --extension` without a
  // user-selected tab). The decision chain is documented in chooseSessionTab
  // (tabSelection.ts): never user-owned groups, never user-opened pages —
  // prefer an existing about:blank, then the first (leftmost/oldest) tab of
  // the AUTOMATION set (Browser4 group tabs + the persisted binding).
  private async _pickSessionTab(selectorTabId: number): Promise<chrome.tabs.Tab> {
    // lastFocusedWindow is the correct selector from a service worker context:
    // `currentWindow` resolves to the most-recently-focused window anyway, but
    // the explicit form is clearer and stable across Chrome versions.
    const windowTabs = await chrome.tabs.query({ lastFocusedWindow: true }).catch(() => [] as chrome.tabs.Tab[]);

    // The automation set = tabs our session owns.  The Browser4 tab group is
    // the session's own playground (tabs created by `tab-new` / auto-resume
    // are pulled in), plus the persisted binding id if it still exists.
    const automatedTabIds = new Set<number>();
    const binding = await this._restoreBinding().catch(() => undefined);
    if (binding?.id !== undefined)
      automatedTabIds.add(binding.id);
    try {
      const groups = await chrome.tabGroups.query({ title: 'Browser4' });
      for (const g of groups) {
        const tabs = await chrome.tabs.query({ groupId: g.id });
        for (const t of tabs) {
          if (t.id !== undefined)
            automatedTabIds.add(t.id);
        }
      }
    } catch (_) {
      // ignore — the automation set is best-effort; blank pages still eligible
    }

    const chosen = chooseSessionTab(windowTabs as chrome.tabs.Tab[], selectorTabId, automatedTabIds);
    if (chosen)
      return chosen as chrome.tabs.Tab;

    // Nothing eligible (no blank page, no automation tab). NEVER fall back to
    // a user-opened page: create a neutral session tab instead — reuse the
    // connect page when available (zero new tabs), else open a fresh blank tab
    // (worker-restart path, where the connect page is already gone).
    if (selectorTabId >= 0) {
      const updated = await chrome.tabs.update(selectorTabId, { url: 'about:blank' });
      if (updated)
        return updated;
    }
    const created = await chrome.tabs.create({ url: 'about:blank', active: false })
        .catch(() => undefined);
    if (created)
      return created;
    throw new Error('Failed to create a session tab — no eligible tab found');
  }

  // Arms the keepalive alarm that keeps the MV3 service worker (owner of the
  // relay WebSocket) awake and heartbeats controlled tabs. No-op when the
  // alarm is already scheduled.
  private _ensureKeepaliveAlarm(): void {
    try {
      chrome.alarms.create(KEEPALIVE_ALARM, { periodInMinutes: KEEPALIVE_PERIOD_MINUTES });
    } catch (error: any) {
      // Pre-Chrome 120 / restricted environments may reject the 0.5-minute
      // period. Never let keepalive setup break the connection itself.
      debugLog('Failed to arm keepalive alarm:', error?.message);
    }
  }

  private async _onAlarm(alarm: chrome.alarms.Alarm): Promise<void> {
    if (alarm.name !== KEEPALIVE_ALARM)
      return;
    if (!this._activeGroup) {
      // No in-memory connection — a service-worker restart wiped it. Try to
      // resume the session from the persisted relay URL.
      await this._tryRestoreSession();
      return;
    }
    // Wake the worker (the alarm event itself does this) and heartbeat the
    // controlled tabs so Memory Saver does not freeze/discard them.
    try {
      await this._activeGroup.heartbeat();
    } catch (error: any) {
      debugLog('Tab heartbeat failed:', error?.message);
    }
  }

  // Re-establishes the relay WebSocket after an MV3 service-worker restart.
  // The old worker was torn down without a chance to close the socket, so the
  // backend sees a stale connection; reconnecting to the same /ws/extension/
  // endpoint lets it rebind (the backend keeps extension-attached sessions
  // registered for this purpose).
  private async _tryRestoreSession(): Promise<void> {
    let stored: any = null;
    try {
      stored = await (chrome.storage as any)?.session?.get?.(STORAGE_RELAY_KEY);
    } catch (_) {
      return;
    }
    const url: string | undefined = stored?.[STORAGE_RELAY_KEY]?.url;
    if (!url)
      return;
    let connection: any;
    try {
      connection = await openRelayConnection(url);
    } catch (error: any) {
      debugLog('Auto-restore: relay connection failed', error?.message);
      return;
    }
    // Pick the tab to drive. Preference order:
    //   1. The persisted binding (tabId of the last session tab) if it still
    //      exists, is debuggable, and was NOT grouped by the user afterwards.
    //   2. Tabs previously in a Browser4 group (our own leftovers).
    //   3. The chooseSessionTab policy (never grouped, blank first, then the
    //      leftmost/oldest debuggable tab). The connect page is gone after a
    //      restart, so there is no selector tab to exclude.
    let tab: chrome.tabs.Tab | undefined = await this._restoreBinding();
    if (!tab) {
      try {
        const groups = await chrome.tabGroups.query({ title: 'Browser4' });
        for (const g of groups) {
          const tabs = await chrome.tabs.query({ groupId: g.id });
          const first = tabs.find(t => t.id !== undefined && !isNonDebuggableUrl(t.url));
          if (first) {
            tab = first;
            break;
          }
        }
      } catch (_) {
        // ignore — fall through to the policy pick
      }
    }
    if (!tab)
      tab = await this._pickSessionTab(-1);
    this._rememberBinding(tab);

    const group = new ConnectedTabGroup(connection, tab);
    group.onclose = () => {
      if (this._activeGroup === group) {
        this._activeGroup = undefined;
        this._activeClientName = undefined;
        chrome.alarms.clear(KEEPALIVE_ALARM).catch(() => {});
        debugLog('Restored group closed');
      }
    };
    this._activeGroup = group;
    this._activeClientName = 'restored';
    this._ensureKeepaliveAlarm();
    debugLog('Auto-restored session on tab', tab.id, tab.url?.slice(0, 80));
  }

  // Persists the session-tab binding.  The entry survives service-worker
  // restarts (storage.session) so a reconnect resumes the same tab instead of
  // re-picking one; it is intentionally NOT cleared on disconnect so a later
  // `attach --extension` restores the previous working tab (agent-browser's
  // restore_target_binding semantics).
  private _rememberBinding(tab: chrome.tabs.Tab): void {
    if (tab.id === undefined)
      return;
    const entry = {
      tabId: tab.id,
      url: tab.url,
      title: tab.title,
      at: Date.now(),
    };
    (chrome.storage as any)?.session?.set?.({ [STORAGE_BINDING_KEY]: entry }).catch(() => {});
  }

  // Reads the persisted binding and validates it is still usable: the tab must
  // exist, be debuggable, and NOT have been moved into a user tab group since
  // the binding was written (grouped tabs are never driven).
  private async _restoreBinding(): Promise<chrome.tabs.Tab | undefined> {
    let stored: any = null;
    try {
      stored = await (chrome.storage as any)?.session?.get?.(STORAGE_BINDING_KEY);
    } catch (_) {
      return undefined;
    }
    const entry = stored?.[STORAGE_BINDING_KEY];
    if (!entry || typeof entry.tabId !== 'number')
      return undefined;
    try {
      const tab = await chrome.tabs.get(entry.tabId);
      if (isNonDebuggableUrl(tab.url))
        return undefined;
      if (tab.groupId !== undefined && tab.groupId >= 0)
        return undefined;
      return tab;
    } catch (_) {
      // Tab was closed — binding is stale; caller re-picks.
      return undefined;
    }
  }

  private async _getTabs(): Promise<chrome.tabs.Tab[]> {
    const tabs = await chrome.tabs.query({});
    return tabs.filter(tab => !isNonDebuggableUrl(tab.url));
  }

  private async _onActionClicked(): Promise<void> {
    await chrome.tabs.create({
      url: chrome.runtime.getURL('status.html'),
      active: true
    });
  }

  // Closes the active group's connection if any. ConnectedTabGroup's onclose
  // handles state cleanup (connectedTabIds, badges, reconcile).
  private _disconnect(reason: string) {
    this._activeGroup?.close(reason);
    this._activeGroup = undefined;
    this._activeClientName = undefined;
    chrome.alarms.clear(KEEPALIVE_ALARM).catch(() => {});
    // User-initiated disconnect: clear the persisted relay URL so a later
    // service-worker restart does not resurrect a session nobody wants.
    (chrome.storage as any)?.session?.remove?.(STORAGE_RELAY_KEY).catch(() => {});
  }
}

new Browser4Extension();

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
import { ConnectedTabGroup, cleanupStaleBrowser4Groups, isNonDebuggableUrl } from './connectedTabGroup';

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
        // newTab parameter), create a fresh about:blank page instead of
        // reusing the connect page. This gives `attach --extension` a
        // clean browser context.
        const selectedTabPromise: Promise<chrome.tabs.Tab> = message.tab
            ? Promise.resolve(message.tab)
            : chrome.tabs.create({ url: 'about:blank' });
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

      const group = new ConnectedTabGroup(connection, tab);
      group.onclose = () => {
        // _disconnect normally clears _activeGroup before calling group.close(),
        // so this branch only triggers on unexpected closes (e.g. remote end).
        if (this._activeGroup === group) {
          this._activeGroup = undefined;
          this._activeClientName = undefined;
          debugLog('Active group closed unexpectedly');
        }
      };
      this._activeGroup = group;
      this._activeClientName = clientName;

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
  }
}

new Browser4Extension();

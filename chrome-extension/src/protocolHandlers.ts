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

export type ProtocolCommand = {
  id: number;
  method: string;
  params?: any;
};

// The narrow surface of RelayConnection that protocol handlers use.
export interface RelayContext {
  readonly attachedTabs: ReadonlySet<number>;
  sendMessage(message: any): void;
  // Records that a tab's debugger is now attached. Fires ontabattached on the
  // owning RelayConnection.
  notifyTabAttached(tabId: number): void;
  // Records that a tab's debugger is now detached. Fires ontabdetached on the
  // owning RelayConnection.
  notifyTabDetached(tabId: number): void;
}

// Allow-listed chrome.* commands the relay may invoke. The handler resolves
// the method reflectively and spreads positional params.
const ALLOWED_CHROME_COMMANDS = new Set([
  'chrome.debugger.attach',
  'chrome.debugger.detach',
  'chrome.debugger.sendCommand',
  'chrome.tabs.create',
  'chrome.tabs.remove',
]);

export class ProtocolHandler {
  private _context: RelayContext;

  constructor(context: RelayContext) {
    this._context = context;
  }

  async handleCommand(message: ProtocolCommand): Promise<any> {
    if (ALLOWED_CHROME_COMMANDS.has(message.method)) {
      const args = (message.params ?? []) as any[];
      const result = await invokeChromeMethod(message.method, args);
      // Attach bookkeeping; detach flows through the chrome.debugger.onDetach event.
      if (message.method === 'chrome.debugger.attach') {
        const target = args[0] as chrome.debugger.Debuggee | undefined;
        if (target?.tabId !== undefined)
          this._context.notifyTabAttached(target.tabId);
      }
      return result ?? {};
    }
    throw new Error(`Unknown method: ${message.method}`);
  }

  forwardChromeEvent(fullMethod: string, args: any[]): void {
    this._context.sendMessage({ method: fullMethod, params: args });
  }

  onUserAttachRequest(tab: chrome.tabs.Tab): void {
    // Simulate a "new tab opened" event; the relay responds by calling
    // chrome.debugger.attach, which flows through handleCommand.
    this._context.sendMessage({ method: 'chrome.tabs.onCreated', params: [tab] });
  }

  didInitialize(): void {
    // Signals the end of the initial-tab handshake. The relay holds CDP
    // traffic from Browser4 until it sees this event, so that
    // `Target.setAutoAttach` is answered from a populated tab model.
    this._context.sendMessage({ method: 'extension.initialized', params: [] });
  }

  onUserDetachRequest(tabId: number): void {
    // chrome.debugger.detach does not fire onDetach for the caller, so we
    // synthesize one so the relay notices the tab is gone.
    this._context.sendMessage({
      method: 'chrome.debugger.onDetach',
      params: [{ tabId }, 'target_closed'],
    });
  }
}

// ─── Reflective chrome.* invocation ────────────────────────────────────────

// Resolves chrome.<api>.<member>. Exported so RelayConnection can install
// listeners on the same set of chrome events without duplicating the traversal.
export function resolveChromeMember(fullMethod: string): { obj: any; name: string } {
  const parts = fullMethod.split('.');
  if (parts[0] !== 'chrome' || parts.length < 3)
    throw new Error(`Invalid chrome method: ${fullMethod}`);
  let obj: any = chrome;
  for (let i = 1; i < parts.length - 1; i++) {
    obj = obj?.[parts[i]];
    if (obj === undefined)
      throw new Error(`Unknown chrome path: ${parts.slice(0, i + 1).join('.')}, calling ${fullMethod}`);
  }
  return { obj, name: parts[parts.length - 1] };
}

async function invokeChromeMethod(fullMethod: string, args: any[]): Promise<any> {
  const { obj, name } = resolveChromeMember(fullMethod);
  const fn = obj[name] as (...a: any[]) => any;
  if (typeof fn !== 'function')
    throw new Error(`Not a function: ${fullMethod}`);
  return await fn.apply(obj, args);
}

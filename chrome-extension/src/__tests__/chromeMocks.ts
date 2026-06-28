/**
 * Shared mock setup for Chrome extension APIs used across test files.
 *
 * Each mock provides jest-compatible fn() wrappers so tests can assert call
 * arguments and control return values / rejections.
 */

import { vi } from 'vitest';

// ─── chrome.debugger ──────────────────────────────────────────────────────

export const mockDebuggerAttach = vi.fn();
export const mockDebuggerDetach = vi.fn();
export const mockDebuggerSendCommand = vi.fn();

export const mockDebuggerOnEvent = createEventMock();
export const mockDebuggerOnDetach = createEventMock();

function createEventMock() {
  const listeners: Array<(...args: any[]) => void> = [];
  return {
    addListener: vi.fn((fn: (...args: any[]) => void) => {
      listeners.push(fn);
    }),
    removeListener: vi.fn((fn: (...args: any[]) => void) => {
      const idx = listeners.indexOf(fn);
      if (idx >= 0) listeners.splice(idx, 1);
    }),
    /** Fire the event to all registered listeners. */
    dispatch(...args: any[]) {
      for (const fn of [...listeners]) fn(...args);
    },
    /** Clear all registered listeners. */
    clear() {
      listeners.length = 0;
    },
    get listenerCount() {
      return listeners.length;
    },
  };
}

// ─── chrome.tabs ──────────────────────────────────────────────────────────

export const mockTabsCreate = vi.fn();
export const mockTabsRemove = vi.fn();
export const mockTabsUpdate = vi.fn();
export const mockTabsQuery = vi.fn();

export const mockTabsOnCreated = createEventMock();
export const mockTabsOnRemoved = createEventMock();

// ─── chrome.runtime ───────────────────────────────────────────────────────

export const mockRuntimeSendMessage = vi.fn();
export const mockRuntimeOnMessage = createEventMock();

// ─── chrome.windows ───────────────────────────────────────────────────────

export const mockWindowsUpdate = vi.fn();

// ─── Install / uninstall ──────────────────────────────────────────────────

const _chromeBackup: any = {};

export function installChromeMocks() {
  _chromeBackup.debugger = (globalThis as any).chrome?.debugger;
  _chromeBackup.tabs = (globalThis as any).chrome?.tabs;
  _chromeBackup.runtime = (globalThis as any).chrome?.runtime;
  _chromeBackup.windows = (globalThis as any).chrome?.windows;

  (globalThis as any).chrome = {
    debugger: {
      attach: mockDebuggerAttach,
      detach: mockDebuggerDetach,
      sendCommand: mockDebuggerSendCommand,
      onEvent: mockDebuggerOnEvent,
      onDetach: mockDebuggerOnDetach,
    },
    tabs: {
      create: mockTabsCreate,
      remove: mockTabsRemove,
      update: mockTabsUpdate,
      query: mockTabsQuery,
      onCreated: mockTabsOnCreated,
      onRemoved: mockTabsOnRemoved,
    },
    runtime: {
      sendMessage: mockRuntimeSendMessage,
      onMessage: mockRuntimeOnMessage,
    },
    windows: {
      update: mockWindowsUpdate,
    },
  };
}

export function uninstallChromeMocks() {
  (globalThis as any).chrome = {
    debugger: _chromeBackup.debugger,
    tabs: _chromeBackup.tabs,
    runtime: _chromeBackup.runtime,
    windows: _chromeBackup.windows,
  };
}

/** Reset all mock call history and event listeners between tests. */
export function resetChromeMocks() {
  vi.clearAllMocks();
  mockDebuggerOnEvent.clear();
  mockDebuggerOnDetach.clear();
  mockTabsOnCreated.clear();
  mockTabsOnRemoved.clear();
  mockRuntimeOnMessage.clear();
}

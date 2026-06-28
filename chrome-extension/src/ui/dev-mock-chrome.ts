/**
 * Dev-only Chrome API mocks so the UI can be opened standalone.
 * This file is only loaded when running `npm run dev` — it is NOT
 * included in the production extension build.
 */

if (typeof chrome === 'undefined' || !chrome.runtime) {
  (window as any).chrome = {
    runtime: {
      sendMessage: (_msg: any) => {
        // eslint-disable-next-line no-console
        console.log('[dev-mock] chrome.runtime.sendMessage:', _msg);
        return Promise.resolve({ success: false, error: 'Dev mode — no background worker' });
      },
      onMessage: {
        addListener: () => {},
        removeListener: () => {},
      },
      getURL: (path: string) => path,
    },
    tabs: {
      query: () => Promise.resolve([]),
      get: (tabId: number) => Promise.resolve({ id: tabId, title: `Tab ${tabId}`, url: 'about:blank' }),
      update: () => Promise.resolve({}),
      remove: () => Promise.resolve(),
    },
    windows: {
      update: () => Promise.resolve({}),
    },
    debugger: {
      attach: () => Promise.resolve(),
      detach: () => Promise.resolve(),
      sendCommand: () => Promise.resolve({}),
      onEvent: { addListener: () => {}, removeListener: () => {} },
      onDetach: { addListener: () => {}, removeListener: () => {} },
    },
  } as any;
}

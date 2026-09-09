import { describe, it, expect } from 'vitest';
import { chooseSessionTab, TabCandidate } from '../tabSelection';

const tab = (id: number, overrides: Partial<TabCandidate> = {}): TabCandidate => ({
  id,
  url: 'https://example.com/',
  ...overrides,
});

const none = new Set<number>();

describe('chooseSessionTab', () => {
  it('NEVER selects a user-opened page (not in the automation set), even as the first or active tab', () => {
    const tabs = [
      tab(1, { url: 'https://mail.google.com/' }),       // leftmost user page
      tab(2, { active: true, url: 'https://weibo.com/' }), // user's active page
      tab(3),
    ];
    expect(chooseSessionTab(tabs, undefined, none)).toBeNull();
  });

  it('selects the first (leftmost) tab of the automation set', () => {
    const tabs = [
      tab(1, { url: 'https://user-page.com/' }),   // user, ignored
      tab(2, { url: 'https://mail.google.com/' }), // user, ignored
      tab(3, { url: 'https://docs.automated.com/' }),
      tab(4),
    ];
    const automated = new Set([3, 4]);
    expect(chooseSessionTab(tabs, undefined, automated)?.id).toBe(3);
  });

  it('prefers an existing about:blank tab over automation tabs', () => {
    const tabs = [
      tab(1),
      tab(2, { url: 'about:blank' }),
    ];
    const automated = new Set([1]);
    expect(chooseSessionTab(tabs, undefined, automated)?.id).toBe(2);
  });

  it('allows a blank page even without a provenance marker', () => {
    const tabs = [tab(1, { url: 'about:blank' }), tab(2, { url: 'https://user.com/' })];
    expect(chooseSessionTab(tabs, undefined, none)?.id).toBe(1);
  });

  it('refuses user-owned tab groups, but allows automation-set tabs inside groups', () => {
    const tabs = [
      tab(1, { groupId: 7, url: 'about:blank' }),   // blank INSIDE a user group — still vetoed
      tab(2, { groupId: 7 }),                       // user group
      tab(3, { groupId: 8 }),                       // automation group (Browser4)
    ];
    const automated = new Set([3]);
    expect(chooseSessionTab(tabs, undefined, automated)?.id).toBe(3);

    expect(chooseSessionTab(tabs, undefined, none)).toBeNull();
  });

  it('excludes the connect page itself and non-debuggable URLs', () => {
    const tabs = [
      tab(10, { url: 'chrome-extension://abc/connect.html' }),
      tab(11, { url: 'chrome://newtab/' }),
      tab(12, { url: 'edge://settings/' }),
    ];
    const automated = new Set([10, 11, 12]);
    expect(chooseSessionTab(tabs, 10, automated)).toBeNull();
  });

  it('returns null when only user pages / user groups exist', () => {
    const tabs = [
      tab(1, { url: 'https://user-page.com/' }),
      tab(2, { groupId: 5, url: 'about:blank' }),
    ];
    expect(chooseSessionTab(tabs, undefined, none)).toBeNull();
  });

  it('does not prefer the user active tab even when automation tabs exist', () => {
    const tabs = [
      tab(1, { url: 'https://gmail.com/', active: true }), // user active
      tab(2, { url: 'https://stale.automated.com/' }),     // automation, older
    ];
    const automated = new Set([2]);
    expect(chooseSessionTab(tabs, undefined, automated)?.id).toBe(2);
  });
});

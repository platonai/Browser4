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

import { isNonDebuggableUrl } from './connectedTabGroup';

/**
 * A minimal tab shape sufficient for session-tab selection.  Mirrors the
 * fields of `chrome.tabs.Tab` that the decision logic reads so the pure
 * function can be unit-tested without a live browser.
 */
export interface TabCandidate {
  id?: number;
  url?: string;
  active?: boolean;
  groupId?: number;
}

/**
 * Session-tab selection policy.
 *
 * Picks the tab that an `attach --extension` session drives when the user did
 * not explicitly choose one.  The policy follows the semantics of
 * vercel/agent-browser (cli/src/native/browser.rs) with browser4-specific
 * refinements, and its ordering is a hard contract:
 *
 *   0. Source: the last-focused window, as returned by `tabs.query`.  CDP and
 *      extension tab lists are NOT ordered by activity; they follow creation
 *      order, and Chrome inserts new tabs on the right, so index order
 *      approximates "oldest (leftmost) → newest (rightmost)".
 *
 *   1. Hard rules for every candidate (absolute vetoes):
 *      - The connect page itself (`selectorTabId`): an extension page that
 *        must be closed after approval.
 *      - Non-debuggable URLs (`chrome:`, `edge:`, `devtools:` schemes): the
 *        debugger cannot attach — including browser "new tab" pages.
 *      - **User-owned tab groups (`groupId >= 0` unless the tab is in the
 *        automation set): NEVER selected.** A tab group is the user's own
 *        organization — a cluster of bookmarks, research, or background work.
 *        The only exception is the Browser4 automation group itself, which the
 *        session owns; its tabs are part of the automation set.  (A user who
 *        drags a tab INTO the Browser4 group explicitly authorizes it — that
 *        is the documented opt-in on the connect page.)
 *      - **User-opened pages are NEVER selected.** Only tabs in the
 *        automation set (`automatedTabIds`) are eligible, plus existing blank
 *        pages (see below).  The previous "first debuggable tab" fallback
 *        must never reach a page the user opened manually: driving a user's
 *        page with `goto`/`reload` destroys their working state, so the
 *        selector refuses to touch anything it did not create.
 *
 *   2. Preference 1 — a pre-existing EMPTY tab (`about:blank`).
 *      An existing blank tab is the ideal session workbench: no user content,
 *      no history to lose, and navigating it is a no-op.  A blank page cannot
 *      meaningfully be "user work in progress", so it is safe without a
 *      provenance marker and is preferred over touching any other tab.
 *
 *   3. Preference 2 — the first (leftmost / oldest) tab OF THE AUTOMATION
 *      SET.  agent-browser semantics, scoped to automated tabs only:
 *      - Rightmost tabs are newest, i.e. most likely actively used; the
 *        leftmost automation tab is the stale one, and within the automation
 *        set there is no user intent to protect.
 *      - A deterministic rule keeps the binding predictable ("with the first
 *        tab live, it stays the active tab").
 *      - The user's ACTIVE tab is deliberately NOT preferred: attaching must
 *        never hijack the page the user is currently looking at.  If the user
 *        wants a specific tab driven, they select it explicitly on the connect
 *        page (`message.tab` bypasses this selector entirely).
 *      - If no automation tab is used but the browser has a persisted binding
 *        (see `b4TabBinding`), the binding id is part of the automation set —
 *        resuming the same working tab beats picking a "new" one.
 *
 *   4. Preference 3 — nothing eligible → return `null`.  The caller must not
 *      fall back to a user page: it creates a fresh `about:blank` session tab
 *      (reusing the connect page when available, otherwise `tabs.create`).
 *
 *   Robustness: a candidate taken as-is is assumed live; Memory-Saver
 *   discarded-renderer recovery is handled elsewhere (heartbeat + revive),
 *   mirroring agent-browser's "the first tab is almost always live" probe.
 */
export function chooseSessionTab(
  tabs: TabCandidate[],
  selectorTabId: number | undefined,
  automatedTabIds: ReadonlySet<number>,
): TabCandidate | null {
  const isAutomated = (tab: TabCandidate): boolean =>
    tab.id !== undefined && automatedTabIds.has(tab.id);

  const candidates = tabs.filter(tab => {
    if (tab.id === undefined || tab.id === selectorTabId)
      return false;
    if (isNonDebuggableUrl(tab.url))
      return false;
    // Grouped tabs are eligible ONLY when the tab is part of the automation
    // set (the Browser4 group).  Any other group is user-owned → veto.
    if (tab.groupId !== undefined && tab.groupId >= 0 && !isAutomated(tab))
      return false;
    // A blank page is provenance-free and safe; everything else must have
    // been created by automation (or be an explicitly authorized group tab).
    const isBlank = (tab.url ?? '').trim().toLowerCase() === 'about:blank';
    return isBlank || isAutomated(tab);
  });

  // 1) An existing empty tab is the safest workbench.
  const blank = candidates.find(tab =>
    (tab.url ?? '').trim().toLowerCase() === 'about:blank',
  );
  if (blank)
    return blank;

  // 2) agent-browser rule, scoped to automation-set tabs: the first
  //    (leftmost/oldest) one that is debuggable and grouped-tab-eligible.
  return candidates.find(tab => isAutomated(tab)) ?? null;
}

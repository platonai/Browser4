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

import React from 'react';
import { createRoot } from 'react-dom/client';
import { Header } from './header';
import { Footer } from './footer';
import { EmptyState } from './emptyState';
import { Button, TabItem } from './tabItem';
import { AuthTokenSection } from './authToken';
import './demo.css';

// ── Mock data ───────────────────────────────────────────────────────────────

const MOCK_TABS: chrome.tabs.Tab[] = [
  {
    id: 1,
    title: 'GitHub - platonai/Browser4: AI-driven browser automation',
    url: 'https://github.com/platonai/Browser4',
    favIconUrl: 'https://github.githubassets.com/favicons/favicon.svg',
  },
  {
    id: 2,
    title: 'Example Domain',
    url: 'https://example.com/',
    favIconUrl: undefined,
  },
  {
    id: 3,
    title:
      'Stack Overflow — Where Developers Learn, Share, & Build Careers',
    url: 'https://stackoverflow.com/questions',
    favIconUrl: 'https://stackoverflow.com/favicon.ico',
  },
] as any;

const MOCK_CONNECTED_TABS: chrome.tabs.Tab[] = [
  {
    id: 4,
    title: 'Browser4 — Dashboard',
    url: 'https://app.browser4.io/dashboard',
    favIconUrl: undefined,
  },
  {
    id: 5,
    title: 'My App — Local Development',
    url: 'http://localhost:3000/',
    favIconUrl: undefined,
  },
] as any;

// ── Demo sections ───────────────────────────────────────────────────────────

const DemoSection: React.FC<{
  title: string;
  description?: string;
  children: React.ReactNode;
}> = ({ title, description, children }) => (
  <section className='demo-section'>
    <h2 className='demo-section-title'>{title}</h2>
    {description && (
      <p className='demo-section-desc'>{description}</p>
    )}
    <div className='demo-section-body'>{children}</div>
  </section>
);

const StatusBanner: React.FC<{
  type: 'connecting' | 'connected' | 'error';
  message: string;
}> = ({ type, message }) => (
  <div className={`status-banner ${type}`}>{message}</div>
);

// ── Main demo app ───────────────────────────────────────────────────────────

const DemoApp: React.FC = () => {
  const handleNoop = () => {
    // no-op for demo buttons
  };

  return (
    <div className='app-container'>
      <div className='content-wrapper'>
        <header className='demo-header'>
          <h1 className='demo-title'>Browser4 UI Demo</h1>
          <p className='demo-subtitle'>
            All UI components rendered with mock data — no Chrome extension or
            Browser4 server required.
          </p>
        </header>

        {/* ── Branding ─────────────────────────────────────────── */}
        <DemoSection title='Header & Footer' description='Branding components shown on both extension pages.'>
          <Header />
          <Footer />
        </DemoSection>

        {/* ── Status banners ─────────────────────────────────── */}
        <DemoSection
          title='Status Banners'
          description='The three connection states shown by the Connect page.'
        >
          <StatusBanner
            type='connected'
            message='"browser4-cli" connected.'
          />
          <StatusBanner
            type='connecting'
            message='"browser4-mcp" is trying to connect to the Browser4 Extension.'
          />
          <StatusBanner
            type='error'
            message='Invalid token provided.'
          />
        </DemoSection>

        {/* ── Warning banner ─────────────────────────────────── */}
        <DemoSection
          title='Warning Banner'
          description='Security warning shown while a client is requesting connection.'
        >
          <div className='warning-banner'>
            <strong>⚠️ Warning:</strong> Allowing this connection exposes the
            entire browser to the client, including any signed-in sessions,
            cookies, and content in other tabs and windows. Once approved, the
            client may also be able to reconnect later without showing this
            dialog again, unless you regenerate the token below and then restart
            the browser.
          </div>
        </DemoSection>

        {/* ── Tab list (connect page) ────────────────────────── */}
        <DemoSection
          title='Tab Picker'
          description='Tab list shown on the Connect page so the user can pick which tab to share.'
        >
          <div className='tab-section-title'>
            You can drag tabs into the Browser4 group later to make them
            accessible to the client. Optionally, select a tab to allow and
            immediately switch to it:
          </div>
          {MOCK_TABS.map((tab) => (
            <TabItem
              key={tab.id}
              tab={tab}
              button={
                <Button variant='primary' onClick={handleNoop}>
                  Allow &amp; select
                </Button>
              }
            />
          ))}
        </DemoSection>

        {/* ── Connected tabs (status page) ───────────────────── */}
        <DemoSection
          title='Connected Tabs'
          description='The Status page when one or more tabs are connected.'
        >
          <div className='card'>
            <div className='card-header'>
              <span className='card-title'>
                Connected to <strong>"browser4-cli"</strong>
              </span>
              <Button variant='primary' onClick={handleNoop}>
                Disconnect
              </Button>
            </div>
            <div className='tab-section-title'>
              {MOCK_CONNECTED_TABS.length === 1
                ? 'Accessible page:'
                : 'Accessible pages:'}
            </div>
            {MOCK_CONNECTED_TABS.map((tab) => (
              <TabItem key={tab.id} tab={tab} onClick={handleNoop} />
            ))}
          </div>
        </DemoSection>

        {/* ── Empty state ────────────────────────────────────── */}
        <DemoSection
          title='Empty State'
          description='The Status page when no clients are connected.'
        >
          <div className='card'>
            <EmptyState />
          </div>
        </DemoSection>

        {/* ── Buttons ────────────────────────────────────────── */}
        <DemoSection
          title='Button Variants'
          description='All button styles used throughout the extension.'
        >
          <div className='demo-button-row'>
            <Button variant='primary' onClick={handleNoop}>
              Primary
            </Button>
            <Button variant='default' onClick={handleNoop}>
              Default
            </Button>
            <Button variant='reject' onClick={handleNoop}>
              Reject
            </Button>
          </div>
        </DemoSection>

        {/* ── Auth token ─────────────────────────────────────── */}
        <DemoSection
          title='Auth Token'
          description='Token section shown on both the Connect and Status pages.'
        >
          <AuthTokenSection />
        </DemoSection>

        <footer className='demo-footer'>
          <p>
            This is a standalone demo page. In production, these components are
            rendered inside the Browser4 Chrome Extension.
          </p>
        </footer>
      </div>
    </div>
  );
};

// Initialize the React app
const container = document.getElementById('root');
if (container) {
  const root = createRoot(container);
  root.render(<DemoApp />);
}

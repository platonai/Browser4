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

import React, { useCallback, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Header } from './header';
import { Footer } from './footer';
import { Button, TabItem } from './tabItem';
import { AuthTokenSection, getOrCreateAuthToken } from './authToken';
import * as icons from './icons';

type Status =
  | { type: 'connecting'; message: string }
  | { type: 'connected'; message: string }
  | { type: 'error'; message: string };

const ConnectApp: React.FC = () => {
  const [tabs, setTabs] = useState<chrome.tabs.Tab[]>([]);
  const [status, setStatus] = useState<Status | null>(null);
  const [showTabList, setShowTabList] = useState(true);
  const [clientInfo, setClientInfo] = useState('unknown');
  const [tabsLoading, setTabsLoading] = useState(false);

  const setError = useCallback((message: string) => {
    setShowTabList(false);
    setTabsLoading(false);
    setStatus({ type: 'error', message });
  }, []);

  useEffect(() => {
    const runAsync = async () => {
      const params = new URLSearchParams(window.location.search);
      const relayUrl = params.get('mcpRelayUrl');

      if (!relayUrl) {
        setError('Missing mcpRelayUrl parameter in URL.');
        return;
      }

      try {
        const host = new URL(relayUrl).hostname;
        if (host !== '127.0.0.1' && host !== '[::1]') {
          setError(`Browser4 extension only allows loopback connections (127.0.0.1 or [::1]). Received host: ${host}`);
          return;
        }
      } catch (e) {
        setError(`Invalid mcpRelayUrl parameter in URL: ${relayUrl}. ${e}`);
        return;
      }

      try {
        const client = JSON.parse(params.get('client') || '{}');
        const info = `${client.name || 'unknown'}`;
        setClientInfo(info);
        setStatus({
          type: 'connecting',
          message: `"${info}" is trying to connect to the Browser4 Extension.`
        });
      } catch (e) {
        setStatus({ type: 'error', message: 'Failed to parse client version.' });
        return;
      }

      const response = await chrome.runtime.sendMessage({ type: 'connectionRequested', mcpRelayUrl: relayUrl });
      if (!response.success) {
        setError(response.error);
        return;
      }

      const expectedToken = getOrCreateAuthToken();
      const token = params.get('token');
      if (token === expectedToken) {
        await handleConnectToTab();
        return;
      }
      if (token) {
        setError('Invalid token provided.');
        return;
      }

      // If this is a browser_navigate command, hide the tab list and show simple allow/reject
      if (params.get('newTab') === 'true')
        setShowTabList(false);
      else
        await loadTabs();
    };
    void runAsync();
    // Ping the background every 20s so the MV3 service worker (which owns the
    // relay WebSocket) stays above its 30s idle timeout while the user decides.
    const keepalive = setInterval(() => {
      chrome.runtime.sendMessage({ type: 'keepalive' }).catch(() => {});
    }, 20_000);
    return () => clearInterval(keepalive);
  }, []);

  const loadTabs = useCallback(async () => {
    setTabsLoading(true);
    const response = await chrome.runtime.sendMessage({ type: 'getTabs' });
    if (response.success) {
      setTabs(response.tabs);
    } else {
      setStatus({ type: 'error', message: 'Failed to load tabs: ' + response.error });
    }
    setTabsLoading(false);
  }, []);

  const handleConnectToTab = useCallback(async (tab?: chrome.tabs.Tab) => {
    setShowTabList(false);

    try {
      const response = await chrome.runtime.sendMessage({
        type: 'connectToTab',
        tab,
        clientName: clientInfo,
      });

      if (response?.success) {
        setStatus({ type: 'connected', message: `"${clientInfo}" connected.` });
      } else {
        setStatus({
          type: 'error',
          message: response?.error || `"${clientInfo}" failed to connect.`
        });
      }
    } catch (e) {
      setStatus({
        type: 'error',
        message: `"${clientInfo}" failed to connect: ${e}`
      });
    }
  }, [clientInfo]);

  useEffect(() => {
    const listener = (message: any) => {
      if (message.type === 'pendingConnectionClosed') {
        setError('Pending client connection closed.');
        document.title = 'Browser4 Extension';
      }
    };
    chrome.runtime.onMessage.addListener(listener);
    return () => {
      chrome.runtime.onMessage.removeListener(listener);
    };
  }, [setError]);

  return (
    <div className='app-container'>
      <div className='content-wrapper'>
        <Header />

        {status && (
          <div className='card'>
            <StatusBanner
              status={status}
              onRetry={
                status.type === 'error' && status.message.includes('tabs')
                  ? loadTabs
                  : undefined
              }
              retryLabel='Reload tabs'
            />
          </div>
        )}

        {status?.type === 'connecting' && (
          <div className='card'>
            <div className='warning-banner'>
              <strong>⚠️ Warning:</strong> Allowing this connection exposes the entire browser to the client,
              including any signed-in sessions, cookies, and content in other tabs and windows.
              Once approved, the client may also be able to reconnect later without showing this dialog again,
              unless you regenerate the token below and then restart the browser.
            </div>
          </div>
        )}

        {status?.type === 'connecting' && (
          <div className='card'>
            <h3 className='card-title'>Authentication Token</h3>
            <AuthTokenSection />
          </div>
        )}

        {showTabList && (
          <div className='card'>
            {tabsLoading ? (
              <div aria-busy='true' aria-label='Loading tabs'>
                {[1, 2, 3].map(i => (
                  <div className='skeleton-row' key={i}>
                    <div className='skeleton skeleton-favicon' />
                    <div className='skeleton-text'>
                      <div className='skeleton skeleton-title' />
                      <div className='skeleton skeleton-url' />
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <>
                <div className='tab-section-title'>
                  You can drag tabs into the Browser4 group later to make them accessible to the client.
                  Optionally, select a tab to allow and immediately switch to it:
                </div>
                <div>
                  {tabs.map(tab => (
                    <TabItem
                      key={tab.id}
                      tab={tab}
                      button={
                        <Button variant='primary' onClick={() => handleConnectToTab(tab)}>
                          Allow &amp; select
                        </Button>
                      }
                    />
                  ))}
                </div>
              </>
            )}
          </div>
        )}

        <Footer />
      </div>
    </div>
  );
};

interface StatusBannerProps {
  status: Status;
  onRetry?: () => void;
  retryLabel?: string;
}

const StatusBanner: React.FC<StatusBannerProps> = ({ status, onRetry, retryLabel }) => {
  const iconMap: Record<string, React.ReactNode> = {
    connected: <span className='status-banner-icon' aria-hidden='true'>{icons.check()}</span>,
    connecting: <Spinner />,
    error: <span className='status-banner-icon' aria-hidden='true'>{icons.cross()}</span>,
  };

  return (
    <div
      className={`status-banner ${status.type}`}
      role='status'
      aria-live='polite'
    >
      {iconMap[status.type] || icons.check()}
      <div className='status-banner-body'>
        <div>{status.message}</div>
        {status.type === 'error' && onRetry && (
          <div className='status-banner-action'>
            <button
              className='status-banner-retry-btn'
              onClick={onRetry}
              type='button'
            >
              {icons.refresh()}
              {retryLabel || 'Retry'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

/** Simple inline spinner for the connecting state. */
const Spinner: React.FC = () => (
  <span className='status-banner-icon' aria-hidden='true'>
    <svg className='octicon' viewBox='0 0 16 16' width='16' height='16'>
      <style>{`@keyframes b4-spin{to{transform:rotate(360deg)}}`}</style>
      <g style={{ transformOrigin: '8px 8px', animation: 'b4-spin 1s linear infinite' }}>
        <path
          d='M8 2a6 6 0 100 12A6 6 0 008 2zM1.5 8a6.5 6.5 0 1113 0 6.5 6.5 0 01-13 0z'
          fill='currentColor'
          opacity='0.2'
        />
        <path
          d='M8 1.5a.75.75 0 010 1.5A5 5 0 103 8a.75.75 0 01-1.5 0A6.5 6.5 0 118 1.5z'
          fill='currentColor'
        />
      </g>
    </svg>
  </span>
);

// Initialize the React app
const container = document.getElementById('root');
if (container) {
  const root = createRoot(container);
  root.render(<ConnectApp />);
}

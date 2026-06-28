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

import React, { useState, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { Header } from './header';
import { Footer } from './footer';
import { EmptyState } from './emptyState';
import { Button, TabItem  } from './tabItem';
import { AuthTokenSection } from './authToken';

const StatusApp: React.FC = () => {
  const [connectedTabs, setConnectedTabs] = useState<chrome.tabs.Tab[]>([]);
  const [clientName, setClientName] = useState<string | undefined>(undefined);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    void loadStatus();
  }, []);

  const loadStatus = async () => {
    setLoading(true);
    const { connectedTabIds, clientName } = await chrome.runtime.sendMessage({ type: 'getConnectionStatus' });
    const tabs = await Promise.all((connectedTabIds as number[] ?? []).map(tabId => chrome.tabs.get(tabId)));
    setConnectedTabs(tabs);
    setClientName(clientName);
    setLoading(false);
  };

  const openTab = async (tabId: number) => {
    await chrome.tabs.update(tabId, { active: true });
    window.close();
  };

  const disconnect = async () => {
    await chrome.runtime.sendMessage({ type: 'disconnect' });
    window.close();
  };

  const renderContent = () => {
    if (loading) {
      return (
        <div className='card' aria-busy='true' aria-label='Loading connection status'>
          {[1, 2].map(i => (
            <div className='skeleton-row' key={i}>
              <div className='skeleton skeleton-favicon' />
              <div className='skeleton-text'>
                <div className='skeleton skeleton-title' />
                <div className='skeleton skeleton-url' />
              </div>
            </div>
          ))}
        </div>
      );
    }

    if (connectedTabs.length > 0) {
      return (
        <div className='card'>
          <div className='card-header'>
            <span className='card-title'>
              Connected to <strong>"{clientName || 'unknown'}"</strong>
            </span>
            <Button variant='primary' onClick={disconnect}>
              Disconnect
            </Button>
          </div>
          <div className='tab-section-title'>
            {connectedTabs.length === 1 ? 'Accessible page:' : 'Accessible pages:'}
          </div>
          <div>
            {connectedTabs.map(tab => (
              <TabItem
                key={tab.id}
                tab={tab}
                onClick={() => openTab(tab.id!)}
              />
            ))}
          </div>
        </div>
      );
    }

    return (
      <div className='card'>
        <EmptyState />
      </div>
    );
  };

  return (
    <div className='app-container'>
      <div className='content-wrapper'>
        <Header />
        {renderContent()}
        <div className='card'>
          <h3 className='card-title'>Authentication Token</h3>
          <AuthTokenSection />
        </div>
        <Footer />
      </div>
    </div>
  );
};

// Initialize the React app
const container = document.getElementById('root');
if (container) {
  const root = createRoot(container);
  root.render(<StatusApp />);
}

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
import './emptyState.css';

/**
 * Empty state shown when no client is connected to the extension.
 */
export const EmptyState: React.FC = () => (
  <div className="empty-state">
    <svg
      className="empty-state-icon"
      width="48"
      height="48"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="1.5" opacity="0.4" />
      <path
        d="M12 7v5M12 16h.01"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
      />
    </svg>
    <h3 className="empty-state-title">No Clients Connected</h3>
    <p className="empty-state-description">
      Connect from the Browser4 CLI or MCP server by passing the{' '}
      <code>--extension</code> flag.
    </p>
    <div className="empty-state-actions">
      <a
        className="empty-state-link"
        href="https://browser4.io/docs"
        target="_blank"
        rel="noopener noreferrer"
      >
        View documentation →
      </a>
    </div>
  </div>
);

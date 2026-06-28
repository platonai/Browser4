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
import './footer.css';

const EXTENSION_VERSION =
  (typeof chrome !== 'undefined' && chrome.runtime?.getManifest?.()?.version) || '0.0.0';

/**
 * Application footer with version and links.
 */
export const Footer: React.FC = () => (
  <footer className="app-footer" role="contentinfo">
    <span className="app-footer-version">v{EXTENSION_VERSION}</span>
    <span className="app-footer-separator" aria-hidden="true">·</span>
    <a
      className="app-footer-link"
      href="https://github.com/platonai/Browser4"
      target="_blank"
      rel="noopener noreferrer"
    >
      GitHub
    </a>
    <span className="app-footer-separator" aria-hidden="true">·</span>
    <a
      className="app-footer-link"
      href="https://browser4.io/docs"
      target="_blank"
      rel="noopener noreferrer"
    >
      Help
    </a>
  </footer>
);

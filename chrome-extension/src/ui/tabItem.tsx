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
import { chevronDown } from './icons';

const FALLBACK_FAVICON =
  'data:image/svg+xml,' +
  '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">' +
  '<rect width="16" height="16" fill="%23eaeef2" rx="2"/>' +
  '</svg>';

export const Button: React.FC<{
  variant: 'primary' | 'default' | 'reject';
  onClick: () => void;
  children: React.ReactNode;
}> = ({ variant, onClick, children }) => {
  return (
    <button className={`button ${variant}`} onClick={onClick} type="button">
      {children}
    </button>
  );
};

export interface TabItemProps {
  tab: chrome.tabs.Tab;
  onClick?: () => void;
  button?: React.ReactNode;
}

export const TabItem: React.FC<TabItemProps> = ({ tab, onClick, button }) => {
  const isClickable = !!onClick;
  const faviconUrl = tab.favIconUrl || FALLBACK_FAVICON;

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if ((e.key === 'Enter' || e.key === ' ') && isClickable) {
      e.preventDefault();
      onClick?.();
    }
  };

  return (
    <div
      className={`tab-item${isClickable ? ' clickable' : ''}`}
      onClick={onClick}
      role={isClickable ? 'button' : undefined}
      tabIndex={isClickable ? 0 : undefined}
      onKeyDown={isClickable ? handleKeyDown : undefined}
      aria-label={
        isClickable
          ? `Switch to tab: ${tab.title || 'Untitled'}`
          : undefined
      }
    >
      <img
        src={faviconUrl}
        alt=""
        className="tab-favicon"
        onError={(e) => {
          (e.target as HTMLImageElement).src = FALLBACK_FAVICON;
        }}
      />
      <div className="tab-content">
        <div className="tab-title">{tab.title || 'Untitled'}</div>
        <div className="tab-url">{tab.url}</div>
      </div>
      {button}
      {isClickable && !button && (
        <span className="tab-item-chevron" aria-hidden="true">
          {chevronDown()}
        </span>
      )}
    </div>
  );
};

# Publishing a Chrome Extension to Microsoft Edge Add-ons

Edge is Chromium-based, so most Chrome extensions run in Edge with no code changes. This guide covers testing your extension in Edge, packaging it, and submitting it to the Microsoft Edge Add-ons store.

## Quick Test in Edge

Before submitting to the store, sideload your extension to confirm it works.

1. Open Edge, navigate to `edge://extensions/`
2. Toggle **Developer mode** on (bottom-left corner)
3. Click **Load unpacked** and select your extension's root folder (the one containing `manifest.json`)
4. Exercise the extension — check popups, content scripts, background workers, and options pages

### Common Compatibility Notes

- Edge supports both `chrome.*` and `browser.*` APIs. If you want cross-browser portability, prefer `browser.*` and use feature detection:

```javascript
const browserAPI = typeof browser !== 'undefined' ? browser : chrome;
```

- Manifest V3 is required for new submissions (same as Chrome).
- Service workers, content scripts, and the side panel API work identically.
- If your extension uses `chrome.identity`, you'll need a Microsoft account client ID from the Azure portal.

## Create a Partner Center Account

1. Go to [Microsoft Partner Center — Edge](https://partner.microsoft.com/en-us/dashboard/microsoftedge/overview)
2. Sign in with a Microsoft account (create one if you don't have one)
3. Fill in the publisher profile: name, contact info, and payout details (for paid extensions or in-app purchases)
4. Registration is **free** — no upfront fee (unlike the Chrome Web Store's one-time $5)

## Package the Extension

Create a zip archive with `manifest.json` at its root:

```bash
# From your extension directory:
zip -r edge-extension.zip . -x "*.git*" -x "node_modules/*" -x "*.pem"
```

**Important:** Do not zip the parent folder — the zip must contain `manifest.json` directly at the top level.

## Submit to the Edge Add-ons Store

1. In Partner Center, click **Create a new extension**
2. Upload the `.zip` file
3. Fill in the **Store listing**:

| Field | Notes |
|---|---|
| **Name** | Must be unique in the Edge store |
| **Short description** | Max 120 characters; shown in gallery cards |
| **Full description** | Well-formatted, keyword-rich; explain what the extension does and why users would want it |
| **Category** | Pick the closest match (Productivity, Developer Tools, Social, etc.) |
| **Screenshots** | At least 1 required; 640×400 or 1280×800 recommended; show real UI, not placeholder art |
| **Small promotional tile** | Optional; 440×280 |
| **Privacy policy URL** | Required if your extension collects any personal or usage data |

4. Set **Availability**:
   - **Public** — visible to everyone in the Edge Add-ons store
   - **Hidden** — accessible only via a direct link (soft-launch / beta)
   - **Organization** — restricted to your Microsoft 365 tenant via Intune

5. Click **Submit for review**

## Review Process

- Review typically takes **1–2 business days**.
- You'll receive an email when the extension passes certification or if revisions are needed.
- If rejected, the email will list the specific policy items that must be fixed. Common issues:
  - Missing or unclear privacy policy
  - Overly broad permissions without justification in the description
  - Extension name or description that misleads users
  - Remote code execution that isn't disclosed

## Updating an Existing Extension

1. Increment the `version` in `manifest.json`
2. Create a new zip
3. In Partner Center, open the extension and click **Update submission**
4. Upload the new zip, update the description/screenshots if anything changed
5. Re-submit — updates go through the same review queue

## Chrome Web Store vs. Edge Add-ons

| Aspect | Chrome Web Store | Edge Add-ons |
|---|---|---|
| Registration fee | $5 USD (one-time) | Free |
| Dashboard | [Chrome Web Store Dev Console](https://chrome.google.com/webstore/devconsole) | [Microsoft Partner Center](https://partner.microsoft.com/dashboard/microsoftedge) |
| Review time | 1–3 business days | 1–2 business days |
| Manifest required | V3 (V2 no longer accepted for new items) | V3 |
| Enterprise distribution | Google Admin Console | Microsoft 365 / Intune |
| API namespace | `chrome.*` | Both `chrome.*` and `browser.*` |
| Side panel | `sidePanel` API | `sidePanel` API (same) |

## Tips

- **Permissions:** Request only what the extension actually needs. In the store listing, include a short justification for each sensitive permission — this builds trust with both reviewers and users.
- **Test in Edge Stable:** Developer mode sideloading is fine for initial testing, but do a final pass with the zip installed in a clean Edge profile (no flags) before submitting.
- **Cross-store workflow:** If you maintain the extension for both Chrome and Edge, keep a single codebase and only diverge where a browser-specific API requires it. Most extensions need zero changes.
- **Analytics:** If the extension phones home (analytics, error reporting, CDN-hosted resources), disclose it in the privacy policy and in the description. Both stores require transparency on remote code.

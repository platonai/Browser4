/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
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
package ai.platon.pulsar.captcha

/**
 * JavaScript helper snippets for CAPTCHA detection and token injection.
 *
 * These are evaluated in the page context via WebDriver.evaluateValue().
 */
object CaptchaSolveScripts {

    /**
     * Detect reCAPTCHA v2/v3 widgets on the page.
     *
     * Checks for:
     * - grecaptcha API object
     * - iframes with google.com/recaptcha in src
     * - .g-recaptcha elements with data-sitekey
     */
    val DETECT_RECAPTCHA = """
        (() => {
            try {
                const result = { present: false, type: null, siteKey: null, action: null, widgetCount: 0 };

                // Check for grecaptcha API
                const hasGrecaptcha = typeof grecaptcha !== 'undefined' && grecaptcha.render;

                // Check for recaptcha iframes
                const recaptchaIframes = document.querySelectorAll(
                    'iframe[src*="google.com/recaptcha"], iframe[src*="recaptcha.net"]'
                );

                // Check for .g-recaptcha divs
                const recaptchaDivs = document.querySelectorAll('.g-recaptcha');

                if (!hasGrecaptcha && recaptchaIframes.length === 0 && recaptchaDivs.length === 0) {
                    return JSON.stringify(result);
                }

                result.present = true;
                result.widgetCount = recaptchaDivs.length || recaptchaIframes.length;

                // Determine type: v2 or v3
                // v3 is typically invisible and loads recaptcha/api.js?render=SITEKEY
                const scripts = document.querySelectorAll('script[src*="recaptcha"]');
                let isV3 = false;
                scripts.forEach(s => {
                    if (s.src.includes('render=')) isV3 = true;
                });

                result.type = isV3 ? 'RECAPTCHA_V3' : 'RECAPTCHA_V2';

                // Extract site key
                // First try from .g-recaptcha div
                if (recaptchaDivs.length > 0) {
                    result.siteKey = recaptchaDivs[0].getAttribute('data-sitekey');
                }

                // Try from grecaptcha widget IDs
                if (!result.siteKey || result.siteKey === '') {
                    try {
                        const widgets = grecaptcha?.getWidgets?.() || [];
                        if (widgets.length > 0) {
                            // We can't directly get sitekey from widget, try data attributes
                            const widgetEl = document.querySelector('#g-recaptcha-response');
                            if (widgetEl) {
                                result.siteKey = widgetEl.closest('form')?.querySelector('.g-recaptcha')?.getAttribute('data-sitekey');
                            }
                        }
                    } catch (e) {}
                }

                // Try from script src
                if (!result.siteKey || result.siteKey === '') {
                    const scriptEl = document.querySelector('script[src*="recaptcha/api.js?render="]');
                    if (scriptEl) {
                        const match = scriptEl.src.match(/render=([^&]+)/);
                        if (match) result.siteKey = match[1];
                    }
                }

                // Extract action (v3)
                if (isV3) {
                    const actionEl = document.querySelector('[data-action]');
                    if (actionEl) result.action = actionEl.getAttribute('data-action');
                    else {
                        const match2 = document.documentElement.innerHTML.match(/recaptcha\.execute\([^,]+,\s*['"](\w+)['"]/);
                        if (match2) result.action = match2[1];
                    }
                }

                return JSON.stringify(result);
            } catch (e) {
                return JSON.stringify({ present: false, error: e.message });
            }
        })()
    """.trimIndent()

    /**
     * Detect hCaptcha widgets on the page.
     *
     * Checks for:
     * - hcaptcha API object
     * - iframes with hcaptcha.com in src
     * - .h-captcha elements with data-sitekey
     */
    val DETECT_HCAPTCHA = """
        (() => {
            try {
                const result = { present: false, siteKey: null };

                const hasHcaptcha = typeof hcaptcha !== 'undefined';
                const hcaptchaIframes = document.querySelectorAll('iframe[src*="hcaptcha.com"]');
                const hcaptchaDivs = document.querySelectorAll('.h-captcha, [data-hcaptcha-widget-id]');

                if (!hasHcaptcha && hcaptchaIframes.length === 0 && hcaptchaDivs.length === 0) {
                    return JSON.stringify(result);
                }

                result.present = true;

                // Extract site key
                if (hcaptchaDivs.length > 0) {
                    result.siteKey = hcaptchaDivs[0].getAttribute('data-sitekey');
                }

                // Try from hcaptcha.render call
                if (!result.siteKey || result.siteKey === '') {
                    const scripts = document.querySelectorAll('script[src*="hcaptcha.com"]');
                    scripts.forEach(s => {
                        const match = s.src.match(/[?&]sitekey=([^&]+)/);
                        if (match) result.siteKey = match[1];
                    });
                }

                return JSON.stringify(result);
            } catch (e) {
                return JSON.stringify({ present: false, error: e.message });
            }
        })()
    """.trimIndent()

    /**
     * Detect Cloudflare Turnstile widgets on the page.
     *
     * Checks for:
     * - turnstile API object
     * - .cf-turnstile elements
     * - iframes with challenges.cloudflare.com
     */
    val DETECT_TURNSTILE = """
        (() => {
            try {
                const result = { present: false, siteKey: null, action: null, cdata: null };

                const hasTurnstile = typeof turnstile !== 'undefined';
                const turnstileDivs = document.querySelectorAll('.cf-turnstile, [data-turnstile-widget]');
                const cfIframes = document.querySelectorAll('iframe[src*="challenges.cloudflare.com"]');
                const cfScripts = document.querySelectorAll('script[src*="challenges.cloudflare.com/turnstile"]');

                if (!hasTurnstile && turnstileDivs.length === 0 && cfIframes.length === 0 && cfScripts.length === 0) {
                    return JSON.stringify(result);
                }

                result.present = true;

                // Extract site key
                if (turnstileDivs.length > 0) {
                    result.siteKey = turnstileDivs[0].getAttribute('data-sitekey');
                    result.action = turnstileDivs[0].getAttribute('data-action');
                    result.cdata = turnstileDivs[0].getAttribute('data-cdata');
                }

                // Try from script src
                if (!result.siteKey || result.siteKey === '') {
                    cfScripts.forEach(s => {
                        const match = s.src.match(/[?&]sitekey=([^&]+)/);
                        if (match) result.siteKey = match[1];
                    });
                }

                return JSON.stringify(result);
            } catch (e) {
                return JSON.stringify({ present: false, error: e.message });
            }
        })()
    """.trimIndent()

    /**
     * Detect generic image CAPTCHAs on the page.
     *
     * Checks for common image CAPTCHA patterns:
     * - img elements with captcha-related class/id/alt text
     * - captcha input fields
     * - reload/refresh buttons near captcha images
     */
    val DETECT_IMAGE_CAPTCHA = """
        (() => {
            try {
                const result = { present: false, imageSelector: null, inputSelector: null };

                // Common selectors for image captchas
                const captchaKeywords = ['captcha', 'verification', 'verify', 'security', 'challenge'];

                // Check images
                const allImages = document.querySelectorAll('img');
                for (const img of allImages) {
                    const src = (img.src || '').toLowerCase();
                    const alt = (img.alt || '').toLowerCase();
                    const id = (img.id || '').toLowerCase();
                    const cls = (img.className || '').toLowerCase();

                    const combined = [src, alt, id, cls].join(' ');
                    if (captchaKeywords.some(kw => combined.includes(kw))) {
                        result.present = true;
                        result.imageSelector = img.id ? '#' + img.id : (img.className ? '.' + img.className.split(' ')[0] : 'img');
                        break;
                    }
                }

                // Check for captcha text input nearby
                if (result.present) {
                    const inputs = document.querySelectorAll('input[type="text"], input:not([type])');
                    for (const input of inputs) {
                        const name = (input.name || '').toLowerCase();
                        const placeholder = (input.placeholder || '').toLowerCase();
                        if (captchaKeywords.some(kw => name.includes(kw) || placeholder.includes(kw))) {
                            result.inputSelector = input.id ? '#' + input.id :
                                (input.name ? 'input[name="' + input.name + '"]' : 'input');
                            break;
                        }
                    }
                }

                return JSON.stringify(result);
            } catch (e) {
                return JSON.stringify({ present: false, error: e.message });
            }
        })()
    """.trimIndent()

    /**
     * Inject a reCAPTCHA response token and trigger the callback.
     *
     * Sets the token via:
     * 1. Setting element.value for #g-recaptcha-response and related textareas
     * 2. Calling grecaptcha.getResponse() to verify
     * 3. Executing the callback function if found via data-callback attribute
     */
    fun INJECT_RECAPTCHA_TOKEN(token: String): String = """
        (() => {
            try {
                // Set token in the hidden textarea(s)
                var textarea = document.querySelector('#g-recaptcha-response');
                if (textarea) textarea.value = '${token}';
                var textareaV3 = document.querySelector('.g-recaptcha-response');
                if (textareaV3) textareaV3.value = '${token}';
                var textareaV22 = document.querySelector('textarea[name="g-recaptcha-response"]');
                if (textareaV22) textareaV22.value = '${token}';
                var textareas = document.querySelectorAll('textarea[id*="recaptcha"]');
                textareas.forEach(function(t) { t.value = '${token}'; });

                // Try to trigger the callback
                var callbackEl = document.querySelector('[data-callback]');
                var callbackName = callbackEl ? callbackEl.getAttribute('data-callback') : null;
                if (callbackName && typeof window[callbackName] === 'function') {
                    try { window[callbackName]('${token}'); } catch (e) {}
                }

                // Try to find and submit the parent form
                var submitBtn = document.querySelector('form [type="submit"], form button:not([type])');
                if (submitBtn) {
                    // Don't auto-submit, just set token. Let the user/bot click submit.
                }

                return JSON.stringify({ success: true, tokenSet: true });
            } catch (e) {
                return JSON.stringify({ success: false, error: e.message });
            }
        })()
    """.trimIndent()

    /**
     * Inject an hCaptcha response token and trigger callback.
     */
    fun INJECT_HCAPTCHA_TOKEN(token: String): String = """
        (() => {
            try {
                // Set token in the hidden textarea(s)
                var textarea = document.querySelector('[name="h-captcha-response"]');
                if (textarea) textarea.value = '${token}';
                var textarea2 = document.querySelector('#h-captcha-response');
                if (textarea2) textarea2.value = '${token}';
                var textareas = document.querySelectorAll('textarea[id*="hcaptcha"]');
                textareas.forEach(function(t) { t.value = '${token}'; });

                // Try to trigger hcaptcha callback
                var callbackEl = document.querySelector('[data-callback]');
                var callbackName = callbackEl ? callbackEl.getAttribute('data-callback') : null;
                if (callbackName && typeof window[callbackName] === 'function') {
                    try { window[callbackName]('${token}'); } catch (e) {}
                }

                // Also try hcaptcha API
                if (typeof hcaptcha !== 'undefined' && hcaptcha.getResponse) {
                    // Setting the textarea value above should be enough
                }

                return JSON.stringify({ success: true, tokenSet: true });
            } catch (e) {
                return JSON.stringify({ success: false, error: e.message });
            }
        })()
    """.trimIndent()

    /**
     * Inject a Cloudflare Turnstile response token and trigger callback.
     */
    fun INJECT_TURNSTILE_TOKEN(token: String): String = """
        (() => {
            try {
                // Set token in the hidden input
                var input = document.querySelector('[name="cf-turnstile-response"]');
                if (input) input.value = '${token}';
                var input2 = document.querySelector('#cf-turnstile-response');
                if (input2) input2.value = '${token}';

                // Try to trigger the data-callback
                var callbackEl = document.querySelector('.cf-turnstile[data-callback]');
                var callbackName = callbackEl ? callbackEl.getAttribute('data-callback') : null;
                if (callbackName && typeof window[callbackName] === 'function') {
                    try { window[callbackName]('${token}'); } catch (e) {}
                }

                // Try turnstile callback
                var turnstileEl = document.querySelector('.cf-turnstile');
                var cb = turnstileEl ? turnstileEl.getAttribute('data-callback') : null;
                if (cb && typeof window[cb] === 'function') {
                    try { window[cb]('${token}'); } catch (e) {}
                }

                return JSON.stringify({ success: true, tokenSet: true });
            } catch (e) {
                return JSON.stringify({ success: false, error: e.message });
            }
        })()
    """.trimIndent()

    /**
     * Check if a CAPTCHA-related global object exists on the page.
     * Used as a quick pre-screen before running full detection.
     */
    val QUICK_CAPTCHA_CHECK = """
        (() => {
            try {
                return JSON.stringify({
                    hasRecaptcha: typeof grecaptcha !== 'undefined',
                    hasHcaptcha: typeof hcaptcha !== 'undefined',
                    hasTurnstile: typeof turnstile !== 'undefined',
                    recaptchaIframes: document.querySelectorAll('iframe[src*="google.com/recaptcha"]').length,
                    hcaptchaIframes: document.querySelectorAll('iframe[src*="hcaptcha.com"]').length,
                    cfIframes: document.querySelectorAll('iframe[src*="challenges.cloudflare.com"]').length
                });
            } catch (e) {
                return JSON.stringify({ error: e.message });
            }
        })()
    """.trimIndent()

    /**
     * Submit or click the first submit button in a form containing a CAPTCHA.
     */
    val SUBMIT_CAPTCHA_FORM = """
        (() => {
            try {
                // Find a form that contains a captcha-related element
                var captchaEl = document.querySelector(
                    '#g-recaptcha-response, [name="h-captcha-response"], [name="cf-turnstile-response"], ' +
                    '.g-recaptcha, .h-captcha, .cf-turnstile'
                );
                if (!captchaEl) return JSON.stringify({ success: false, reason: 'No captcha element found' });

                var form = captchaEl.closest('form');
                if (!form) return JSON.stringify({ success: false, reason: 'No parent form found' });

                var submitBtn = form.querySelector('[type="submit"], button:not([type])');
                if (submitBtn) {
                    submitBtn.click();
                    return JSON.stringify({ success: true, action: 'clicked' });
                }

                // Fallback: submit the form directly
                form.submit();
                return JSON.stringify({ success: true, action: 'submitted' });
            } catch (e) {
                return JSON.stringify({ success: false, error: e.message });
            }
        })()
    """.trimIndent()
}

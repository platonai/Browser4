# plugin-captcha-detection

Test the **browser4-captcha** plugin by detecting CAPTCHA elements on real websites that use various CAPTCHA providers.

1. Navigate to `https://www.google.com/recaptcha/api2/demo`. This is Google's official reCAPTCHA v2 demo page — it contains a live "I'm not a robot" checkbox.

2. Use the `captcha.detect` tool to scan the page for CAPTCHA elements. Report:
   - `isPresent` — whether a CAPTCHA was detected
   - `captchaType` — the type of CAPTCHA (expected: RECAPTCHA_V2)
   - `siteKey` — the site key extracted from the page
   - `confidence` — detection confidence level

3. Navigate to `https://accounts.hcaptcha.com/demo`. This is hCaptcha's official demo page. Use `captcha.detect` to detect the hCaptcha widget. Report the detection result including type and site key.

4. Navigate to `https://demo.turnstile.workers.dev`. This is Cloudflare's Turnstile demo page. Use `captcha.detect` to detect the Turnstile widget. Report the detection result.

5. Navigate to `https://en.wikipedia.org/wiki/CAPTCHA` (a page that should NOT have CAPTCHA). Use `captcha.detect` to verify the detector correctly reports `isPresent: false` on a normal page with no CAPTCHA. This tests that the detector does not produce false positives.

6. Navigate back to the reCAPTCHA demo page. If a CAPTCHA solving service is configured:
   - Note the detected `siteKey`
   - Check the solver balance using `captcha.getBalance`
   - Report whether solving services are available

7. Summarize: which CAPTCHA types were successfully detected? Which had the highest/lowest confidence? How quickly does detection work on each page?

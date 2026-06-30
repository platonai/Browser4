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
 * Enumeration of supported CAPTCHA types.
 */
enum class CaptchaType {
    /** Google reCAPTCHA v2 — "I'm not a robot" checkbox */
    RECAPTCHA_V2,

    /** Google reCAPTCHA v2 invisible — no checkbox, triggered by user action */
    RECAPTCHA_V2_INVISIBLE,

    /** Google reCAPTCHA v3 — score-based, no user interaction */
    RECAPTCHA_V3,

    /** Google reCAPTCHA Enterprise */
    RECAPTCHA_ENTERPRISE,

    /** hCaptcha — privacy-focused alternative to reCAPTCHA */
    HCAPTCHA,

    /** hCaptcha Enterprise */
    HCAPTCHA_ENTERPRISE,

    /** Cloudflare Turnstile — privacy-preserving CAPTCHA alternative */
    TURNSTILE,

    /** Traditional image-based CAPTCHA (text in distorted image) */
    IMAGE,

    /** Arkose Labs FunCaptcha (rotate/click puzzles) */
    FUNCAPTCHA,

    /** Unknown or unrecognized CAPTCHA type */
    UNKNOWN
}

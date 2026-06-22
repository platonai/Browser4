# Amazon serves Chinese locale and HKD pricing when accessed from Asian IPs

**Severity:** Low  
**Category:** Enhancement

## Summary

When navigating to `https://www.amazon.com/` from an Asian IP address, Amazon automatically serves the Chinese (ZH) locale with HKD pricing. This can be unexpected and confusing for users who intend to interact with the US English version of the site.

## Steps to Reproduce

1. From an Asian IP address, run `browser4-cli goto https://www.amazon.com/`.
2. Inspect the page content and pricing.

## Expected Behavior

The site is served in English with USD pricing, matching the `.com` domain, or there is a documented way to request a specific locale.

## Actual Behavior

- Page text and accessibility ref names appear in Chinese.
- Pricing is displayed in HKD rather than USD.

## Impact

Element text, ref names, and labels are in Chinese, which complicates automation scripts that search for English strings. Pricing is in a non-USD currency, which may not match the user's expectations or requirements.

## Suggested Fix

- Document this locale behavior so users are aware of it.
- Consider adding a `--locale` or `--accept-language` header option to allow users to specify their preferred language and region.


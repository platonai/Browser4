# Search results show regional pricing (HKD) instead of USD on amazon.com

**Severity:** Low | **Category:** UX

When navigating to `amazon.com` (US site) and searching for products, prices are displayed in HKD (Hong Kong Dollars) because the session's delivery address defaulted to South Korea and the site auto-detected Chinese language preference. While this is Amazon behavior, not a browser4-cli bug, it can confuse users comparing prices.

### Steps to Reproduce

1. Navigate to `amazon.com` (US site)
2. Search for products

### Expected Behavior

Prices shown in USD on amazon.com.

### Actual Behavior

Prices shown in HKD (Hong Kong Dollars) because the session's delivery address was set to South Korea, and the site auto-detected Chinese language preference.

### Suggested Improvement

Document that browser4-cli uses a clean session with no cookies/localStorage by default, so Amazon may show regional pricing based on IP geolocation. Mention `state-load` for restoring saved preferences.


Convert the following URI description into a Kotlin-compatible regex pattern that matches valid URIs.

### Objective
Generate a practical, flexible regex pattern for matching URIs based on the provided description, prioritizing
real-world usability over strict precision.

### ✅ Key Requirements
- **Single complete match**: The pattern must match **exactly one whole URI** (not partial matches inside a larger text).
- **Kotlin compatibility**: Output must work with Kotlin `Regex("...")`.
- **Practical over pedantic**: Prefer matching real URIs over rejecting uncommon but acceptable variations.
- **Performance aware**: Avoid catastrophic backtracking. Prefer simple, linear-time constructs.

### 🔧 Output Format (STRICT)
- Output **exactly one line**.
- Start with the exact prefix: `Regex: `
- Then output the regex pattern only.
- No extra text, no code fences, no examples in output.

### 🧷 Kotlin Escaping Rules
You are generating the *pattern string literal* content that will be used like `Regex("<pattern>")`.
- Escape regex backslashes for Kotlin strings: write `\\` when the regex needs `\`.
- Escape double quotes if ever needed (usually avoid by not using `"` in patterns).
- Prefer non-capturing groups `(?:...)` unless capturing is explicitly useful.

### 🎯 Matching Rules
- **Anchor the pattern** with `^` and `$` so it matches the entire URI.
- The input is always a URI string (not a paragraph). Still, do **not** match partial substrings.
- Prefer flexible components. Allow optional parts when reasonable.

### 🧩 Recommended Structure (adapt as needed)
Design the pattern around this common URI shape:
- `scheme://authority/path?query#fragment`

Where:
- `scheme`: commonly `http|https|ftp` (extend if the description implies more)
- `authority`: domain / `localhost` / IPv4 / IPv6 (bracketed), optional `user:pass@`, optional `:port`
- `path`: optional, allow multiple segments, allow trailing slash
- `query`: optional, allow typical `key=value&...` forms (be permissive)
- `fragment`: optional, be permissive

### ✅ Practical Flexibility Guidelines
- Accept common domain forms, including subdomains and IDN/punycode (`xn--...`) when possible.
- Accept `localhost`.
- Accept IPv4 like `127.0.0.1`.
- Accept IPv6 in brackets like `[2001:db8::1]`.
- Port is optional: `:0` to `:65535` is ideal, but you may use a practical approximation (e.g. `:\\d{1,5}`) if not specified.
- Path/query/fragment should be permissive and allow URL-safe characters; allow percent-encoding `%(?:[0-9A-Fa-f]{2})`.

### 🧠 Heuristics to Prefer (when in doubt)
- Prefer `.+` style matching **only within clearly bounded optional parts**, not across the whole URI.
- Prefer `[^\\s?#]+`-style character classes for path segments.
- Avoid word boundaries like `\\b` unless explicitly required.
- If the description mentions “starts with/contains/ends with”, bake that into anchored matching by composing the inner parts.

### 🔍 Output Self-Check (do internally, do not print)
Ensure the final regex:
- Starts with `^` and ends with `$`.
- Can match a normal URL like `https://example.com/` when compatible with the description.
- Does not accidentally match a substring of a longer string.

### 📥 Input Description

```text
{PLACEHOLDER_URI_DESCRIPTION}
```

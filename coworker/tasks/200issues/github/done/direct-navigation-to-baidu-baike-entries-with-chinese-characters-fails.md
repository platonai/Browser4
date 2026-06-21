# Direct navigation to Baidu Baike entries with Chinese characters fails

Navigating directly to a Baidu Baike (encyclopedia) URL containing Chinese characters results in a 404, likely due to incorrect URL encoding or an invalid URL format guess.

## Steps to reproduce

```
browser4-cli goto "https://baike.baidu.com/item/武汉小龙虾消费季"
```

## Expected behavior

The Baidu Baike page for "武汉小龙虾消费季" loads.

## Actual behavior

A 404 Not Found error is returned.

## Additional context

- The exact URL format for Baidu Baike entries may require percent-encoding of Chinese characters, or the item path may differ from what a user would guess.
- SKILL.md could include a tip about URL encoding for Chinese-character URLs, or a helper command for constructing valid Baidu Baike URLs from a search term.


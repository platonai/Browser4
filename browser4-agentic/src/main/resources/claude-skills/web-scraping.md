# Web Scraping Skill

Extract data from web pages using CSS selectors and Browser4's web automation capabilities.

## When to Use This Skill

Use this skill when you need to:
- Extract text, links, images, or other content from web pages
- Collect data from multiple similar elements (like product listings, articles, etc.)
- Gather structured data from HTML tables or lists
- Monitor changes in web page content

## How to Use This Skill

### Step 1: Navigate to the Target Page

First, navigate to the web page you want to scrape:
- Use the `browser.navigate` tool with the target URL
- Wait for the page to fully load before proceeding

### Step 2: Identify Target Elements

Determine which elements contain the data you need:
- Inspect the page using browser developer tools (right-click → Inspect)
- Find unique CSS selectors for the target elements
- Choose selectors that are stable and unlikely to change

Common selector patterns:
- **Class selector**: `.className` - targets elements with a specific class
- **ID selector**: `#elementId` - targets a unique element by ID
- **Tag selector**: `tagname` - targets all elements of a type (div, p, a, etc.)
- **Attribute selector**: `[attribute="value"]` - targets elements with specific attributes
- **Descendant selector**: `parent child` - targets children within a parent
- **Direct child**: `parent > child` - targets direct children only

### Step 3: Extract the Data

Use the `browser.extract` tool to get data:
- Specify the CSS selector for your target elements
- Choose which attributes to extract:
  - `text` - the text content of the element
  - `href` - for links
  - `src` - for images and scripts
  - `data-*` - custom data attributes
  - `innerHTML` - HTML content including tags
  - `outerHTML` - the element itself with all its HTML

### Step 4: Handle Dynamic Content

If the page loads content dynamically (via JavaScript):
- Use `browser.wait_for_element` with your selector before extracting
- Set an appropriate timeout (usually 5-30 seconds)
- Consider waiting for specific elements that indicate content is ready

### Step 5: Process Multiple Elements

When extracting from multiple similar elements:
- Use a selector that matches all target elements
- The tool will return an array of results
- Process each result as needed

## Examples

### Example 1: Extract Article Titles

**Task**: Extract all article titles from a news website

**Steps**:
1. Navigate to `https://example-news.com`
2. Wait for articles to load
3. Use selector `h2.article-title` or `article h2`
4. Extract the `text` attribute

**Expected Result**:
```
["Breaking News: Major Event", "Technology Update: New Release", ...]
```

### Example 2: Extract Product Information

**Task**: Gather product names and prices from an e-commerce site

**Steps**:
1. Navigate to `https://example-shop.com/products`
2. Wait for product grid to load
3. Use selector `.product-card` to target product containers
4. Extract multiple attributes:
   - `.product-card h3` for product names (text)
   - `.product-card .price` for prices (text)
   - `.product-card a` for product links (href)

**Expected Result**:
```
Product: "Premium Laptop", Price: "$1,299.99", Link: "/products/laptop-123"
Product: "Wireless Mouse", Price: "$29.99", Link: "/products/mouse-456"
...
```

### Example 3: Extract Data from a Table

**Task**: Extract data from an HTML table

**Steps**:
1. Navigate to the page with the table
2. Use selector `table tr` to get all rows
3. Within each row, use `td` or `th` to get cells
4. Extract `text` from each cell

**Selector Tips**:
- First row (headers): `table thead tr th`
- Data rows: `table tbody tr td`
- Specific column: `table tbody tr td:nth-child(2)` (2nd column)

### Example 4: Handle Pagination

**Task**: Extract data from multiple pages

**Process**:
1. Extract data from the current page (as above)
2. Look for pagination links: `a.next-page` or `button.next`
3. Click the next page button: `browser.click("a.next-page")`
4. Wait for new content to load
5. Repeat extraction
6. Continue until no more next page button exists

## Best Practices

### Selector Stability
- **Prefer IDs and stable classes**: Avoid selectors based on generated classes like `css-xyz123`
- **Use semantic HTML**: Target semantic tags (`<article>`, `<nav>`, `<section>`) when available
- **Test selectors**: Always verify selectors return the expected elements

### Performance
- **Be specific**: More specific selectors are faster than broad ones
- **Limit scope**: Use descendant selectors to narrow the search area
- **Batch operations**: Extract multiple attributes in one call when possible

### Reliability
- **Wait for content**: Always wait for dynamic content to load
- **Handle missing elements**: Check if extraction succeeded before processing
- **Retry on failure**: If extraction fails, wait and try again (may be loading)

### Ethics and Legality
- **Respect robots.txt**: Check the site's robots.txt file for restrictions
- **Rate limiting**: Don't scrape too quickly; add delays between requests
- **Terms of service**: Ensure scraping complies with the website's terms
- **User agent**: Identify your bot properly in the user agent string

### Data Quality
- **Clean extracted data**: Remove extra whitespace, normalize formats
- **Validate data**: Check that extracted data matches expected patterns
- **Handle errors**: Plan for missing or malformed data

## Common Issues

### Issue 1: Empty Results
**Problem**: The selector returns no data

**Solutions**:
- Verify selector in browser DevTools (Console: `document.querySelectorAll('your-selector')`)
- Check if content is loaded (wait longer or check different selector)
- Ensure selector syntax is correct
- Check if content is in an iframe (need to switch context)

### Issue 2: Incorrect Data
**Problem**: Extraction returns unexpected content

**Solutions**:
- Refine selector to be more specific
- Check if multiple elements match (might need to narrow scope)
- Verify you're extracting the right attribute
- Inspect the actual HTML structure (may differ from what you see visually)

### Issue 3: Timeout Errors
**Problem**: Page takes too long to load

**Solutions**:
- Increase timeout value
- Wait for a specific element rather than a fixed time
- Check if the page has completed loading
- Handle slow connections gracefully

### Issue 4: Dynamic Content Not Loading
**Problem**: Content loaded via JavaScript doesn't appear

**Solutions**:
- Use `wait_for_element` with the selector before extracting
- Wait for network requests to complete
- Look for loading indicators to disappear
- Try scrolling to trigger lazy-loaded content

### Issue 5: Stale Element Reference
**Problem**: Element reference becomes invalid

**Solutions**:
- Re-select the element after page updates
- Extract data immediately after selection
- Handle page changes by re-navigating or refreshing

## Troubleshooting Guide

### Step 1: Verify Page Loaded
```
Check: Can you see the content in the browser?
If NO: Increase wait time or check URL
If YES: Continue to Step 2
```

### Step 2: Test Selector
```
Open browser DevTools → Console
Run: document.querySelectorAll('your-selector')
Check: Does it return the expected elements?
If NO: Fix selector
If YES: Continue to Step 3
```

### Step 3: Check Attribute
```
Verify: Are you extracting the correct attribute?
For text: Use 'text'
For links: Use 'href'
For images: Use 'src'
If unsure: Extract 'outerHTML' to see full element
```

### Step 4: Handle Timing
```
If content is dynamic:
- Add wait_for_element call
- Increase timeout
- Look for loading indicators
```

## Advanced Techniques

### Technique 1: Extract from Shadow DOM
Some modern websites use Shadow DOM. To access:
1. Identify shadow host element
2. Use special selectors or JavaScript to pierce shadow root
3. Extract from shadow tree content

### Technique 2: Handle Infinite Scroll
For pages that load content on scroll:
1. Scroll to bottom of page
2. Wait for new content to load
3. Repeat until no new content appears
4. Then extract all loaded content

### Technique 3: Extract Structured Data
For JSON-LD or microdata:
1. Look for `<script type="application/ld+json">` tags
2. Extract the script content
3. Parse as JSON for structured data
4. Often more reliable than scraping HTML

## Related Skills

- **browser-automation.md**: For fundamental browser control (navigation, clicking, waiting)
- **data-validation.md**: For validating extracted data against patterns and rules
- **form-filling.md**: Often used together when interacting with search forms before scraping results

## Tool Reference

The following Browser4 tools are commonly used with this skill:

- `browser.navigate(url)` - Navigate to a web page
- `browser.extract(selector, attributes)` - Extract data using CSS selectors
- `browser.wait_for_element(selector, timeout)` - Wait for elements to appear
- `browser.click(selector)` - Click elements (for pagination, expanding content)
- `browser.scroll(direction, amount)` - Scroll the page (for lazy-loaded content)
- `browser.get_page_source()` - Get raw HTML for complex parsing

## Summary

Web scraping with Browser4 involves:
1. **Navigate** to the target page
2. **Wait** for content to load
3. **Identify** stable CSS selectors
4. **Extract** data using appropriate attributes
5. **Validate** and clean the extracted data

Always be respectful of website resources, follow their terms of service, and implement appropriate rate limiting and error handling.

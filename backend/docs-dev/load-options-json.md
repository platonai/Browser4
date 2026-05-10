# LoadOptions JSON Format

LoadOptions can be converted to/from JSON format for easy configuration and API usage.

## Basic Usage

### Converting LoadOptions to JSON

```kotlin
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.options.LoadOptionsJson

// Create LoadOptions from command-line style arguments
val options = LoadOptions.parse("-expires 1d -ignoreFailure -parse")

// Convert to JSON (only modified/non-default values)
val json = LoadOptionsJson.toJson(options)
// Result: {"expires":"1d","ignoreFailure":true,"parse":true}

// Convert to JSON with all default values included
val fullJson = LoadOptionsJson.toJson(options, includeDefaults = true)
```

### Creating LoadOptions from JSON

```kotlin
val json = """
{
    "expires": "1d",
    "ignoreFailure": true,
    "parse": true,
    "topLinks": 50
}
"""

val options = LoadOptionsJson.fromJson(json)
```

## JSON Template

The full JSON template with all available fields and their default values:

```json
{
  "entity": "",
  "label": "",
  "taskId": "",
  "taskTime": "1970-01-01T00:00:00Z",
  "deadline": "2200-01-01T00:00:00Z",
  "authToken": "",
  "readonly": false,
  "isResource": false,
  "priority": 0,
  "expires": "5259492m",
  "expireAt": "2200-01-01T00:00:00Z",
  "outLinkSelector": "",
  "outLinkPattern": ".+",
  "iframe": 0,
  "topLinks": 20,
  "waitNonBlank": "",
  "requireNotBlank": "",
  "requireSize": 0,
  "requireImages": 0,
  "requireAnchors": 0,
  "fetchMode": "BROWSER",
  "browser": "PULSAR_CHROME",
  "autoScrollCount": 1,
  "scrollInterval": "500ms",
  "scriptTimeout": "1m",
  "pageLoadTimeout": "3m",
  "itemBrowser": "PULSAR_CHROME",
  "itemExpires": "5259492m",
  "itemExpireAt": "2200-01-01T00:00:00Z",
  "itemScrollCount": 1,
  "itemScrollInterval": "500ms",
  "itemScriptTimeout": "1m",
  "itemPageLoadTimeout": "3m",
  "itemWaitNonBlank": "",
  "itemRequireNotBlank": "",
  "itemRequireSize": 0,
  "itemRequireImages": 0,
  "itemRequireAnchors": 0,
  "persist": true,
  "storeContent": true,
  "dropContent": false,
  "refresh": false,
  "ignoreFailure": false,
  "nMaxRetry": 3,
  "nJitRetry": -1,
  "lazyFlush": true,
  "incognito": false,
  "parse": false,
  "reparseLinks": false,
  "ignoreUrlQuery": false,
  "noNorm": false,
  "noFilter": false,
  "netCondition": "GOOD",
  "interactLevel": "DEFAULT",
  "test": 0,
  "version": "20220918"
}
```

## Field Descriptions

### Task Configuration

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| entity | String | "" | The type of content being crawled (e.g., article, product, hotel) |
| label | String | "" | A label to categorize tasks into logical groups |
| taskId | String | "" | A unique identifier for distinguishing between separate tasks |
| taskTime | Instant | EPOCH | A timestamp used to group related tasks into a batch |
| deadline | Instant | 2200-01-01 | Absolute deadline after which the task should be discarded |
| authToken | String | "" | Authentication token for authorized access to protected resources |

### Fetch Behavior

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| readonly | Boolean | false | When enabled, operates in non-destructive mode |
| isResource | Boolean | false | When true, fetches the URL as a basic resource without browser rendering |
| priority | Int | 0 | Task execution priority (lower = higher priority) |
| expires | Duration | 5259492m | Duration after which cached content is considered stale |
| expireAt | Instant | 2200-01-01 | Absolute timestamp after which cached content should be refetched |
| fetchMode | FetchMode | BROWSER | Mechanism used to fetch web content |
| browser | BrowserType | PULSAR_CHROME | Browser engine to use for rendering pages |

### Page Interaction

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| autoScrollCount | Int | 1 | Number of times to scroll down the page after initial load |
| scrollInterval | Duration | 500ms | Time interval between successive scroll actions |
| scriptTimeout | Duration | 1m | Maximum time allowed for injected JavaScript execution |
| pageLoadTimeout | Duration | 3m | Maximum time to wait for page loading to complete |
| interactLevel | InteractLevel | DEFAULT | Controls the level of interaction with web pages |

### Content Validation

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| waitNonBlank | String | "" | CSS selector for element that must contain non-blank text |
| requireNotBlank | String | "" | CSS selector for element that must contain non-blank text for valid retrieval |
| requireSize | Int | 0 | Minimum acceptable page size in bytes |
| requireImages | Int | 0 | Minimum number of images required |
| requireAnchors | Int | 0 | Minimum number of anchor elements required |

### Link Extraction

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| outLinkSelector | String | "" | CSS selector to identify and extract links from portal pages |
| outLinkPattern | String | ".+" | Regular expression pattern to filter extracted outlinks |
| topLinks | Int | 20 | Maximum number of outlinks to extract from a single page |

### Item Page Options

Item-specific options apply when processing detail pages (as opposed to index pages):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| itemBrowser | BrowserType | PULSAR_CHROME | Browser to use for item detail pages |
| itemExpires | Duration | 5259492m | Cache expiration for item detail pages |
| itemExpireAt | Instant | 2200-01-01 | Absolute timestamp for item page expiration |
| itemScrollCount | Int | 1 | Number of scroll actions for item pages |
| itemScrollInterval | Duration | 500ms | Time interval between scrolls for item pages |
| itemScriptTimeout | Duration | 1m | JavaScript execution timeout for item pages |
| itemPageLoadTimeout | Duration | 3m | Page load timeout for item pages |
| itemWaitNonBlank | String | "" | CSS selector for non-blank text on item pages |
| itemRequireNotBlank | String | "" | CSS selector for content validation on item pages |
| itemRequireSize | Int | 0 | Minimum page size for item pages |
| itemRequireImages | Int | 0 | Minimum images for item pages |
| itemRequireAnchors | Int | 0 | Minimum links for item pages |

### Storage Options

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| persist | Boolean | true | Controls whether fetched pages are persisted to storage |
| storeContent | Boolean | true | Controls whether page content (HTML) is stored |
| dropContent | Boolean | false | When enabled, page content will not be stored |
| lazyFlush | Boolean | true | Controls when pages are flushed to the database |

### Retry Behavior

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| refresh | Boolean | false | Forces an immediate fetch, ignoring cache and past failures |
| ignoreFailure | Boolean | false | Attempts to fetch a page even if previous fetch attempts failed |
| nMaxRetry | Int | 3 | Maximum number of fetch retries before marking as failed |
| nJitRetry | Int | -1 | Maximum number of immediate retries during a single fetch |

### Parsing Options

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| parse | Boolean | false | Enables immediate parsing of fetched pages |
| reparseLinks | Boolean | false | Forces re-parsing of links even for previously parsed pages |
| ignoreUrlQuery | Boolean | false | Removes query parameters from URLs |
| noNorm | Boolean | false | Disables URL normalization |
| noFilter | Boolean | false | Disables URL filtering |

## Duration Format

Durations can be specified in human-readable format:
- `100ms` - milliseconds
- `30s` - seconds
- `5m` - minutes
- `2h` - hours
- `7d` - days

## Enum Values

### FetchMode
- `BROWSER` - Fetch using a real browser
- `UNKNOWN` - Unknown fetch mode

### BrowserType
- `PULSAR_CHROME` - Browser4's optimized Chrome implementation (recommended)
- `PLAYWRIGHT_CHROME` - Playwright's Chrome implementation
- `NATIVE` - Raw HTTP client

### InteractLevel
- `FASTEST` - Minimal interaction, maximum speed
- `FASTER` - Slight interaction, very fast
- `FAST` - Moderate interaction, fast
- `DEFAULT` - Balanced (recommended)
- `GOOD_DATA` - Improved data completeness
- `BETTER_DATA` - Better content extraction
- `BEST_DATA` - Full interaction, best data quality

### Condition (deprecated)
- `BEST`, `BETTER`, `GOOD`, `WORSE`, `WORST`

## API Usage

### Java Usage

```java
import ai.platon.pulsar.skeleton.common.options.LoadOptions;
import ai.platon.pulsar.skeleton.common.options.LoadOptionsJson;

// To JSON
LoadOptions options = LoadOptions.parse("-expires 1d", VolatileConfig.UNSAFE);
String json = LoadOptionsJson.toJson(options);

// From JSON
LoadOptions options2 = LoadOptionsJson.fromJson(json);

// Generate template
String template = LoadOptionsJson.generateJsonTemplate();
```

### REST API Example

```bash
curl -X POST http://localhost:8182/api/x/scrape \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://example.com",
    "options": {
      "expires": "1d",
      "parse": true,
      "topLinks": 50
    }
  }'
```

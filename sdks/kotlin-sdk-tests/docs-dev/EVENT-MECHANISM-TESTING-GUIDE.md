# Event Mechanism Testing Guide

## Overview

This guide documents the event mechanism in Browser4 and how to test it. Events are emitted during the page lifecycle and transmitted to clients via Server-Sent Events (SSE).

## Event Architecture

### Event Flow

```
Page Lifecycle → GlobalEventHandlers → ServerSideEventHandlers → SSE Stream → Client
```

1. **Page Lifecycle**: Events are triggered at various stages during page crawling, loading, and browsing
2. **GlobalEventHandlers**: Central dispatcher that receives events from different components
3. **ServerSideEventHandlers**: Converts events to ServerSideEvent objects and broadcasts them
4. **SSE Stream**: Server-Sent Events stream accessible via `/api/commands/{id}/stream`
5. **Client**: Receives and processes events in real-time

### Event Categories

Browser4 emits three categories of events:

1. **CrawlEventHandlers** - Wraps around load/browse phases
   - `onWillLoad` - Before URL is loaded in crawl loop
   - `onLoaded` - After URL is loaded in crawl loop

2. **LoadEventHandlers** - Page loading and parsing phase
   - `onNormalize` - URL normalization
   - `onWillLoad` - Before page load
   - `onWillFetch` - Before network fetch
   - `onFetched` - After network fetch
   - `onWillParse` - Before parsing
   - `onWillParseHTMLDocument` - Before HTML parsing
   - `onHTMLDocumentParsed` - After HTML parsing (with document)
   - `onParsed` - After parsing complete
   - `onLoaded` - After page load complete

3. **BrowseEventHandlers** - Browser interaction phase (when using browser)
   - `onWillLaunchBrowser` - Before browser launch
   - `onBrowserLaunched` - After browser launched
   - `onWillFetch` - Before browser fetch
   - `onWillNavigate` - Before browser navigation
   - `onNavigated` - After navigation complete
   - `onWillInteract` - Before interaction phase
   - `onWillCheckDocumentState` - Before document state check
   - `onDocumentFullyLoaded` - Document fully loaded
   - `onWillScroll` - Before scrolling
   - `onDidScroll` - After scrolling
   - `onDocumentSteady` - Document steady (best for RPA actions)
   - `onWillComputeFeature` - Before feature computation
   - `onFeatureComputed` - After feature computation
   - `onDidInteract` - After interaction complete
   - `onWillStopTab` - Before stopping browser tab
   - `onTabStopped` - After tab stopped
   - `onFetched` - After browser fetch complete

## Event Lifecycle Sequence

### Standard Load (Without Browser)
```
1. crawl.onWillLoad
2. load.onNormalize
3. load.onWillLoad
4. load.onWillFetch
5. load.onFetched
6. load.onWillParse
7. load.onWillParseHTMLDocument
8. load.onHTMLDocumentParsed
9. load.onParsed
10. load.onLoaded
11. crawl.onLoaded
```

### Browser-Enabled Load
```
1. crawl.onWillLoad
2. load.onNormalize
3. load.onWillLoad
4. load.onWillFetch
5. browse.onWillLaunchBrowser
6. browse.onBrowserLaunched
7. browse.onWillFetch
8. browse.onWillNavigate
9. browse.onNavigated
10. browse.onWillInteract
11. browse.onWillCheckDocumentState
12. browse.onDocumentFullyLoaded
13. browse.onWillScroll
14. browse.onDidScroll
15. browse.onDocumentSteady
16. browse.onWillComputeFeature
17. browse.onFeatureComputed
18. browse.onDidInteract
19. browse.onWillStopTab
20. browse.onTabStopped
21. browse.onFetched
22. load.onFetched
23. load.onWillParse
24. load.onWillParseHTMLDocument
25. load.onHTMLDocumentParsed
26. load.onParsed
27. load.onLoaded
28. crawl.onLoaded
```

## Testing the Event Mechanism

### Test Setup

Tests are located in `sdks/kotlin-sdk-tests/src/test/kotlin/ai/platon/pulsar/sdk/integration/EventMechanismIntegrationTest.kt`

#### Running Tests

```bash
# Run all event mechanism tests
./mvnw -Psdk -pl sdks/kotlin-sdk-tests test -Dtest=EventMechanismIntegrationTest

# Run specific test
./mvnw -Psdk -pl sdks/kotlin-sdk-tests test -Dtest=EventMechanismIntegrationTest#"should receive SSE events from simple load"
```

### Test Implementation Patterns

#### 1. Submit Async Command and Collect Events

```kotlin
// Submit async command
val commandId = submitAsyncCommand(url, args)

// Collect SSE events
val events = collectSseEvents(commandId, timeoutSeconds = 45)

// Verify events received
assertTrue(events.isNotEmpty())
```

#### 2. Extract Event Types from SSE Data

```kotlin
val eventTypes = events.mapNotNull { event ->
    val data = event.data
    if (data.contains("\"event\"")) {
        val eventMatch = Regex(""""event"\s*:\s*"([^"]+)"""").find(data)
        eventMatch?.groupValues?.get(1)
    } else {
        null
    }
}.distinct()

println("Event types received: $eventTypes")
```

#### 3. Verify Event Order

```kotlin
val willLoadIndex = eventTypes.indexOf("onWillLoad")
val loadedIndex = eventTypes.lastIndexOf("onLoaded")

assertTrue(
    willLoadIndex < loadedIndex,
    "onWillLoad should come before onLoaded"
)
```

### Current Test Coverage

✅ **Implemented Tests** (8 tests):

1. `should receive SSE events from simple load`
   - Verifies basic SSE connectivity
   - Confirms events are emitted and received

2. `should receive LoadEventHandlers events`
   - Verifies key load phase events (onWillFetch, onLoaded)
   - Parses event types from JSON data

3. `should receive events in correct order`
   - Validates event sequence
   - Ensures onWillLoad comes before onLoaded

4. `should receive status updates via SSE`
   - Checks status information in events
   - Verifies event structure

5. `should handle multiple concurrent SSE streams`
   - Tests parallel event streaming
   - Verifies isolation between streams

6. `should include event metadata in SSE data`
   - Validates metadata presence (URL, timestamp, status)
   - Checks event data richness

7. `should receive event when page load fails`
   - Tests error scenario handling
   - Ensures events emitted even on failure

8. `should complete SSE stream when command finishes`
   - Verifies stream completion
   - Ensures no hanging connections

### Future Test Recommendations

#### 1. Individual Event Handler Tests

Test each event handler separately to ensure comprehensive coverage:

```kotlin
@Test
fun `should trigger onWillFetch event`() {
    val url = TestUrls.SIMPLE_PAGE
    val commandId = submitAsyncCommand(url, "-parse")
    val events = collectSseEvents(commandId)
    
    val hasOnWillFetch = events.any { 
        it.data.contains("\"event\":\"onWillFetch\"")
    }
    assertTrue(hasOnWillFetch, "onWillFetch event should be triggered")
}
```

#### 2. Browser Event Tests

Test browser-specific events by enabling browser:

```kotlin
@Test
@Tag("RequiresBrowser")
fun `should trigger BrowseEventHandlers with browser enabled`() {
    val url = TestUrls.SIMPLE_PAGE
    val commandId = submitAsyncCommand(url, "-parse -useBrowser")
    val events = collectSseEvents(commandId, timeoutSeconds = 60)
    
    val browseEvents = listOf(
        "onBrowserLaunched", "onNavigated", "onDocumentSteady"
    )
    
    val receivedBrowseEvents = events.mapNotNull { /*...*/ }
    browseEvents.forEach { eventType ->
        assertTrue(
            eventType in receivedBrowseEvents,
            "$eventType should be triggered"
        )
    }
}
```

#### 3. Event Data Validation

Validate event data structure and content:

```kotlin
@Test
fun `should include URL in event metadata`() {
    val url = TestUrls.SIMPLE_PAGE
    val commandId = submitAsyncCommand(url)
    val events = collectSseEvents(commandId)
    
    val eventsWithUrl = events.filter { event ->
        event.data.contains("\"url\":\"$url\"")
    }
    
    assertTrue(
        eventsWithUrl.isNotEmpty(),
        "Events should include the requested URL"
    )
}
```

#### 4. Performance Tests

Test event mechanism under load:

```kotlin
@Test
@Tag("Slow")
fun `should handle high frequency events`() {
    val urls = (1..10).map { TestUrls.SIMPLE_PAGE }
    val commandIds = urls.map { submitAsyncCommand(it) }
    
    val allEvents = commandIds.map { id ->
        collectSseEvents(id, timeoutSeconds = 60)
    }
    
    assertTrue(allEvents.all { it.isNotEmpty() })
}
```

#### 5. Event Handler Registration Tests

Test custom event handler registration:

```kotlin
@Test
fun `should execute custom event handlers`() {
    // Register custom event handler via SDK
    // Verify custom logic is executed
    // Check side effects or logs
}
```

#### 6. Event Filtering Tests

Test event type filtering:

```kotlin
@Test
fun `should filter events by type`() {
    // Subscribe to specific event types only
    // Verify only requested types are received
}
```

## SSE Event Format

Events are transmitted in SSE format:

```
event: eventType (optional)
id: eventId (optional)
data: {"event":"onWillFetch","status":"processing",...}

event: eventType
id: eventId
data: {"event":"onLoaded","status":"done",...}
```

### Event Data Structure

```json
{
  "event": "onWillFetch",
  "eventPhase": "load",
  "url": "http://example.com",
  "timestamp": "2026-01-16T16:30:19Z",
  "status": "processing",
  "metadata": {
    "pageStatus": "STATUS_OK"
  }
}
```

## Common Issues and Solutions

### Issue: Events Not Received

**Symptoms**: SSE stream connects but no events arrive

**Solutions**:
1. Check if GlobalEventHandlers.serverSideEventHandlers is set
2. Verify events are being emitted from page lifecycle components
3. Check firewall/proxy settings for SSE support
4. Increase timeout duration

### Issue: Event Order Incorrect

**Symptoms**: Events arrive out of expected sequence

**Solutions**:
1. Check for race conditions in async event emission
2. Verify event emission points in code
3. Review GlobalEventHandlers.emitEvent implementation
4. Check for buffering issues in SSE stream

### Issue: Missing Events

**Symptoms**: Some expected events never arrive

**Solutions**:
1. Check if event is actually emitted in the code path
2. Verify event type string matches exactly
3. Check for exceptions in event emission code
4. Review ServerSideEventHandlers buffer capacity

### Issue: Duplicate Events

**Symptoms**: Same event received multiple times

**Solutions**:
1. Check for duplicate emit calls in code
2. Review event deduplication logic
3. Verify SSE reconnection handling

## Best Practices

### 1. Use Appropriate Timeouts

```kotlin
// Short timeout for quick operations
val events = collectSseEvents(commandId, timeoutSeconds = 30)

// Longer timeout for browser operations
val events = collectSseEvents(commandId, timeoutSeconds = 60)
```

### 2. Handle Partial Event Reception

```kotlin
// Don't require all events in production scenarios
val hasKeyEvents = events.any { 
    it.data.contains("onLoaded") 
}
assertTrue(hasKeyEvents, "Should have at least onLoaded event")
```

### 3. Robust Event Parsing

```kotlin
// Use safe parsing with fallback
val eventType = try {
    Regex(""""event"\s*:\s*"([^"]+)"""")
        .find(event.data)
        ?.groupValues
        ?.get(1)
} catch (e: Exception) {
    null
}
```

### 4. Test with Real Pages

```kotlin
// Use test server for consistent results
val url = TestUrls.SIMPLE_PAGE

// Or use mock pages with known characteristics
val url = TestUrls.MOCK_SERVER_BASE + "/test-page"
```

### 5. Clean Up Resources

```kotlin
@AfterEach
fun cleanup() {
    try {
        if (client.sessionId != null) {
            client.deleteSession()
        }
    } catch (e: Exception) {
        // Ignore cleanup errors
    } finally {
        client.close()
    }
}
```

## References

- [PageEvents.kt](/pulsar-core/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/crawl/PageEvents.kt) - Event handler interfaces
- [ServerSideEventHandlers.kt](/pulsar-core/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/crawl/ServerSideEventHandlers.kt) - Server-side event implementation
- [GlobalEventHandlers.kt](/pulsar-core/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/crawl/GlobalEventHandlers.kt) - Global event dispatcher
- [CommandController.kt](/pulsar-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/CommandController.kt) - SSE streaming endpoint
- [CommandService.kt](/pulsar-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CommandService.kt) - Command execution with events
- [Event Handler Examples](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_6_EventHandler.kt) - Usage examples

## Contributing

When adding new event types or modifying the event mechanism:

1. Update event documentation in interface KDoc
2. Add corresponding emit calls in lifecycle code
3. Add tests in EventMechanismIntegrationTest
4. Update this guide with new event types
5. Verify backward compatibility

## See Also

- [REST API Examples](../../docs/rest-api-examples.md)
- [Event Handling Guide](../../docs/get-started/9event-handling.md)
- [Server-Side Event Handlers](../../docs/server-side-event-handlers.md)

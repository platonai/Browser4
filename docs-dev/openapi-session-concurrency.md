# OpenAPI Session Concurrency Strategy

## Overview

This document describes the concurrency optimization implemented for the Browser4 OpenAPI controllers. The implementation provides:

1. **Cross-session parallelism**: Multiple sessions can execute operations in parallel
2. **Same-session serialization**: Operations within a single session execute serially
3. **Last-request-wins strategy**: When multiple requests arrive for the same session, only the latest one executes

## Problem Statement

Browser navigation and DOM operations share state (navigation stack, DOM tree, history) that cannot be safely accessed concurrently. When multiple requests arrive for the same browser session:

- **Previous Behavior**: Requests would queue and execute sequentially, even if newer requests made older ones obsolete
- **Desired Behavior**: Only the latest request should execute; earlier requests should be cancelled

## Implementation

### Core Components

#### 1. ManagedSession Request Tracking

Each `ManagedSession` now tracks requests with:

```kotlin
data class ManagedSession(
    // ... other fields ...
) {
    private val requestCounter = AtomicLong(0)
    @Volatile
    private var latestRequestId: Long = 0
    
    fun newRequest(): Long {
        val requestId = requestCounter.incrementAndGet()
        latestRequestId = requestId
        return requestId
    }
    
    fun isLatestRequest(requestId: Long): Boolean {
        return requestId == latestRequestId
    }
}
```

#### 2. RequestSupersededException

Custom exception to signal when a request has been superseded:

```kotlin
class RequestSupersededException(
    val sessionId: String,
    val requestId: Long,
    message: String = "Request $requestId for session $sessionId was superseded by a newer request"
) : RuntimeException(message)
```

#### 3. Controller Pattern

All controllers that modify browser state follow this pattern:

```kotlin
suspend fun operation(@PathVariable sessionId: String, ...): ResponseEntity<Any> {
    val session = sessionManager.getSession(sessionId) ?: return notFound(...)
    
    // Register this as the latest request
    val requestId = session.newRequest()
    
    return try {
        session.mutex.withLock {
            // Check if still latest before executing
            if (!session.isLatestRequest(requestId)) {
                throw RequestSupersededException(sessionId, requestId)
            }
            // Execute operation
            session.driver.operation(...)
        }
        ResponseEntity.ok(...)
    } catch (e: RequestSupersededException) {
        // Return success for superseded requests (idempotent)
        logger.info("Request was superseded by newer request")
        ResponseEntity.ok(...)
    }
}
```

## Concurrency Guarantees

### Cross-Session Parallelism ✅

Different sessions can execute operations in parallel:

```
Session A: navigateTo(url1)  ─────────► ✓ Executes in parallel
Session B: navigateTo(url2)  ─────────► ✓ Executes in parallel
Session C: scrollDown()      ─────────► ✓ Executes in parallel
```

Each session has its own:
- Mutex for serialization
- Request counter
- Latest request ID

### Same-Session Serialization ✅

Operations within a session execute serially with last-request-wins:

```
Session A: Request 1 (navigateTo) ──┐
                                    └──► Starts executing
Session A: Request 2 (scrollDown)   ──┐
                                      └──► Waits for mutex
Session A: Request 3 (click)        ────┐
                                        └──► Waits for mutex

Timeline:
1. Request 1 acquires mutex, executes
2. Request 2 and 3 register as "latest" (Request 3 wins)
3. Request 1 completes, releases mutex
4. Request 2 acquires mutex, checks isLatest (false), skips execution
5. Request 3 acquires mutex, checks isLatest (true), executes
```

## Affected Controllers

The last-request-wins strategy is implemented in:

### NavigationController
- `navigateTo()` - Navigate to URL
- `reload()` - Reload page
- `goBack()` - Navigate back
- `goForward()` - Navigate forward
- `getTitle()` - Get page title
- `bringToFront()` - Bring window to front

### ScrollController
- `scrollDown()` - Scroll down
- `scrollUp()` - Scroll up
- `scrollTo()` - Scroll to element
- `scrollToTop()` - Scroll to top
- `scrollToBottom()` - Scroll to bottom
- `scrollToMiddle()` - Scroll to position
- `scrollBy()` - Scroll by pixels

### ElementController
- `clickElement()` - Click element
- `sendKeysToElement()` - Send keys to element
- `getElementAttribute()` - Get element attribute
- `getElementText()` - Get element text

## Logging and Debugging

The implementation includes extensive logging for debugging concurrency issues:

```
DEBUG: Session {id} navigation request {requestId} registered
DEBUG: Session {id} navigation request {requestId} executing
INFO:  Session {id} navigation to {url} was superseded by newer request
```

Request IDs are monotonically increasing, making it easy to trace request order in logs.

## Performance Characteristics

### Benefits
1. **Reduced wasted work**: Obsolete operations are skipped
2. **Better responsiveness**: Latest user action executes without waiting for stale operations
3. **Resource efficiency**: Less CPU/memory/network usage from cancelled operations

### Overhead
- Minimal: One atomic increment + one volatile read per request
- No additional locks or synchronization beyond existing mutex

## Testing

Comprehensive test coverage in `SessionConcurrencyTest.kt`:

1. **Request ID increment**: Verifies monotonic counter
2. **Latest request validation**: Verifies only latest is valid
3. **Concurrent request creation**: 100 concurrent requests maintain correctness
4. **Mutex serialization**: Operations execute serially
5. **Request supersession**: Earlier requests are properly cancelled
6. **Session independence**: Multiple sessions don't interfere
7. **Rapid requests**: Burst requests correctly cancel all but last

All tests pass with zero flakiness.

## Migration Notes

### Backward Compatibility

The implementation is fully backward compatible:

- API contracts unchanged
- Response formats unchanged
- Superseded requests return success (idempotent behavior)
- Clients don't need modifications

### Monitoring

Monitor these metrics to verify correct behavior:

1. **Superseded request rate**: Track `RequestSupersededException` frequency
2. **Request execution rate**: Should increase (less queuing)
3. **Session operation latency**: Should improve for burst scenarios

## Future Enhancements

Potential improvements:

1. **Timeout configuration**: Allow configurable request timeout
2. **Request prioritization**: Allow high-priority requests to jump queue
3. **Cancellation propagation**: Actively cancel in-flight operations (requires WebDriver support)
4. **Metrics endpoint**: Expose supersession statistics via REST API

## References

- Original issue: "优化 openapi.controller 实现"
- Implementation PR: [Link to PR]
- Test file: `pulsar-rest/src/test/kotlin/ai/platon/pulsar/rest/openapi/SessionConcurrencyTest.kt`
- Core files:
  - `SessionManager.kt`
  - `RequestSupersededException.kt`
  - `NavigationController.kt`
  - `ScrollController.kt`
  - `ElementController.kt`

---

**Last Updated**: 2026-01-29
**Version**: 4.5.0-SNAPSHOT

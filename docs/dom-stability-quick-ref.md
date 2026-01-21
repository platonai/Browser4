# DOM Stability Detection - Quick Reference

## TL;DR

This document provides a quick reference for choosing and using DOM stability detection strategies in Browser4.

## Quick Decision Tree

```
Is it your own site? 
├─ YES → Use Custom Event approach
└─ NO → Continue...

Is speed critical (e.g., AI agent)?
├─ YES → Use PageStateTracker.waitForDOMSettle()
└─ NO → Continue...

Is it a traditional content site (news, e-commerce)?
├─ YES → Use __pulsar_utils__.waitForReady()
└─ NO → Continue...

Is it a Single Page Application (React, Vue, Angular)?
├─ YES → Use Network-Idle or Hybrid (SPA preset)
└─ NO → Use Hybrid (DEFAULT preset)
```

## Method Comparison (At a Glance)

| Method | Speed | Reliability | Use Case | Code Location |
|--------|-------|-------------|----------|---------------|
| `waitForReady` | ⭐⭐ | ⭐⭐⭐⭐⭐ | Traditional sites | `__pulsar_utils__.js` |
| `waitForDOMSettle` | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | AI agents, SPAs | `PageStateTracker.kt` |
| Network-Idle | ⭐⭐⭐ | ⭐⭐⭐⭐ | AJAX-heavy sites | `NetworkIdleStrategy` |
| Hybrid | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Unknown/diverse | `HybridStabilityDetector` |

## Usage Examples

### 1. Using waitForReady (JavaScript)

```kotlin
// In InteractiveBrowserEmulator or similar
val expression = "__pulsar_utils__.waitForReady(5)" // 5 scrolls
var message: Any? = null
while (message == null || message == false) {
    message = driver.evaluate(expression)
    if (message == false) {
        delay(500)
    }
}
```

**When to use:**
- Content-heavy websites
- Need page quality metrics
- Lazy-loaded content

### 2. Using waitForDOMSettle (Kotlin)

```kotlin
// In PageStateTracker
suspend fun checkPageStability() {
    pageStateTracker.waitForDOMSettle(
        timeoutMs = 5000,
        checkIntervalMs = 100
    )
}
```

**When to use:**
- AI agent interactions
- Modern SPAs
- Speed is priority

### 3. Using Network-Idle Strategy

```kotlin
val config = StabilityConfig(
    networkIdleTime = 500,
    maxInflightRequests = 2,
    timeout = 30_000
)
val strategy = NetworkIdleStrategy(session, config)
val isStable = strategy.check()
```

**When to use:**
- AJAX-heavy sites
- REST/GraphQL APIs
- Dynamic content loading

### 4. Using Hybrid Detector

```kotlin
// For AI agents (fast)
val detector = HybridStabilityDetector(
    session,
    pageStateTracker,
    StabilityConfig.FAST
)

// For production scraping (thorough)
val detector = HybridStabilityDetector(
    session,
    pageStateTracker,
    StabilityConfig.THOROUGH
)

// For SPAs
val detector = HybridStabilityDetector(
    session,
    pageStateTracker,
    StabilityConfig.SPA
)

val result = detector.waitForStability()
if (result.success) {
    println("Page stable after ${result.elapsedMs}ms")
    println("Passed strategies: ${result.passedStrategies}")
}
```

**When to use:**
- Unknown site types
- Maximum reliability needed
- Can afford slightly longer waits

## Configuration Presets

### DEFAULT (Balanced)
```kotlin
StabilityConfig.DEFAULT
// - Timeout: 30s
// - Mode: ANY_N (requires 2/3 strategies)
// - Best for: General purpose
```

### FAST (AI Agents)
```kotlin
StabilityConfig.FAST
// - Timeout: 10s
// - Mode: ANY_N (requires 1/3 strategies)
// - Best for: Quick agent interactions
```

### THOROUGH (Production)
```kotlin
StabilityConfig.THOROUGH
// - Timeout: 60s
// - Mode: ALL (requires all strategies)
// - Best for: Critical data extraction
```

### SPA (Single Page Apps)
```kotlin
StabilityConfig.SPA
// - Timeout: 20s
// - Network-idle: 1s, 0 requests
// - Best for: React/Vue/Angular apps
```

## Custom Configuration

```kotlin
val config = StabilityConfig(
    timeout = 45_000,                    // 45 seconds max
    checkIntervalMs = 100,               // Check every 100ms
    mode = StabilityMode.ANY_N,          // At least N strategies
    requiredStrategies = 2,              // Need 2 out of 3
    
    // Network-idle settings
    networkIdleTime = 500,               // 500ms of no activity
    maxInflightRequests = 2,             // Allow 2 concurrent requests
    
    // DOM stability settings
    domStableChecks = 3,                 // 3 consecutive stable checks
    domStableChecksComplete = 2,         // 2 if readyState=complete
    
    // Content quality settings
    minHeight = 1000,                    // Min 1000px height
    minElements = 10,                    // Min 10 elements
    minAnchors = 5,                      // Min 5 links
    minImages = 2                        // Min 2 images
)
```

## Strategy Combination Modes

### ALL Mode
```kotlin
config.mode = StabilityMode.ALL
// Requires: ALL strategies to pass
// Use when: Maximum reliability needed
// Trade-off: Slowest
```

### ANY_N Mode (Recommended)
```kotlin
config.mode = StabilityMode.ANY_N
config.requiredStrategies = 2
// Requires: N strategies to pass
// Use when: Balance between speed and reliability
// Trade-off: Configurable
```

### RACE Mode
```kotlin
config.mode = StabilityMode.RACE
// Requires: First strategy to pass
// Use when: Speed is critical
// Trade-off: Less reliable
```

## Troubleshooting

### Page never stabilizes
```kotlin
// 1. Increase timeout
config.timeout = 60_000

// 2. Lower requirements
config.requiredStrategies = 1
config.mode = StabilityMode.ANY_N

// 3. Lower content thresholds
config.minHeight = 500
config.minElements = 5
```

### Too slow
```kotlin
// 1. Use FAST preset
val config = StabilityConfig.FAST

// 2. Use RACE mode
config.mode = StabilityMode.RACE

// 3. Use single strategy
val strategy = DOMStabilityStrategy(pageStateTracker, config)
```

### False positives (returns too early)
```kotlin
// 1. Use THOROUGH preset
val config = StabilityConfig.THOROUGH

// 2. Increase stable checks
config.domStableChecks = 5

// 3. Increase content requirements
config.minHeight = 2000
config.minElements = 50
```

## Performance Tips

1. **Profile first**: Test different strategies on your target sites
2. **Cache configurations**: Reuse `StabilityConfig` for same site types
3. **Combine with timeouts**: Always have a maximum wait time
4. **Log strategy results**: Track which strategies succeed for future optimization

## See Also

- [Full Documentation](dom-stability-approaches.md) - Detailed analysis and design
- [Implementation](../pulsar-agentic/src/main/kotlin/.../DOMStabilityStrategies.kt) - Source code
- [Tests](../pulsar-agentic/src/test/kotlin/.../DOMStabilityStrategiesTest.kt) - Unit tests

---

**Last Updated**: 2026-01-21  
**Version**: 1.0

# Agent Structured Logging Examples

This document provides real-world examples of structured logging output from the pulsar-agentic module.

## Successful Task Execution

```
15:03:01.234 [agent-worker-1] INFO  AgentLogger - resolveStart | sid=7a8b9c0d maxSteps=100 | instruction=Navigate to amazon.com and search for 'laptop'
15:03:01.567 [agent-worker-1] INFO  AgentLogger - actionStart | sid=7a8b9c0d step=1 | action=Navigate to https://amazon.com
15:03:02.890 [agent-worker-1] INFO  AgentLogger - toolExecOk | sid=7a8b9c0d step=1 tool=navigate | ✅ Navigation completed successfully
15:03:03.123 [agent-worker-1] INFO  AgentLogger - actSuccess | step=1 candidate=1/2 | ✅ Action executed successfully
15:03:03.456 [agent-worker-1] INFO  AgentLogger - actionStart | sid=7a8b9c0d step=2 | action=Type 'laptop' in search box
15:03:04.234 [agent-worker-1] INFO  AgentLogger - toolExecOk | sid=7a8b9c0d step=2 tool=type | ✅ Text entered successfully
15:03:04.567 [agent-worker-1] INFO  AgentLogger - actSuccess | step=2 candidate=1/1 | ✅ Action executed successfully
15:03:04.890 [agent-worker-1] INFO  AgentLogger - actionStart | sid=7a8b9c0d step=3 | action=Click search button
15:03:05.678 [agent-worker-1] INFO  AgentLogger - toolExecOk | sid=7a8b9c0d step=3 tool=click | ✅ Click executed successfully
15:03:05.901 [agent-worker-1] INFO  AgentLogger - actSuccess | step=3 candidate=1/1 | ✅ Action executed successfully
15:03:06.123 [agent-worker-1] INFO  AgentLogger - complete | sid=7a8b9c0d step=3 complete=true | ✅ Task finished
15:03:06.345 [agent-worker-1] INFO  AgentLogger - resolveDone | sid=7a8b9c0d step=3 success=true duration=5111ms | ✅ Search completed successfully
```

## Failed Action with Retry

```
15:05:01.234 [agent-worker-2] INFO  AgentLogger - resolveStart | sid=2b3c4d5e maxSteps=50 | instruction=Click login button
15:05:01.456 [agent-worker-2] INFO  AgentLogger - actionStart | sid=2b3c4d5e step=1 | action=Click #login-button
15:05:02.789 [agent-worker-2] ERROR AgentLogger - toolExecFail | sid=2b3c4d5e step=1 tool=click | ❌ Element not found: #login-button
15:05:03.012 [agent-worker-2] INFO  AgentLogger - toolExecOk | sid=2b3c4d5e step=1 tool=click | ✅ Click executed successfully (2nd attempt)
15:05:03.234 [agent-worker-2] INFO  AgentLogger - actSuccess | step=1 candidate=2/3 | ✅ Action executed successfully
15:05:03.456 [agent-worker-2] INFO  AgentLogger - complete | sid=2b3c4d5e step=1 complete=true | ✅ Task finished
15:05:03.678 [agent-worker-2] INFO  AgentLogger - resolveDone | sid=2b3c4d5e step=1 success=true duration=2444ms | ✅ Login completed
```

## All Candidates Failed

```
15:07:01.234 [agent-worker-3] INFO  AgentLogger - resolveStart | sid=3c4d5e6f maxSteps=100 | instruction=Extract product details
15:07:01.456 [agent-worker-3] INFO  AgentLogger - actionStart | sid=3c4d5e6f step=1 | action=Click product link
15:07:02.789 [agent-worker-3] ERROR AgentLogger - toolExecFail | sid=3c4d5e6f step=1 tool=click | ❌ Element not clickable
15:07:03.012 [agent-worker-3] ERROR AgentLogger - toolExecFail | sid=3c4d5e6f step=1 tool=click | ❌ Timeout waiting for element
15:07:03.234 [agent-worker-3] ERROR AgentLogger - toolExecFail | sid=3c4d5e6f step=1 tool=click | ❌ Navigation failed
15:07:03.456 [agent-worker-3] WARN  AgentLogger - actAllFailed | step=1 candidates=3 | ❌ All candidates failed. Last: Navigation failed
15:07:03.678 [agent-worker-3] WARN  AgentLogger - resolveDone | sid=3c4d5e6f step=1 success=false duration=2444ms | ❌ Task failed
```

## Timeout Scenario

```
15:10:01.234 [agent-worker-4] INFO  AgentLogger - resolveStart | sid=4d5e6f7a maxSteps=100 | instruction=Complete checkout
15:10:01.456 [agent-worker-4] INFO  AgentLogger - actionStart | sid=4d5e6f7a step=1 | action=Fill payment form
15:10:11.456 [agent-worker-4] WARN  AgentLogger - actTimeout | step=1 timeout=10000ms | ⏳ Action timed out: Fill payment form
15:10:11.678 [agent-worker-4] INFO  AgentLogger - actionStart | sid=4d5e6f7a step=2 | action=Retry payment form
15:10:15.890 [agent-worker-4] INFO  AgentLogger - toolExecOk | sid=4d5e6f7a step=2 tool=type | ✅ Form filled successfully
15:10:16.123 [agent-worker-4] INFO  AgentLogger - actSuccess | step=2 candidate=1/1 | ✅ Action executed successfully
15:10:16.345 [agent-worker-4] INFO  AgentLogger - complete | sid=4d5e6f7a step=2 complete=true | ✅ Task finished
```

## Validation Failure

```
15:12:01.234 [agent-worker-5] INFO  AgentLogger - resolveStart | sid=5e6f7a8b maxSteps=100 | instruction=Navigate to admin panel
15:12:01.456 [agent-worker-5] INFO  AgentLogger - actionStart | sid=5e6f7a8b step=1 | action=Click admin link
15:12:02.789 [agent-worker-5] WARN  AgentLogger - validationFailed | sid=5e6f7a8b step=1 | ⚠️ Tool call validation failed: Invalid URL
15:12:03.012 [agent-worker-5] INFO  AgentLogger - actionStart | sid=5e6f7a8b step=2 | action=Navigate to /admin
15:12:04.345 [agent-worker-5] INFO  AgentLogger - toolExecOk | sid=5e6f7a8b step=2 tool=navigate | ✅ Navigation completed
15:12:04.567 [agent-worker-5] INFO  AgentLogger - complete | sid=5e6f7a8b step=2 complete=true | ✅ Task finished
```

## No-Operation Detection

```
15:15:01.234 [agent-worker-6] INFO  AgentLogger - resolveStart | sid=6f7a8b9c maxSteps=100 | instruction=Wait for page load
15:15:01.456 [agent-worker-6] INFO  AgentLogger - actionStart | sid=6f7a8b9c step=1 | action=Wait for element
15:15:02.789 [agent-worker-6] WARN  AgentLogger - observeNoAction | step=1 | ⚠️ No observe result generated
15:15:03.012 [agent-worker-6] WARN  AgentLogger - noop | step=1 consecutive=1 max=5 | ⚠️ No-operation detected
15:15:04.234 [agent-worker-6] WARN  AgentLogger - noop | step=2 consecutive=2 max=5 | ⚠️ No-operation detected
15:15:05.456 [agent-worker-6] WARN  AgentLogger - noop | step=3 consecutive=3 max=5 | ⚠️ No-operation detected
15:15:06.678 [agent-worker-6] INFO  AgentLogger - toolExecOk | sid=6f7a8b9c step=4 tool=wait | ✅ Element appeared
15:15:06.890 [agent-worker-6] INFO  AgentLogger - complete | sid=6f7a8b9c step=4 complete=true | ✅ Task finished
```

## User Initiated Close

```
15:20:01.234 [agent-worker-7] INFO  AgentLogger - resolveStart | sid=7a8b9c0d maxSteps=100 | instruction=Long running task
15:20:01.456 [agent-worker-7] INFO  AgentLogger - actionStart | sid=7a8b9c0d step=1 | action=Process data
15:20:05.789 [agent-worker-7] INFO  AgentLogger - toolExecOk | sid=7a8b9c0d step=1 tool=process | ✅ Processing...
15:20:10.012 [agent-worker-7] INFO  AgentLogger - userClose | step=1 | 🛑 Agent closed by user
```

## Resolve Timeout

```
15:25:01.234 [agent-worker-8] INFO  AgentLogger - resolveStart | sid=8b9c0d1e maxSteps=100 | instruction=Complex multi-step task
15:25:01.456 [agent-worker-8] INFO  AgentLogger - actionStart | sid=8b9c0d1e step=1 | action=Step 1
15:25:05.789 [agent-worker-8] INFO  AgentLogger - actSuccess | step=1 candidate=1/1 | ✅ Action executed successfully
...
[many steps omitted]
...
15:49:01.234 [agent-worker-8] WARN  AgentLogger - resolveTimeout | sid=8b9c0d1e timeout=1440000ms | ⏳ Resolve timed out: Complex multi-step...
```

## Final Summary Generation

```
15:30:01.234 [agent-worker-9] INFO  AgentLogger - resolveStart | sid=9c0d1e2f maxSteps=100 | instruction=Extract all product info
15:30:01.456 [agent-worker-9] INFO  AgentLogger - actionStart | sid=9c0d1e2f step=1 | action=Navigate to product
...
[steps omitted]
...
15:30:25.789 [agent-worker-9] INFO  AgentLogger - complete | sid=9c0d1e2f step=10 complete=true | ✅ Task finished
15:30:25.890 [agent-worker-9] INFO  AgentLogger - final | sid=9c0d1e2f step=10 | Generating final summary
15:30:26.123 [agent-worker-9] INFO  AgentLogger - resolveDone | sid=9c0d1e2f step=10 success=true duration=24889ms | ✅ Extraction completed
```

## Parsing Examples

### Extract Session Timeline

```bash
grep "sid=7a8b9c0d" logs/pulsar.log | grep -E "(resolveStart|actionStart|toolExecOk|complete|resolveDone)"
```

### Monitor Failures

```bash
grep -E "(toolExecFail|actAllFailed|validationFailed)" logs/pulsar.log | tail -20
```

### Performance Analysis

```bash
grep "resolveDone" logs/pulsar.log | \
  grep -oP "duration=\K[0-9]+" | \
  awk '{sum+=$1; count++} END {print "Average: " sum/count "ms"}'
```

### Timeout Statistics

```bash
grep "Timeout" logs/pulsar.log | wc -l
```

### Success Rate

```bash
success=$(grep "resolveDone.*success=true" logs/pulsar.log | wc -l)
total=$(grep "resolveDone" logs/pulsar.log | wc -l)
echo "Success rate: $(echo "scale=2; $success * 100 / $total" | bc)%"
```

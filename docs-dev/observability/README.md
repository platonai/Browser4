# Pulsar Agentic Observability Guide

## 概述 / Overview

本文档介绍了 pulsar-agentic 模块的可观测性系统，包括分布式追踪、指标收集、监控和告警配置。

This guide covers the observability system for the pulsar-agentic module, including distributed tracing, metrics collection, monitoring, and alerting configuration.

## 核心组件 / Core Components

### 1. OpenTelemetry 分布式追踪 / Distributed Tracing

使用 OpenTelemetry 进行分布式追踪，支持 OTLP 协议导出到多种后端（Jaeger、Zipkin、OTEL Collector 等）。

Uses OpenTelemetry for distributed tracing with OTLP protocol export to various backends (Jaeger, Zipkin, OTEL Collector, etc.).

#### 配置 / Configuration

环境变量 / Environment Variables:

```bash
# 启用/禁用追踪 / Enable/disable tracing
export OTEL_TRACES_ENABLED=true

# OTLP 导出端点 / OTLP exporter endpoint
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

# 服务名称 / Service name
export OTEL_SERVICE_NAME=pulsar-agentic

# 服务版本 / Service version
export OTEL_SERVICE_VERSION=4.7.0-SNAPSHOT
```

#### 使用示例 / Usage Example

```kotlin
import ai.platon.pulsar.agentic.observability.TracingUtils

// 简单的 span 追踪 / Simple span tracing
TracingUtils.withSpan("agent.resolve") {
    // Your agent logic here
    performResolve()
}

// 带属性的 span / Span with attributes
TracingUtils.withSpan("agent.act", mapOf(
    "action" to "click",
    "element" to "button#submit"
)) { span ->
    performAction()
    span.addEvent("Action completed")
}

// 手动管理 span / Manual span management
val span = TracingUtils.startSpan("custom-operation")
try {
    // Your code
    TracingUtils.setAttribute(span, "result", "success")
} catch (e: Exception) {
    TracingUtils.recordError(span, e)
    throw e
} finally {
    span.end()
}
```

### 2. Prometheus 指标导出 / Metrics Export

使用 Micrometer 收集指标并导出到 Prometheus 格式。

Uses Micrometer to collect metrics and export them in Prometheus format.

#### 配置 / Configuration

环境变量 / Environment Variables:

```bash
# 启用/禁用指标 / Enable/disable metrics
export METRICS_ENABLED=true

# 指标前缀 / Metrics prefix
export METRICS_PREFIX=pulsar_agentic

# 通用标签 / Common tags (comma-separated key:value pairs)
export METRICS_COMMON_TAGS=service:pulsar-agentic,environment:production
```

#### 指标端点 / Metrics Endpoint

如果使用 Spring Boot Actuator，指标将在以下端点可用：

If using Spring Boot Actuator, metrics are available at:

```
http://localhost:8182/actuator/prometheus
```

#### 使用示例 / Usage Example

```kotlin
import ai.platon.pulsar.agentic.observability.MetricsConfig

// 获取 registry / Get registry
val registry = MetricsConfig.registry

// 创建计数器 / Create counter
registry.counter("custom.operations", "type", "special").increment()

// 创建计时器 / Create timer
val timer = registry.timer("custom.duration", "operation", "process")
timer.record {
    // Your operation
}

// 导出 Prometheus 数据 / Export Prometheus data
val prometheusData = MetricsConfig.scrape()
```

### 3. 业务指标 / Business Metrics

#### Agent 指标 / Agent Metrics

```kotlin
import ai.platon.pulsar.agentic.observability.AgentMetrics

// 记录 agent 启动 / Record agent start
AgentMetrics.recordAgentStart("browser-agent")

// 记录动作执行 / Record action execution
AgentMetrics.recordActionExecution("click", success = true, durationMs = 150)

// 记录步骤完成 / Record step completion
AgentMetrics.recordStepCompleted("browser-agent", stepNumber = 5)

// 记录 agent 完成 / Record agent completion
AgentMetrics.recordAgentCompleted("browser-agent", totalSteps = 10)

// 记录失败 / Record failure
AgentMetrics.recordAgentFailed("browser-agent", errorType = "timeout")

// 记录重试 / Record retry
AgentMetrics.recordRetry("transient_error")
```

**可用指标 / Available Metrics:**

- `agent.started` - Agent 启动计数 / Agent start count
- `agent.completed` - Agent 完成计数 / Agent completion count
- `agent.failed` - Agent 失败计数 / Agent failure count
- `agent.active.count` - 活跃 agent 数量 / Active agents count
- `agent.actions.success` - 成功动作计数 / Successful actions count
- `agent.actions.failure` - 失败动作计数 / Failed actions count
- `agent.action.duration` - 动作执行时长 / Action execution duration
- `agent.resolve.duration` - Resolve 操作时长 / Resolve operation duration
- `agent.steps.completed` - 完成步骤计数 / Completed steps count
- `agent.retries` - 重试计数 / Retry count
- `agent.timeouts` - 超时计数 / Timeout count

#### Tool 指标 / Tool Metrics

```kotlin
import ai.platon.pulsar.agentic.observability.ToolMetrics

// 记录工具调用 / Record tool call
ToolMetrics.recordToolCall("browser.click", success = true, durationMs = 250)

// 使用计时器记录 / Record with timer
val result = ToolMetrics.recordToolCallTimed("browser.navigate") {
    performNavigation()
}

// 记录验证失败 / Record validation failure
ToolMetrics.recordValidationFailure("browser.click", "invalid_selector")

// 记录工具注册 / Record tool registration
ToolMetrics.recordToolRegistration("custom.tool", "custom")
```

**可用指标 / Available Metrics:**

- `tool.calls.total` - 工具调用总数 / Total tool calls
- `tool.calls.success` - 成功调用计数 / Successful calls count
- `tool.calls.failure` - 失败调用计数 / Failed calls count
- `tool.active.calls` - 活跃调用数 / Active calls count
- `tool.execution.duration` - 执行时长 / Execution duration
- `tool.validation.failures` - 验证失败计数 / Validation failures count
- `tool.registrations` - 工具注册计数 / Tool registrations count

#### Inference 指标 / Inference Metrics

```kotlin
import ai.platon.pulsar.agentic.observability.InferenceMetrics

// 记录推理调用 / Record inference call
InferenceMetrics.recordInferenceCall("gpt-4", success = true, durationMs = 1500)

// 使用计时器记录 / Record with timer
val result = InferenceMetrics.recordInferenceCallTimed("gpt-4") {
    callLLM()
}

// 记录 token 使用 / Record token usage
InferenceMetrics.recordTokenUsage("gpt-4", inputTokens = 100, outputTokens = 250)

// 记录熔断器触发 / Record circuit breaker trip
InferenceMetrics.recordCircuitBreakerTrip("consecutive_failures")

// 记录重试 / Record retry
InferenceMetrics.recordRetry("gpt-4", "rate_limit")

// 记录错误 / Record error
InferenceMetrics.recordInferenceError("gpt-4", "timeout")
```

**可用指标 / Available Metrics:**

- `inference.calls.total` - 推理调用总数 / Total inference calls
- `inference.calls.success` - 成功调用计数 / Successful calls count
- `inference.calls.failure` - 失败调用计数 / Failed calls count
- `inference.active.calls` - 活跃调用数 / Active calls count
- `inference.duration` - 推理时长 / Inference duration
- `inference.tokens.input.total` - 输入 token 总数 / Total input tokens
- `inference.tokens.output.total` - 输出 token 总数 / Total output tokens
- `inference.circuit_breaker.trips` - 熔断器触发计数 / Circuit breaker trips
- `inference.retries` - 重试计数 / Retry count
- `inference.errors` - 错误计数 / Error count
- `inference.prompt.size` - Prompt 大小 / Prompt size

## 监控设置 / Monitoring Setup

### Prometheus 配置 / Prometheus Configuration

在 `prometheus.yml` 中添加 scrape 配置：

Add scrape configuration to `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'pulsar-agentic'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 30s
    static_configs:
      - targets: ['localhost:8182']
        labels:
          service: 'pulsar-agentic'
          environment: 'production'
```

### 告警规则 / Alerting Rules

使用提供的 Prometheus 告警规则文件：

Use the provided Prometheus alerting rules file:

```bash
# 加载告警规则 / Load alerting rules
prometheus --config.file=prometheus.yml \
  --storage.tsdb.path=/data \
  --web.listen-address=:9090 \
  --alertmanager.notification-queue-capacity=10000
```

告警规则文件位于：`docs/observability/prometheus-alerts.yml`

Alerting rules file located at: `docs/observability/prometheus-alerts.yml`

### Grafana Dashboard

导入提供的 Grafana dashboard JSON 文件：

Import the provided Grafana dashboard JSON file:

1. 打开 Grafana UI / Open Grafana UI
2. 导航至 Dashboards → Import / Navigate to Dashboards → Import
3. 上传 `docs/observability/grafana-dashboard.json` / Upload `docs/observability/grafana-dashboard.json`
4. 选择 Prometheus 数据源 / Select Prometheus data source
5. 点击 Import / Click Import

### Jaeger 追踪 / Jaeger Tracing

启动 Jaeger all-in-one：

Start Jaeger all-in-one:

```bash
docker run -d --name jaeger \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 16686:16686 \
  -p 4317:4317 \
  -p 4318:4318 \
  jaegertracing/all-in-one:latest
```

访问 Jaeger UI：`http://localhost:16686`

Access Jaeger UI: `http://localhost:16686`

## Docker Compose 示例 / Docker Compose Example

完整的可观测性栈：

Complete observability stack:

```yaml
version: '3.8'

services:
  pulsar-agentic:
    image: pulsar-agentic:latest
    environment:
      - OTEL_TRACES_ENABLED=true
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
      - OTEL_SERVICE_NAME=pulsar-agentic
      - METRICS_ENABLED=true
    ports:
      - "8182:8182"
    depends_on:
      - otel-collector
      - prometheus

  otel-collector:
    image: otel/opentelemetry-collector:latest
    command: ["--config=/etc/otel-collector-config.yaml"]
    volumes:
      - ./otel-collector-config.yaml:/etc/otel-collector-config.yaml
    ports:
      - "4317:4317"  # OTLP gRPC
      - "4318:4318"  # OTLP HTTP

  jaeger:
    image: jaegertracing/all-in-one:latest
    environment:
      - COLLECTOR_OTLP_ENABLED=true
    ports:
      - "16686:16686"  # Jaeger UI
      - "14250:14250"  # gRPC

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - ./prometheus-alerts.yml:/etc/prometheus/alerts.yml
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:latest
    volumes:
      - grafana-storage:/var/lib/grafana
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    ports:
      - "3000:3000"
    depends_on:
      - prometheus

volumes:
  grafana-storage:
```

## 性能影响 / Performance Impact

可观测性系统对性能的影响：

Performance impact of the observability system:

- **追踪 / Tracing**: <1% CPU overhead，negligible memory
- **指标 / Metrics**: <0.5% CPU overhead，~10MB memory
- **总体 / Overall**: <2% overhead in typical scenarios

建议在生产环境中：

Recommendations for production:

1. 使用采样降低追踪开销 / Use sampling to reduce tracing overhead
2. 调整指标发布间隔 / Adjust metrics publishing interval
3. 监控系统资源使用 / Monitor system resource usage

## 故障排查 / Troubleshooting

### 追踪未显示 / Traces Not Showing

检查：

Check:

1. OTLP 端点是否可达 / OTLP endpoint reachable
2. `OTEL_TRACES_ENABLED=true` 是否设置 / `OTEL_TRACES_ENABLED=true` set
3. 防火墙规则 / Firewall rules
4. 查看应用日志 / Check application logs

### 指标未导出 / Metrics Not Exporting

检查：

Check:

1. `METRICS_ENABLED=true` 是否设置 / `METRICS_ENABLED=true` set
2. Actuator 端点是否暴露 / Actuator endpoints exposed
3. Prometheus scrape 配置 / Prometheus scrape configuration
4. 访问 `/actuator/prometheus` 查看原始数据 / Access `/actuator/prometheus` for raw data

### 高内存使用 / High Memory Usage

解决方案：

Solutions:

1. 减少历史记录大小 / Reduce history size
2. 降低指标分辨率 / Lower metrics resolution
3. 使用采样 / Use sampling
4. 调整 batch processor 设置 / Adjust batch processor settings

## 最佳实践 / Best Practices

1. **生产环境 / Production**:
   - 使用采样以减少开销 / Use sampling to reduce overhead
   - 配置合理的保留策略 / Configure appropriate retention policies
   - 设置告警阈值 / Set up alerting thresholds
   - 定期审查指标 / Regularly review metrics

2. **开发环境 / Development**:
   - 启用完整追踪 / Enable full tracing
   - 使用本地 Jaeger / Use local Jaeger
   - 监控资源使用 / Monitor resource usage

3. **安全 / Security**:
   - 不在 span 中包含敏感数据 / Don't include sensitive data in spans
   - 使用 TLS 进行导出 / Use TLS for export
   - 限制指标端点访问 / Restrict metrics endpoint access

## 参考资料 / References

- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)
- [Micrometer Documentation](https://micrometer.io/docs)
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)

# Quick Start Guide - Pulsar Agentic Observability

## 快速开始 / Quick Start

本指南帮助您快速启动 pulsar-agentic 的可观测性系统。

This guide helps you quickly set up the observability system for pulsar-agentic.

## 5分钟快速部署 / 5-Minute Setup

### 1. 启动可观测性栈 / Start Observability Stack

```bash
cd docs/observability
docker-compose up -d
```

这将启动：

This will start:
- Jaeger (追踪 / Tracing): http://localhost:16686
- Prometheus (指标 / Metrics): http://localhost:9090
- Grafana (可视化 / Visualization): http://localhost:3000
- AlertManager (告警 / Alerting): http://localhost:9093

### 2. 配置应用程序 / Configure Application

设置环境变量或在 `application.properties` 中添加：

Set environment variables or add to `application.properties`:

```properties
# 启用追踪 / Enable tracing
otel.traces.enabled=true
otel.exporter.otlp.endpoint=http://localhost:4317

# 启用指标 / Enable metrics
metrics.enabled=true
management.endpoints.web.exposure.include=health,info,metrics,prometheus
```

### 3. 在代码中使用 / Use in Code

#### Agent 指标 / Agent Metrics

```kotlin
import ai.platon.pulsar.agentic.observability.AgentMetrics
import ai.platon.pulsar.agentic.observability.TracingUtils

class MyAgent {
    fun execute() {
        // 记录 agent 启动 / Record agent start
        AgentMetrics.recordAgentStart("my-agent")
        
        // 使用追踪 / Use tracing
        TracingUtils.withSpan("agent.execute") { span ->
            try {
                // 执行任务 / Execute task
                performTask()
                
                // 记录成功 / Record success
                AgentMetrics.recordAgentCompleted("my-agent", totalSteps = 10)
            } catch (e: Exception) {
                // 记录失败 / Record failure
                AgentMetrics.recordAgentFailed("my-agent", "exception")
                TracingUtils.recordError(span, e)
                throw e
            }
        }
    }
}
```

#### Tool 指标 / Tool Metrics

```kotlin
import ai.platon.pulsar.agentic.observability.ToolMetrics

class MyTool {
    fun execute() {
        // 使用计时器自动记录 / Use timer for automatic recording
        ToolMetrics.recordToolCallTimed("my-tool") {
            // 执行工具逻辑 / Execute tool logic
            performToolOperation()
        }
    }
}
```

#### Inference 指标 / Inference Metrics

```kotlin
import ai.platon.pulsar.agentic.observability.InferenceMetrics

class MyInferenceEngine {
    fun callLLM() {
        InferenceMetrics.recordInferenceCallTimed("gpt-4") {
            val response = llmClient.call(prompt)
            
            // 记录 token 使用 / Record token usage
            InferenceMetrics.recordTokenUsage(
                "gpt-4", 
                inputTokens = response.inputTokens,
                outputTokens = response.outputTokens
            )
            
            response
        }
    }
}
```

### 4. 查看指标和追踪 / View Metrics and Traces

#### Grafana Dashboard

1. 访问 http://localhost:3000
2. 登录 (admin/admin)
3. 导入 dashboard: `docs/observability/grafana-dashboard.json`
4. 查看实时指标 / View real-time metrics

#### Jaeger Traces

1. 访问 http://localhost:16686
2. 选择服务 "pulsar-agentic"
3. 查看追踪详情 / View trace details

#### Prometheus Metrics

1. 访问 http://localhost:9090
2. 查询示例 / Query examples:
   ```promql
   # Agent 成功率 / Agent success rate
   rate(agent_completed_total[5m]) / rate(agent_started_total[5m])
   
   # Tool 调用次数 / Tool call count
   sum(rate(tool_calls_total[5m])) by (tool_name)
   
   # Inference 延迟 (p95) / Inference latency (p95)
   histogram_quantile(0.95, sum(rate(inference_duration_seconds_bucket[5m])) by (le))
   ```

## 常用命令 / Common Commands

### 启动所有服务 / Start All Services

```bash
docker-compose up -d
```

### 查看日志 / View Logs

```bash
# 所有服务 / All services
docker-compose logs -f

# 特定服务 / Specific service
docker-compose logs -f otel-collector
docker-compose logs -f prometheus
```

### 停止服务 / Stop Services

```bash
docker-compose down
```

### 清理数据 / Clean Data

```bash
docker-compose down -v
```

### 重启服务 / Restart Service

```bash
docker-compose restart otel-collector
```

## 验证安装 / Verify Installation

### 1. 检查服务健康状态 / Check Service Health

```bash
# OpenTelemetry Collector
curl http://localhost:13133

# Prometheus
curl http://localhost:9090/-/healthy

# Grafana
curl http://localhost:3000/api/health
```

### 2. 测试 OTLP 端点 / Test OTLP Endpoint

```bash
# 检查 gRPC 端口 / Check gRPC port
nc -zv localhost 4317

# 检查 HTTP 端口 / Check HTTP port
nc -zv localhost 4318
```

### 3. 查看 Prometheus 目标 / View Prometheus Targets

访问 http://localhost:9090/targets

确保所有目标都是 "UP" 状态。

Ensure all targets are "UP".

## 故障排查 / Troubleshooting

### 问题: 追踪未显示 / Traces Not Showing

**解决方案 / Solution:**

1. 检查 OTLP 端点连接:
   ```bash
   docker-compose logs otel-collector
   ```

2. 验证应用配置:
   ```bash
   echo $OTEL_EXPORTER_OTLP_ENDPOINT
   echo $OTEL_TRACES_ENABLED
   ```

3. 检查防火墙规则

### 问题: 指标不更新 / Metrics Not Updating

**解决方案 / Solution:**

1. 检查 Prometheus 抓取状态:
   访问 http://localhost:9090/targets

2. 验证应用端点:
   ```bash
   curl http://localhost:8182/actuator/prometheus
   ```

3. 检查 Prometheus 配置:
   ```bash
   docker-compose logs prometheus
   ```

### 问题: Grafana Dashboard 无数据 / No Data in Dashboard

**解决方案 / Solution:**

1. 验证数据源配置 (Prometheus URL)
2. 检查时间范围选择
3. 运行一些测试请求生成数据

## 性能优化 / Performance Tuning

### 生产环境配置 / Production Configuration

```properties
# 降低采样率 / Reduce sampling rate
otel.traces.sampler=parentbased_traceidratio
otel.traces.sampler.arg=0.1

# 增加批处理大小 / Increase batch size
otel.bsp.max.export.batch.size=1024

# 调整指标间隔 / Adjust metrics interval
metrics.step=60s
```

### 资源限制 / Resource Limits

在 `docker-compose.yml` 中添加:

Add to `docker-compose.yml`:

```yaml
services:
  otel-collector:
    deploy:
      resources:
        limits:
          cpus: '1'
          memory: 512M
```

## 下一步 / Next Steps

1. **自定义 Dashboard**: 根据业务需求修改 Grafana dashboard
2. **配置告警**: 设置 AlertManager 通知渠道（Slack、Email 等）
3. **集成到 CI/CD**: 在持续集成中监控指标
4. **生产部署**: 使用 Kubernetes 部署可观测性栈

## 参考资料 / Resources

- [完整文档 / Full Documentation](./README.md)
- [告警规则 / Alert Rules](./prometheus-alerts.yml)
- [Grafana Dashboard](./grafana-dashboard.json)
- [Docker Compose](./docker-compose.yml)

## 支持 / Support

如有问题，请参考：

For issues, please refer to:
- [GitHub Issues](https://github.com/platonai/Browser4/issues)
- [Documentation](./README.md)

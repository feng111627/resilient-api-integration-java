# 外部 REST 调用与容错层说明

本工程实现了一套对外部 REST 接口（示例业务：创建支付）的调用与容错层，
目标是在上游偶发超时、重复提交、持续故障时，接口结果仍然可预期、可解释。

## 1. 调用链契约

一次业务调用按以下顺序经过各层（`PaymentService.createPayment`）：

```
HTTP 入口 (PaymentController)
  -> 幂等层 (IdempotencyStore)     同键单飞 / 结果缓存 / 键复用冲突检测
    -> 熔断器 (CircuitBreaker)     OPEN 时快速失败，不触及上游
      -> 重试策略 (RetryPolicy)    只对可安全重试的失败做有限退避重试
        -> 上游客户端 (UpstreamClient)  超时与状态码归类为统一异常
          -> 响应解析             解析失败归为 PARSE_ERROR
```

### 对外 API

`POST /api/payments`

- 请求头：`Idempotency-Key: <业务幂等键>`（必填，缺失返回 400）
- 请求体：`{"amount": 99.50, "currency": "CNY"}`
- 成功：`201` + `{"paymentId": "...", "status": "..."}`
- 失败：统一错误体 `{"error": "<ErrorKind>", "message": "...", "requestId": "..."}`

### 幂等契约

- 同一个 `Idempotency-Key` + 相同请求内容：
  - 并发提交时只有第一个请求真正调用上游（单飞），其余等待并共享同一结果；
  - 完成后的重复提交直接返回缓存结果，不再调用上游。
- 同一个键 + 不同请求内容：拒绝，返回 `409 IDEMPOTENCY_CONFLICT`，
  与普通参数错误（`400 BAD_REQUEST`）明确区分。
- 请求内容的"相同"由规范化指纹判定（金额去尾零 + 币种 的 SHA-256）。
- 执行失败的键不留占位，后续同键请求可以正常重试，不会被卡死。
- 调用上游与重试时都携带同一 `Idempotency-Key` 头，行为良好的上游可据此去重，
  保证重试不产生重复副作用（测试桩验证了副作用只计一次）。

## 2. 错误分类

所有上游故障统一为 `UpstreamException`，通过 `ErrorKind` 区分，不向调用方
透出 JDK HttpClient 或 Jackson 的异常类型（原始异常仅作为 cause 保留用于排查）：

| ErrorKind             | 含义                       | HTTP 状态 | 是否重试 |
|-----------------------|----------------------------|-----------|----------|
| `TIMEOUT`             | 上游读/连接超时            | 504       | 是       |
| `UPSTREAM_ERROR`      | 上游错误状态码或连接失败   | 502       | 5xx/429/连接失败重试，其余 4xx 不重试 |
| `PARSE_ERROR`         | 响应体解析失败/缺字段      | 502       | 否       |
| `CIRCUIT_OPEN`        | 熔断期间快速拒绝           | 503       | 否       |
| `IDEMPOTENCY_CONFLICT`| 幂等键被不同内容复用       | 409       | 否       |
| （`BAD_REQUEST`）     | 参数错误（非上游异常）     | 400       | —        |

现场信息：每次上游调用生成 `requestId`（同时作为 `X-Request-Id` 头发给上游），
错误响应携带 `requestId`、上游状态码与截断后的响应体片段，日志中可按其串联排查。

## 3. 重试规则

- 只重试可安全重试的失败：`TIMEOUT`、`UPSTREAM_ERROR`（5xx、429、连接失败）。
- 次数有限：最多 `maxAttempts` 次（含首次），指数退避
  （`initialBackoffMs` 起，每次乘 `backoffMultiplier`）。
- 不重复生效：重试携带同一幂等键；`PARSE_ERROR`、`CIRCUIT_OPEN`、
  `IDEMPOTENCY_CONFLICT`、4xx（除 429）不重试。
- 重试耗尽后抛出最后一次失败，错误分类不丢失。

## 4. 熔断规则

- `CLOSED`：正常调用；**连续**失败达到 `failureThreshold` 次后切到 `OPEN`。
- `OPEN`：请求立即以 `CIRCUIT_OPEN` 快速失败，不调用上游；
  冷却 `cooldownMs` 后放行一个试探请求。
- `HALF_OPEN`（试探）：试探期间其它并发请求仍快速失败，避免打爆故障上游；
  试探成功则关闭熔断器并重置计数，试探失败则重新打开并重新计时冷却。
- 熔断不影响幂等层：已完成键的结果在熔断期间仍可重放（缓存在熔断器之前命中），
  失败也不会冲掉已完成结果。

## 5. 可配置项

`application.properties`，前缀 `resilience.`：

| 配置项                        | 默认值                    | 说明               |
|-------------------------------|---------------------------|--------------------|
| `upstream-base-url`           | `http://localhost:9000`   | 上游服务地址       |
| `connect-timeout-ms`          | `1000`                    | 连接超时           |
| `read-timeout-ms`             | `2000`                    | 读超时             |
| `max-attempts`                | `3`                       | 最大尝试次数（含首次） |
| `initial-backoff-ms`          | `100`                     | 初始退避           |
| `backoff-multiplier`          | `2.0`                     | 退避倍数           |
| `failure-threshold`           | `3`                       | 熔断连续失败阈值   |
| `cooldown-ms`                 | `5000`                    | 熔断冷却时长       |

## 6. 本地验证

不需要外部网络与真实凭据：测试用 JDK `HttpServer` 在 127.0.0.1 起本地桩
（`tests/stub/StubUpstreamServer`），可编程返回 500/延迟/坏 JSON，
并按幂等键统计上游副作用次数。

```bash
mvn test
```

覆盖的业务场景（测试日志中带 `[test]` 前缀，可看到每个用例的通过情况）：

- `IdempotencyTest`：同键并发提交上游只执行一次；完成后重放命中缓存；
  同键不同内容返回 `IDEMPOTENCY_CONFLICT` 且与参数错误区分。
- `RetryTest`：5xx 退避重试至成功且副作用只计一次；超时重试；
  4xx 不重试；坏 JSON 归为 `PARSE_ERROR` 不重试；重试耗尽抛出最后失败。
- `CircuitBreakerTest`：连续失败达阈值后快速失败且不再打上游；
  冷却后试探成功自动恢复；试探失败重新打开；
  熔断期间已完成结果仍可重放、失败的键不卡死后续请求。
- `PaymentApiIntegrationTest`：端到端验证错误分类映射
  （400/409/502/504）与统一错误体，确认不透出底层库异常类型。

# 弹性外部 REST 调用层（resilient-api-integration）

本工程在 Spring Boot（Java 21）之上实现了一层面向外部 REST 服务的**调用与容错层**。
目标是在上游偶发超时、重复提交和持续故障时，让调用方看到的结果仍然**可预期、可解释**：

- 基于**业务幂等键**的重复请求处理（并发去重、内容指纹冲突识别、结果重放）；
- **超时 / 有限退避重试 / 熔断**，且重试不会造成上游重复生效；
- 把底层各种异常**统一归类**为稳定错误码，不直接透出 HTTP/序列化库异常；
- 全部故障场景用**本地桩服务**测试，无需外部网络与真实凭据。

示例业务为“支付下单”，但 `service / resilience / idempotency` 这几层与业务无关，可复用到其它写接口。

---

## 1. 对外契约

### 1.1 下单

```
POST /api/payments
Header: Idempotency-Key: <调用方生成的全局唯一键，必填>
Content-Type: application/json

{ "amount": 12.50, "payerAccount": "A001", "payeeAccount": "B001" }
```

- 首次成功：`201 Created`，响应体为 `PaymentResult`，`replayed=false`。
- 同键相同内容的重复提交（并发或串行）：`200 OK`，重放**同一个** `paymentId`，`replayed=true`，上游只会被真正调用一次。
- 同键但内容不同：`409 Conflict`，错误码 `IDEMPOTENCY_CONFLICT`（区别于普通参数错误）。

### 1.2 幂等判定规则

- 请求体先序列化为**规范化 JSON**（字段排序、忽略字段顺序差异），再做 **SHA-256** 指纹。
- `幂等键 + 指纹` 二者共同决定“是否同一请求”：
  - 键相同、指纹相同 → 同一请求（去重/重放）；
  - 键相同、指纹不同 → 冲突拒绝；
  - 键不同 → 互不影响。
- 终态记录在 TTL 内保留（默认 24h），并发安全由存储内部保证（`ConcurrentHashMap` + 条目级 wait/notify）。

### 1.3 失败后的语义（重要：防止重复副作用）

对“写”接口，超时/5xx 时上游**是否真的生效往往无法判定**。因此：

- 同键首个请求**一旦发起过上游调用并失败**，会记录失败终态；同键的重复提交**不会再打上游**，
  而是返回 `409 IDEMPOTENCY_PREVIOUS_FAILED`，并在 `details.originalErrorCode` 中保留首次失败分类。
  调用方应先核实业务状态，确认未生效后**换用新幂等键**重试。
- 只有一种情况占位会被撤销（`ABORTED`）：请求在**尚未发起任何上游调用前**就被 OPEN 熔断拒绝。
  此时不占用幂等键，冷却后同键请求可正常执行。

---

## 2. 错误分类（对外稳定契约）

所有错误统一返回如下结构（`ApiError`），`code` 为稳定分类，`details` 为**脱敏后的排查现场**
（如 HTTP 状态码、是否可重试、响应体预览、尝试次数、熔断剩余冷却毫秒、掩码后的幂等键等），
**不包含**底层异常类名/堆栈，不直接透出 `HttpClient`、Jackson 等库的异常。

```json
{
  "code": "UPSTREAM_TIMEOUT",
  "message": "上游调用超时（>2000ms）",
  "idempotencyKey": "idem****",
  "details": { "timeoutMs": 2000, "upstream": "localhost:9000" },
  "time": "2026-10-08T01:00:00Z"
}
```

| code | HTTP | 含义 | 是否会被自动重试 |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | 参数校验失败 / 请求体不是合法 JSON（`details.fields` 给出字段） | 否 |
| `MISSING_IDEMPOTENCY_KEY` | 400 | 缺少 `Idempotency-Key` 头（与普通参数错误**分开**） | 否 |
| `IDEMPOTENCY_CONFLICT` | 409 | 幂等键被**不同内容**复用 | 否 |
| `IDEMPOTENCY_IN_FLIGHT` | 409 | 同键首个请求仍在进行，等待超时（请稍后用同键再试） | 否 |
| `IDEMPOTENCY_PREVIOUS_FAILED` | 409 | 同键首次请求已失败，重放失败结果（换新键才能重试） | 否 |
| `UPSTREAM_TIMEOUT` | 504 | 单次上游调用超时（内部归一码） | 是 |
| `UPSTREAM_ERROR` | 502 | 上游返回错误状态码（4xx 不可重试；408/429/5xx 可重试，见 `details.retryable`） | 视状态码 |
| `UPSTREAM_PARSE_ERROR` | 502 | 上游 2xx 但响应体无法解析为预期结构 | 否 |
| `CIRCUIT_OPEN` | 503 | 熔断打开，快速失败（含 `remainingOpenMillis`） | 否 |
| `UPSTREAM_UNAVAILABLE` | 503 | 可重试失败在有限次数后仍未成功（含 `attempt`/`maxAttempts`） | 否（已耗尽） |
| `INTERNAL_ERROR` | 500 | 兜底内部错误（详细堆栈只进服务日志） | 否 |

---

## 3. 重试与熔断规则

### 3.1 重试（`RetryExecutor`）

- 总尝试次数 = `resilience.retry.max-attempts`（含首次，默认 3）。
- **只**对“可安全重试”的失败重试：超时、连接类 IO 故障、HTTP 408 / 429 / 5xx。
- **不**重试：参数/幂等类 4xx、HTTP 422 等业务 4xx、2xx 响应解析失败、熔断拒绝。
- 退避：`min(maxBackoff, initialBackoff * multiplier^n)`，再乘 `[1-jitter, 1+jitter]` 随机系数。
- 等待重试期间线程被中断：恢复中断标志并立即失败，不再发起额外调用。
- 重试耗尽后抛 `UPSTREAM_UNAVAILABLE`，cause 保留最后一次失败，现场含尝试次数。

### 3.2 熔断（`CircuitBreaker`）

状态机：`CLOSED → OPEN → HALF_OPEN → CLOSED/OPEN`。

- `CLOSED`：正常放行；连续失败计数达到 `failure-threshold`（默认 5）后转到 `OPEN`。
- `OPEN`：在 `open-state-duration-ms`（默认 10s）内**快速失败**，直接返回 `CIRCUIT_OPEN`，不访问上游。
- 冷却结束后下一个请求把状态推进到 `HALF_OPEN`，**只放行一个试探请求**：
  - 成功 → `CLOSED`（清零计数）；
  - 失败 → 立即重新 `OPEN` 并重新计时。
- 熔断只记录“调用结果”，**不缓存也不冲掉**业务结果：OPEN 期间，此前已完成的同键结果仍正常重放。
- 是否放行只在一次业务调用的入口判定一次，避免 HALF_OPEN 的唯一名额被重复判定消耗。

### 3.3 与幂等的协作

业务调用顺序：`幂等抢占 → 熔断放行检查 → （重试器内多次）单次上游调用并即时反馈熔断 → 写入终态`。
这保证：同键并发只有一个请求真正触达上游；单次调用成败即时计入熔断，使连续故障能迅速触发快速失败。

---

## 4. 可配置项

均在 `src/main/resources/application.properties`，前缀 `resilience`，全部支持环境变量覆盖：

| 属性 | 默认 | 说明 |
|---|---|---|
| `resilience.upstream.base-url` | `http://localhost:9000` | 上游基础地址 |
| `resilience.upstream.connect-timeout-ms` | `1000` | 建连超时 |
| `resilience.upstream.request-timeout-ms` | `2000` | 单次请求超时 |
| `resilience.retry.max-attempts` | `3` | 总尝试次数（含首次） |
| `resilience.retry.initial-backoff-ms` | `100` | 首次退避 |
| `resilience.retry.backoff-multiplier` | `2.0` | 退避倍数 |
| `resilience.retry.max-backoff-ms` | `1000` | 退避上限 |
| `resilience.retry.jitter-ratio` | `0.2` | 抖动比例 |
| `resilience.circuit-breaker.failure-threshold` | `5` | 连续失败熔断阈值 |
| `resilience.circuit-breaker.open-state-duration-ms` | `10000` | OPEN 冷却时长 |
| `resilience.idempotency.ttl-millis` | `86400000` | 终态记录保留时长 |
| `resilience.idempotency.max-entries` | `10000` | 最大条目数 |
| `resilience.idempotency.wait-timeout-ms` | `10000` | 并发同键等待首个请求终态的超时 |

---

## 5. 代码结构

```
model/            ErrorCode / ApiError / PaymentRequest / PaymentResult
exception/        IntegrationException（统一携带错误码、现场、是否可重试）
idempotency/      IdempotencyStore + 内存实现（并发去重/冲突/终态重放/TTL&容量淘汰）
resilience/       CircuitBreaker、RetryExecutor、Sleeper
resilience/config ResilienceProperties、Spring 装配
client/           UpstreamPaymentClient（JDK HttpClient，异常归一）、JsonCodec、地址提供者
service/          PaymentService + DefaultPaymentService（幂等+重试+熔断编排）
web/              PaymentController、GlobalExceptionHandler
src/test/.../stub 本地桩上游 + 故障注入注册表（不访问外部网络）
src/test/.../tests 集成测试 + 存储单元测试
```

---

## 6. 本地验证

无需外部网络、无需真实凭据。测试会在同一进程内以随机端口同时启动“我方服务 + 本地桩”，
并把超时/退避/冷却调小以便快速完成。

```bash
# 运行全部测试（离线也可：mvn -o test）
mvn test
```

### 6.1 测试覆盖的业务场景

- `IdempotencyConcurrencyTest`：**并发重复提交**（10 线程同键同时提交，上游仅生效 1 次，其余重放同一 paymentId）；串行重复提交重放。
- `IdempotencyConflictAndValidationTest`：同键不同内容冲突、缺幂等键、参数非法、坏 JSON、字段顺序不同但指纹相同。
- `RetryTest`：瞬时 500 退避重试成功、持续失败重试耗尽、持续超时、解析失败不重试、422 不重试、429 可重试。
- `CircuitBreakerTest`：连续失败打开熔断后快速失败且不访问上游、冷却后 HALF_OPEN 试探恢复、OPEN 期间已完成结果仍可重放。
- `FailedRequestIdempotencyTest`：失败后同键重复提交不再打上游（防重复副作用），换新键才能成功。
- `IdempotencyStoreWaitTest`：进行中等待超时不被无限卡死；等待期间完成可拿到终态。

### 6.2 从日志查看覆盖情况

测试日志会打印业务动作与断言数据，例如：

```
>>> 同时放行 10 个相同幂等键的并发请求 key=...
结果：201 首次=1，200 重放=9，上游实际调用次数=1
OPEN 期间请求：status=503 code=CIRCUIT_OPEN，上游调用增量=0
冷却后试探：status=201 熔断状态=CLOSED
```

也可查看生成的 surefire 报告：

```bash
ls target/surefire-reports/
grep -h "Tests run" target/surefire-reports/*.txt
```

### 6.3 手动联调（可选）

```bash
mvn spring-boot:run
# 首次
curl -i -X POST http://localhost:8080/api/payments \
  -H 'Idempotency-Key: demo-key-1' -H 'Content-Type: application/json' \
  -d '{"amount":12.50,"payerAccount":"A","payeeAccount":"B"}'
# 再次提交（同键同体）-> 200 且 replayed=true、paymentId 不变
```

> 默认上游地址 `http://localhost:9000` 没有真实服务时，会按 `UPSTREAM_UNAVAILABLE`/`CIRCUIT_OPEN`
> 返回结构化错误，这正是容错层的预期表现。

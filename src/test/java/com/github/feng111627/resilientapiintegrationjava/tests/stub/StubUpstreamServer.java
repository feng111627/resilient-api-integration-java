package com.github.feng111627.resilientapiintegrationjava.tests.stub;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地桩上游：基于 JDK HttpServer，只监听 127.0.0.1，不需要外部网络。
 * 可编程故障行为；按 Idempotency-Key 去重副作用，模拟行为良好的上游。
 */
public class StubUpstreamServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StubUpstreamServer.class);

    /** 桩的响应描述。 */
    public record StubResponse(int status, String body, long delayMs) {
        public static StubResponse ok(String body) {
            return new StubResponse(200, body, 0);
        }

        public static StubResponse status(int status, String body) {
            return new StubResponse(status, body, 0);
        }

        public StubResponse withDelay(long ms) {
            return new StubResponse(status, body, ms);
        }
    }

    /** 行为函数：入参为第几次调用（从 1 开始）。 */
    public interface Behavior {
        StubResponse respond(int attempt);
    }

    private final HttpServer server;
    private final AtomicInteger attempts = new AtomicInteger();
    private final List<String> receivedIdempotencyKeys = new CopyOnWriteArrayList<>();
    /** 已成功处理的幂等键 -> 副作用计数只按唯一键计一次 */
    private final Map<String, String> processedKeys = new ConcurrentHashMap<>();
    private volatile Behavior behavior;

    public StubUpstreamServer(Behavior initialBehavior) {
        this.behavior = initialBehavior;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("桩服务启动失败", e);
        }
        server.createContext("/payments", exchange -> {
            int attempt = attempts.incrementAndGet();
            String idemKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            receivedIdempotencyKeys.add(idemKey);
            StubResponse resp;
            try {
                resp = behavior.respond(attempt);
            } catch (RuntimeException e) {
                resp = StubResponse.status(500, "behavior error");
            }
            log.info("[stub] 第 {} 次调用 key={} -> status={} delay={}ms",
                    attempt, idemKey, resp.status(), resp.delayMs());
            if (resp.delayMs() > 0) {
                try {
                    Thread.sleep(resp.delayMs());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            // 模拟上游幂等：同键已成功过则直接返回首次结果，不重复产生副作用
            final StubResponse finalResp = resp;
            final String body;
            if (resp.status() < 400 && idemKey != null) {
                body = processedKeys.computeIfAbsent(idemKey, k -> finalResp.body());
            } else {
                body = resp.body();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(resp.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        // 多线程处理：一个请求的延迟不应阻塞后续请求
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setBehavior(Behavior behavior) {
        this.behavior = behavior;
    }

    /** 到达上游的请求总数 */
    public int attemptCount() {
        return attempts.get();
    }

    /** 上游真实产生的副作用次数（按唯一幂等键计） */
    public int sideEffectCount() {
        return processedKeys.size();
    }

    public List<String> receivedIdempotencyKeys() {
        return List.copyOf(receivedIdempotencyKeys);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

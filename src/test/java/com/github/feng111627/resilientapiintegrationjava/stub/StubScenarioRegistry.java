package com.github.feng111627.resilientapiintegrationjava.stub;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用故障注入注册表。按 key 配置行为；可设置“前 n 次失败后成功”。
 */
public class StubScenarioRegistry {

    public static final class Rule {
        final StubBehavior behavior;
        final int failTimes; // >0 时前 failTimes 次按 behavior 失败，之后 OK

        Rule(StubBehavior behavior, int failTimes) {
            this.behavior = behavior;
            this.failTimes = failTimes;
        }
    }

    private final Map<String, Rule> rules = new ConcurrentHashMap<>();
    private final AtomicInteger globalCallCount = new AtomicInteger();
    private final Map<String, AtomicInteger> perKeyCounts = new ConcurrentHashMap<>();
    private volatile StubBehavior defaultBehavior = StubBehavior.OK;

    /** 设置未匹配到具体键时的默认行为（如全局 500）。 */
    public void setDefaultBehavior(StubBehavior behavior) {
        this.defaultBehavior = behavior;
    }

    public void setRule(String idempotencyKey, StubBehavior behavior, int failTimes) {
        rules.put(idempotencyKey, new Rule(behavior, failTimes));
    }

    public void setRule(String idempotencyKey, StubBehavior behavior) {
        setRule(idempotencyKey, behavior, Integer.MAX_VALUE);
    }

    public StubBehavior nextBehavior(String idempotencyKey) {
        globalCallCount.incrementAndGet();
        int count = perKeyCounts
                .computeIfAbsent(idempotencyKey, k -> new AtomicInteger())
                .incrementAndGet();
        Rule rule = rules.get(idempotencyKey);
        if (rule == null) {
            return defaultBehavior;
        }
        if (rule.failTimes != Integer.MAX_VALUE && count > rule.failTimes) {
            return StubBehavior.OK;
        }
        return rule.behavior;
    }

    public int globalCallCount() {
        return globalCallCount.get();
    }

    public int callCountOf(String idempotencyKey) {
        AtomicInteger c = perKeyCounts.get(idempotencyKey);
        return c == null ? 0 : c.get();
    }

    public void reset() {
        rules.clear();
        globalCallCount.set(0);
        perKeyCounts.clear();
        defaultBehavior = StubBehavior.OK;
    }
}

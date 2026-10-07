package com.github.feng111627.resilientapiintegrationjava.resilience;

/** 可替换的退避等待，便于测试中不真实 sleep。 */
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
}

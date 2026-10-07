package com.github.feng111627.resilientapiintegrationjava.resilience;

import org.springframework.stereotype.Component;

/** 默认基于 Thread.sleep 的退避等待。 */
@Component
public class DefaultSleeper implements Sleeper {
    @Override
    public void sleep(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}

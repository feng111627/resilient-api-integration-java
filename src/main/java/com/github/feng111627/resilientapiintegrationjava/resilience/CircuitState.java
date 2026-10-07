package com.github.feng111627.resilientapiintegrationjava.resilience;

/** 熔断器状态。 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

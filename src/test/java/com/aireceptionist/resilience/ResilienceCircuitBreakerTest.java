package com.aireceptionist.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilienceCircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .build();
        circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("llmService");
    }

    @Test
    void circuitOpensAfter5ConsecutiveFailures() {
        for (int i = 0; i < 5; i++) {
            try {
                circuitBreaker.executeSupplier(() -> {
                    throw new RuntimeException("LLM unavailable");
                });
            } catch (Exception ignored) {
            }
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void sixthCallImmediatelyRejectedWhenCircuitOpen() {
        for (int i = 0; i < 5; i++) {
            try {
                circuitBreaker.executeSupplier(() -> {
                    throw new RuntimeException("LLM unavailable");
                });
            } catch (Exception ignored) {
            }
        }

        assertThatThrownBy(() -> circuitBreaker.executeSupplier(() -> "real-response"))
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void circuitRemainsClosedWhileFailuresAreBelowThreshold() {
        for (int i = 0; i < 4; i++) {
            try {
                circuitBreaker.executeSupplier(() -> {
                    throw new RuntimeException("LLM unavailable");
                });
            } catch (Exception ignored) {
            }
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}

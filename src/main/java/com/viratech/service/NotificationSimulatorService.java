package com.viratech.service;

import com.viratech.dto.NotificationRequest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class NotificationSimulatorService {

    private final long delayMs;
    private final boolean failEnabled;

    private final AtomicLong receivedCalls = new AtomicLong();

    private final ConcurrentMap<String, DeliveryState> deliveries = new ConcurrentHashMap<>();

    public NotificationSimulatorService(
            @Value("${simulator.delay-ms:0}") long delayMs,
            @Value("${simulator.fail-enabled}") boolean failEnabled

    ) {
        if (delayMs < 0) {
            throw new IllegalArgumentException(
                    "Simulator.delay-ms mus not be negative");
        }

        this.delayMs = delayMs;
        this.failEnabled = failEnabled;
    }

    public void simulate(
            String idempotencyKey,
            NotificationRequest request
    ) {
        long callNumber = receivedCalls.incrementAndGet();

        log.info(
                "event=RECEIVED key={} call={} at={}",
                idempotencyKey,
                callNumber,
                Instant.now()
        );

        DeliveryState state = deliveries.computeIfAbsent(
                idempotencyKey,
                key -> new DeliveryState(request)
        );

        // Serializa chamadas da mesma chave.
        // Chaves diferentes podem ser processadas simultaneamente.
        synchronized (state) {
            if (!state.request.equals(request)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key already associated with another payload"
                );
            }

            if (state.completed) {
                log.info(
                        "event=DUPLICATE key={} at={}",
                        idempotencyKey,
                        Instant.now()
                );
                return;
            }

            waitForDelivery();

            if (failEnabled) {
                log.info(
                        "event=FAILED key={} at={}",
                        idempotencyKey,
                        Instant.now()
                );

                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Simulated notification failure"
                );
            }

            state.completed = true;

            log.info(
                    "event=COMPLETED key={} at={}",
                    idempotencyKey,
                    Instant.now()
            );
        }
    }

    private void waitForDelivery() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Notification simulation interrupted"
            );
        }
    }

    private static class DeliveryState {

        private final NotificationRequest request;
        private boolean completed;

        private DeliveryState(NotificationRequest request) {
            this.request = request;
        }
    }
}

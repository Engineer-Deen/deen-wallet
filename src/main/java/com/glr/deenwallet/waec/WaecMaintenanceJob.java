package com.glr.deenwallet.waec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WaecMaintenanceJob {

    private static final int MAX_DELIVERY_ATTEMPTS = 5;

    private final WaecPinService pinService;
    private final WaecOrderRepository orderRepository;
    private final WaecDeliveryService deliveryService;

    /** Gives back pins held for purchases that were cancelled/failed/never completed. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void releaseStaleReservations() {
        try {
            int released = pinService.releaseStaleReservations(Instant.now().minus(Duration.ofMinutes(30)));
            if (released > 0) log.info("Released {} stale WAEC pin reservations", released);
        } catch (Exception e) {
            log.warn("WAEC stale reservation cleanup failed: {}", e.toString());
        }
    }

    /** Retries pin emails that failed (or never ran), up to a few times. After that an admin can resend. */
    @Scheduled(fixedDelay = 180_000, initialDelay = 90_000)
    public void retryUndeliveredPins() {
        try {
            List<WaecOrder> due = orderRepository.findDeliveryRetryCandidates(
                    List.of(WaecDeliveryStatus.PENDING, WaecDeliveryStatus.FAILED),
                    MAX_DELIVERY_ATTEMPTS, Instant.now().minus(Duration.ofMinutes(3)), PageRequest.of(0, 20));
            for (WaecOrder o : due) {
                try {
                    deliveryService.deliver(o.getId(), null);
                } catch (Exception e) {
                    log.warn("Retry of WAEC pin email for {} failed: {}", o.getTransactionCode(), e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("WAEC delivery retry run failed: {}", e.toString());
        }
    }
}

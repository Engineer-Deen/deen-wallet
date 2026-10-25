package com.glr.deenwallet.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Finishes withdrawals from the Wallet screen: asks Monime what happened to each open payout, and re-sends
 * (with the same idempotency key, so never twice) a withdrawal whose first request got no clear answer.
 */
@Component
public class WalletReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(WalletReconcileJob.class);

    private final WalletService wallet;

    public WalletReconcileJob(WalletService wallet) {
        this.wallet = wallet;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void run() {
        try {
            wallet.syncPayoutRefs();
        } catch (Exception e) {
            log.warn("Wallet reference sync failed: {}", e.toString());
        }
        try {
            for (UUID id : wallet.openWithdrawalIds()) {
                try {
                    wallet.reconcile(id);
                } catch (Exception e) {
                    log.warn("Wallet reconcile failed for withdrawal {}: {}", id, e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Wallet reconcile run failed: {}", e.toString());
        }
    }
}

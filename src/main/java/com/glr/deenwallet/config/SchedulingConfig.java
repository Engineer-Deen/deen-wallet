package com.glr.deenwallet.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables @Scheduled methods across the app — currently only used by
 * TransactionExpiryScheduler. If your main application class already
 * has @EnableScheduling on it, this one is redundant (harmless either
 * way) — feel free to delete this file and add @EnableScheduling to
 * your existing DeenWalletApplication class instead.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

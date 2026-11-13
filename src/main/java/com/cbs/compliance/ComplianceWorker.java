package com.cbs.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background re-screening after list activation, and post-event transaction monitoring. */
@Component
@ConditionalOnProperty(name={"cbs.tenancy.enabled","cbs.ledger.delivery-enabled"},havingValue="true",matchIfMissing=true)
public class ComplianceWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ComplianceWorker.class);
    private final ScreeningService screening;
    private final MonitoringService monitoring;

    public ComplianceWorker(ScreeningService screening, MonitoringService monitoring) { this.screening = screening; this.monitoring = monitoring; }

    @Scheduled(fixedDelay=15000, initialDelay=40000)
    public void rescreen() {
        try { screening.scheduleRescreens(); screening.processRuns(40); } catch (RuntimeException e) { LOG.warn("Screening re-run will be retried: {}", e.getClass().getSimpleName()); }
    }

    @Scheduled(fixedDelay=60000, initialDelay=45000)
    public void monitor() {
        try { monitoring.scan(); } catch (RuntimeException e) { LOG.warn("Transaction monitoring scan will be retried: {}", e.getClass().getSimpleName()); }
    }
}

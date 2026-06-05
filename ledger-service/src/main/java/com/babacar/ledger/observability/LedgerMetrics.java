package com.babacar.ledger.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class LedgerMetrics {

    private final Counter transfersCounter;
    private final Counter reversalsCounter;
    private final Counter failedCounter;

    public LedgerMetrics(MeterRegistry registry) {
        transfersCounter = Counter.builder("ledger_transfers_total")
                .description("Total completed transfers").register(registry);
        reversalsCounter = Counter.builder("ledger_reversals_total")
                .description("Total reversed transfers").register(registry);
        failedCounter = Counter.builder("ledger_transfers_failed_total")
                .description("Total failed transfers").register(registry);
    }

    public void incrementTransfers() { transfersCounter.increment(); }
    public void incrementReversals() { reversalsCounter.increment(); }
    public void incrementFailed()    { failedCounter.increment(); }
}

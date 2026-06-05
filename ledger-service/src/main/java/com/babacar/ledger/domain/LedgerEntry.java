package com.babacar.ledger.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable ledger entry — the financial source of truth.
 *
 * Rules:
 * - Entries are NEVER updated or deleted.
 * - Every transfer produces exactly 2 entries: one DEBIT + one CREDIT.
 * - Sum of all DEBITs = Sum of all CREDITs (double-entry invariant).
 * - Reversals create new entries, not modifications.
 */
@Entity
@Table(name = "ledger_entries")
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType;   // DEBIT or CREDIT

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;   // snapshot at time of entry

    @Column(name = "reference")
    private String reference;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = Instant.now();
    }
}

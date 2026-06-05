package com.babacar.ledger.service.dto;

import com.babacar.ledger.domain.EntryType;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class LedgerEntryResponse {
    private UUID id;
    private UUID accountId;
    private UUID transferId;
    private EntryType entryType;
    private BigDecimal amount;
    private String currency;
    private BigDecimal balanceAfter;
    private String reference;
    private String description;
    private Instant createdAt;
}

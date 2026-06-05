package com.babacar.ledger.service.dto;

import com.babacar.ledger.domain.TransferStatus;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class TransferResponse {
    private UUID id;
    private UUID debitAccountId;
    private UUID creditAccountId;
    private BigDecimal amount;
    private String currency;
    private String reference;
    private String description;
    private TransferStatus status;
    private UUID reversedTransferId;
    private Instant createdAt;
}

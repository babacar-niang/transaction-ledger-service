package com.babacar.ledger.service.dto;

import com.babacar.ledger.domain.*;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class AccountResponse {
    private UUID id;
    private String ownerId;
    private String currency;
    private AccountType type;
    private AccountStatus status;
    private BigDecimal balance;
    private Instant createdAt;
}

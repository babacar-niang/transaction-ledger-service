package com.babacar.ledger.service;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(UUID accountId, BigDecimal required, BigDecimal available) {
        super(String.format("Insufficient balance on account %s: required %s, available %s",
                accountId, required, available));
    }
}

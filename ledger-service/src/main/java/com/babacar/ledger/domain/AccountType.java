package com.babacar.ledger.domain;

public enum AccountType {
    CUSTOMER,
    MERCHANT,
    FEE,
    SUSPENSE,   // temporary holding account
    SYSTEM      // internal system account
}

package com.babacar.ledger.service.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class TransferRequest {

    @NotNull
    private UUID debitAccountId;

    @NotNull
    private UUID creditAccountId;

    @NotNull
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank @Size(min = 3, max = 3)
    private String currency;

    private String reference;
    private String description;
}

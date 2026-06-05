package com.babacar.ledger.service.dto;

import com.babacar.ledger.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAccountRequest {
    @NotBlank
    private String ownerId;

    @NotBlank @Size(min = 3, max = 3)
    private String currency;

    @NotNull
    private AccountType type;
}

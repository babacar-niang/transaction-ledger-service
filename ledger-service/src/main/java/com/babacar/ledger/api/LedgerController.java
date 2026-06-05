package com.babacar.ledger.api;

import com.babacar.ledger.service.LedgerService;
import com.babacar.ledger.service.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.babacar.ledger.service.dto.FundAccountRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Ledger", description = "Double-entry financial ledger endpoints")
public class LedgerController {

    private final LedgerService ledgerService;

    // ── Accounts ─────────────────────────────────────────────────────────────

    @PostMapping("/accounts")
    @Operation(summary = "Create a new ledger account")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledgerService.createAccount(request));
    }

    @GetMapping("/accounts/{accountId}")
    @Operation(summary = "Get account details and current balance")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID accountId) {
        return ResponseEntity.ok(ledgerService.getAccount(accountId));
    }

    @GetMapping("/accounts/{accountId}/balance")
    @Operation(summary = "Get current balance for an account")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable UUID accountId) {
        AccountResponse account = ledgerService.getAccount(accountId);
        return ResponseEntity.ok(Map.of(
                "accountId", accountId,
                "balance", account.getBalance(),
                "currency", account.getCurrency()
        ));
    }

    @PostMapping("/accounts/{accountId}/fund")
    @Operation(
    summary = "Fund an account from the system treasury",
    description = "Credits the account via a real double-entry transfer from SYSTEM_TREASURY. Use this to seed accounts for demos and testing."
)
public ResponseEntity<TransferResponse> fundAccount(
        @PathVariable UUID accountId,
        @Valid @RequestBody FundAccountRequest request) {

    return ResponseEntity.status(HttpStatus.CREATED)
            .body(ledgerService.fundAccount(accountId, request));
}

    @GetMapping("/accounts/{accountId}/entries")
    @Operation(summary = "Get ledger entries (audit trail) for an account")
    public ResponseEntity<List<LedgerEntryResponse>> getEntries(
            @PathVariable UUID accountId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(ledgerService.getAccountEntries(accountId, type, from, to));
    }

    // ── Transfers ────────────────────────────────────────────────────────────

    @PostMapping("/transfers")
    @Operation(summary = "Execute a double-entry transfer between two accounts")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledgerService.transfer(request));
    }

    @PostMapping("/transfers/{transferId}/reverse")
    @Operation(summary = "Reverse a completed transfer — creates new reversal entries")
    public ResponseEntity<TransferResponse> reverse(
            @PathVariable UUID transferId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ledgerService.reverse(transferId, body.get("reason")));
    }
}

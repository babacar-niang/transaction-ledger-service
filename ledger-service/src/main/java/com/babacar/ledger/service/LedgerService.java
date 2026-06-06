package com.babacar.ledger.service;

import com.babacar.ledger.domain.*;
import com.babacar.ledger.kafka.LedgerEventProducer;
import com.babacar.ledger.observability.LedgerMetrics;
import com.babacar.ledger.service.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final TransferRepository transferRepository;
    private final LedgerEventProducer eventProducer;
    private final LedgerMetrics metrics;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = Account.builder()
                .ownerId(request.getOwnerId())
                .currency(request.getCurrency())
                .type(request.getType())
                .balance(java.math.BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .build();
        account = accountRepository.save(account);
        log.info("Account created: {} for owner {}", account.getId(), account.getOwnerId());
        return toAccountResponse(account);
    }

    public AccountResponse getAccount(UUID accountId) {
        return toAccountResponse(findAccount(accountId));
    }

    /**
     * Executes a double-entry transfer with manual retry on optimistic lock conflicts.
     * Each retry uses REQUIRES_NEW to get a fresh transaction with updated version numbers.
     * Backoff: 10ms → 20ms → 40ms → 80ms → 160ms
     */
    public TransferResponse transfer(TransferRequest request) {
        int maxAttempts = 5;
        long backoffMs  = 10;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeTransfer(request);
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == maxAttempts) {
                    log.error("Transfer failed after {} attempts — concurrent conflict", maxAttempts);
                    metrics.incrementFailed();
                    throw new IllegalStateException("Transfer failed after max retries", e);
                }
                log.warn("Optimistic lock conflict attempt {}/{} — retrying in {}ms",
                        attempt, maxAttempts, backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Transfer interrupted during retry", ie);
                }
                backoffMs *= 2;
            }
        }
        throw new IllegalStateException("Transfer failed unexpectedly");
    }

    /**
     * Core transfer logic in its own transaction (REQUIRES_NEW).
     * Each retry gets fresh account versions from DB — no stale state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransferResponse executeTransfer(TransferRequest request) {
        Account debitAccount  = findAccountWithLock(request.getDebitAccountId());
        Account creditAccount = findAccountWithLock(request.getCreditAccountId());

        validateTransfer(debitAccount, creditAccount, request);

        debitAccount.setBalance(debitAccount.getBalance().subtract(request.getAmount()));
        creditAccount.setBalance(creditAccount.getBalance().add(request.getAmount()));

        accountRepository.save(debitAccount);
        accountRepository.save(creditAccount);

        Transfer transfer = Transfer.builder()
                .debitAccountId(debitAccount.getId())
                .creditAccountId(creditAccount.getId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .reference(request.getReference())
                .description(request.getDescription())
                .status(TransferStatus.COMPLETED)
                .build();
        transfer = transferRepository.save(transfer);

        createEntry(debitAccount, transfer, EntryType.DEBIT);
        createEntry(creditAccount, transfer, EntryType.CREDIT);

        metrics.incrementTransfers();
        log.info("Transfer completed: {} | {} {} from {} to {}",
                transfer.getId(), request.getAmount(), request.getCurrency(),
                debitAccount.getId(), creditAccount.getId());

        eventProducer.publishTransferCompleted(transfer);
        return toTransferResponse(transfer);
    }

    /**
     * Reverses a transfer — new entries created, original never modified.
     */
    @Transactional
    public TransferResponse reverse(UUID transferId, String reason) {
        Transfer original = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (original.getStatus() == TransferStatus.REVERSED) {
            throw new IllegalStateException("Transfer already reversed: " + transferId);
        }

        Account debitAccount  = findAccountWithLock(original.getCreditAccountId());
        Account creditAccount = findAccountWithLock(original.getDebitAccountId());

        debitAccount.setBalance(debitAccount.getBalance().subtract(original.getAmount()));
        creditAccount.setBalance(creditAccount.getBalance().add(original.getAmount()));

        accountRepository.save(debitAccount);
        accountRepository.save(creditAccount);

        Transfer reversal = Transfer.builder()
                .debitAccountId(debitAccount.getId())
                .creditAccountId(creditAccount.getId())
                .amount(original.getAmount())
                .currency(original.getCurrency())
                .reference("REV-" + original.getReference())
                .description("Reversal: " + reason)
                .status(TransferStatus.COMPLETED)
                .reversedTransferId(original.getId())
                .reversalReason(reason)
                .build();
        reversal = transferRepository.save(reversal);

        createEntry(debitAccount, reversal, EntryType.DEBIT);
        createEntry(creditAccount, reversal, EntryType.CREDIT);

        original.setStatus(TransferStatus.REVERSED);
        transferRepository.save(original);

        metrics.incrementReversals();
        log.info("Transfer reversed: {} -> reversal: {}", transferId, reversal.getId());

        eventProducer.publishTransferReversed(reversal);
        return toTransferResponse(reversal);
    }

    public List<LedgerEntryResponse> getAccountEntries(
            UUID accountId, String type, Instant from, Instant to) {
        List<LedgerEntry> entries;
        if (from != null && to != null) {
            entries = entryRepository.findByAccountIdAndDateRange(accountId, from, to);
        } else if (type != null) {
            entries = entryRepository.findByAccountIdAndEntryTypeOrderByCreatedAtDesc(
                    accountId, EntryType.valueOf(type.toUpperCase()));
        } else {
            entries = entryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
        }
        return entries.stream().map(this::toEntryResponse).collect(Collectors.toList());
    }

    @Transactional
    public UUID getOrCreateTreasuryAccount() {
        return accountRepository.findByOwnerId("SYSTEM_TREASURY")
                .stream().findFirst()
                .map(Account::getId)
                .orElseGet(() -> {
                    Account treasury = Account.builder()
                            .ownerId("SYSTEM_TREASURY")
                            .currency("XOF")
                            .type(AccountType.SYSTEM)
                            .balance(new java.math.BigDecimal("999999999"))
                            .status(AccountStatus.ACTIVE)
                            .build();
                    Account saved = accountRepository.save(treasury);
                    log.info("Treasury account created: {}", saved.getId());
                    return saved.getId();
                });
    }

    @Transactional
    public TransferResponse fundAccount(UUID accountId, FundAccountRequest request) {
        UUID treasuryId = getOrCreateTreasuryAccount();

        TransferRequest transferRequest = new TransferRequest();
        transferRequest.setDebitAccountId(treasuryId);
        transferRequest.setCreditAccountId(accountId);
        transferRequest.setAmount(request.amount());
        transferRequest.setCurrency("XOF");
        transferRequest.setReference(
                request.reference() != null ? request.reference() : "FUNDING");
        transferRequest.setDescription("Account funding from system treasury");

        log.info("Funding account {} with {} XOF", accountId, request.amount());
        return transfer(transferRequest);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void createEntry(Account account, Transfer transfer, EntryType type) {
        LedgerEntry entry = LedgerEntry.builder()
                .accountId(account.getId())
                .transferId(transfer.getId())
                .entryType(type)
                .amount(transfer.getAmount())
                .currency(transfer.getCurrency())
                .balanceAfter(account.getBalance())
                .reference(transfer.getReference())
                .description(transfer.getDescription())
                .build();
        entryRepository.save(entry);
    }

    private void validateTransfer(Account debit, Account credit, TransferRequest req) {
        if (debit.getStatus() != AccountStatus.ACTIVE)
            throw new IllegalStateException("Debit account is not active: " + debit.getId());
        if (credit.getStatus() != AccountStatus.ACTIVE)
            throw new IllegalStateException("Credit account is not active: " + credit.getId());
        if (!debit.getCurrency().equals(req.getCurrency()))
            throw new IllegalArgumentException("Currency mismatch on debit account");
        if (!debit.hasSufficientBalance(req.getAmount()))
            throw new InsufficientBalanceException(
                    debit.getId(), req.getAmount(), debit.getBalance());
    }

    private Account findAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    private Account findAccountWithLock(UUID id) {
        return accountRepository.findByIdWithLock(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    private AccountResponse toAccountResponse(Account a) {
        return AccountResponse.builder()
                .id(a.getId()).ownerId(a.getOwnerId())
                .currency(a.getCurrency()).type(a.getType())
                .status(a.getStatus()).balance(a.getBalance())
                .createdAt(a.getCreatedAt()).build();
    }

    private TransferResponse toTransferResponse(Transfer t) {
        return TransferResponse.builder()
                .id(t.getId()).debitAccountId(t.getDebitAccountId())
                .creditAccountId(t.getCreditAccountId()).amount(t.getAmount())
                .currency(t.getCurrency()).reference(t.getReference())
                .description(t.getDescription()).status(t.getStatus())
                .reversedTransferId(t.getReversedTransferId())
                .createdAt(t.getCreatedAt()).build();
    }

    private LedgerEntryResponse toEntryResponse(LedgerEntry e) {
        return LedgerEntryResponse.builder()
                .id(e.getId()).accountId(e.getAccountId())
                .transferId(e.getTransferId()).entryType(e.getEntryType())
                .amount(e.getAmount()).currency(e.getCurrency())
                .balanceAfter(e.getBalanceAfter()).reference(e.getReference())
                .description(e.getDescription()).createdAt(e.getCreatedAt()).build();
    }
}
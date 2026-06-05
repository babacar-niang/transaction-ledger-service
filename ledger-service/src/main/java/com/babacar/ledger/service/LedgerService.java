package com.babacar.ledger.service;

import com.babacar.ledger.domain.*;
import com.babacar.ledger.kafka.LedgerEventProducer;
import com.babacar.ledger.observability.LedgerMetrics;
import com.babacar.ledger.service.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
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
     * Executes a double-entry transfer atomically.
     * Retries on optimistic locking conflicts (concurrent modifications).
     *
     * Steps:
     * 1. Load both accounts with optimistic lock
     * 2. Validate balance
     * 3. Debit sender, Credit receiver
     * 4. Create immutable ledger entries
     * 5. Save transfer record
     * 6. Publish Kafka event
     */
    @Transactional
    @Retryable(
        retryFor = ObjectOptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public TransferResponse transfer(TransferRequest request) {
        Account debitAccount  = findAccountWithLock(request.getDebitAccountId());
        Account creditAccount = findAccountWithLock(request.getCreditAccountId());

        validateTransfer(debitAccount, creditAccount, request);

        // Apply double-entry — atomic within this transaction
        debitAccount.setBalance(debitAccount.getBalance().subtract(request.getAmount()));
        creditAccount.setBalance(creditAccount.getBalance().add(request.getAmount()));

        accountRepository.save(debitAccount);
        accountRepository.save(creditAccount);

        // Create immutable ledger entries
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
     * Reverses a transfer by creating a new transfer in the opposite direction.
     * Original entries are never modified — new reversal entries are created.
     */
    @Transactional
    @Retryable(
        retryFor = ObjectOptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public TransferResponse reverse(UUID transferId, String reason) {
        Transfer original = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (original.getStatus() == TransferStatus.REVERSED) {
            throw new IllegalStateException("Transfer already reversed: " + transferId);
        }

        // Reversal = swap debit/credit
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

    public List<LedgerEntryResponse> getAccountEntries(UUID accountId, String type, Instant from, Instant to) {
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
            throw new InsufficientBalanceException(debit.getId(), req.getAmount(), debit.getBalance());
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
   /**
 * Seeds the treasury account on first run.
 * Called at startup via @PostConstruct or ApplicationRunner.
 */
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

/**
 * Funds a customer account from the system treasury.
 * Produces a real double-entry transfer: TREASURY debited, customer credited.
 */
@Transactional
public TransferResponse fundAccount(UUID accountId, FundAccountRequest request) {
    UUID treasuryId = getOrCreateTreasuryAccount();

    TransferRequest transferRequest = new TransferRequest();
    transferRequest.setDebitAccountId(treasuryId);
    transferRequest.setCreditAccountId(accountId);
    transferRequest.setAmount(request.amount());
    transferRequest.setCurrency("XOF");
    transferRequest.setReference(request.reference() != null ? request.reference() : "FUNDING");
    transferRequest.setDescription("Account funding from system treasury");

    log.info("Funding account {} with {} XOF", accountId, request.amount());
    return transfer(transferRequest);
} 
}

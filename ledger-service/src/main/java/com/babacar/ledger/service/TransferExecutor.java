package com.babacar.ledger.service;

import com.babacar.ledger.domain.*;
import com.babacar.ledger.kafka.LedgerEventProducer;
import com.babacar.ledger.observability.LedgerMetrics;
import com.babacar.ledger.service.dto.TransferRequest;
import com.babacar.ledger.service.dto.TransferResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class TransferExecutor {

    private final AccountRepository      accountRepository;
    private final LedgerEntryRepository  entryRepository;
    private final TransferRepository     transferRepository;
    private final LedgerEventProducer    eventProducer;
    private final LedgerMetrics          metrics;

    /**
     * Executes a single transfer attempt in a brand-new transaction.
     * REQUIRES_NEW ensures each retry reads fresh account versions from DB.
     * Spring AOP proxy intercepts this because it's called from LedgerService (different bean).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransferResponse execute(TransferRequest request) {

        Account debitAccount = accountRepository.findByIdWithLock(request.getDebitAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.getDebitAccountId()));
        Account creditAccount = accountRepository.findByIdWithLock(request.getCreditAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.getCreditAccountId()));

        validate(debitAccount, creditAccount, request);

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

    private void validate(Account debit, Account credit, TransferRequest req) {
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

    private TransferResponse toTransferResponse(Transfer t) {
        return TransferResponse.builder()
                .id(t.getId())
                .debitAccountId(t.getDebitAccountId())
                .creditAccountId(t.getCreditAccountId())
                .amount(t.getAmount())
                .currency(t.getCurrency())
                .reference(t.getReference())
                .description(t.getDescription())
                .status(t.getStatus())
                .reversedTransferId(t.getReversedTransferId())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
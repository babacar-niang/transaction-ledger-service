package com.babacar.ledger.service;

import com.babacar.ledger.domain.EntryType;
import com.babacar.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    List<LedgerEntry> findByAccountIdAndEntryTypeOrderByCreatedAtDesc(UUID accountId, EntryType entryType);

    @Query("SELECT e FROM LedgerEntry e WHERE e.accountId = :accountId " +
           "AND e.createdAt BETWEEN :from AND :to ORDER BY e.createdAt DESC")
    List<LedgerEntry> findByAccountIdAndDateRange(UUID accountId, Instant from, Instant to);

    List<LedgerEntry> findByTransferId(UUID transferId);
}

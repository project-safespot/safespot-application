package com.safespot.apicore.repository;

import com.safespot.apicore.domain.entity.EntryDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EntryDetailRepository extends JpaRepository<EntryDetail, Long> {
    Optional<EntryDetail> findByEntryId(Long entryId);
}

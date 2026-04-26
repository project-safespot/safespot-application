package com.safespot.asyncworker.repository;

import java.util.List;
import java.util.Optional;

public interface DisasterAlertRepository {

    Optional<DisasterAlertRecord> findById(Long alertId);

    List<DisasterAlertRecord> findInScopeOrderByIssuedAtDesc(int limit);

    Optional<DisasterAlertRecord> findCoreMessage();
}

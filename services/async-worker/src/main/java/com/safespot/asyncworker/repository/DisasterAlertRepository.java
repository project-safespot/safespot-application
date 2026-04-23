package com.safespot.asyncworker.repository;

import java.util.List;
import java.util.Optional;

public interface DisasterAlertRepository {

    List<DisasterAlertRecord> findActiveByRegion(String region);

    List<DisasterAlertRecord> findByRegionAndDisasterType(String region, String disasterType);

    Optional<DisasterAlertRecord> findById(Long alertId);

    Optional<DisasterAlertRecord> findLatestActiveByTypeAndRegion(String disasterType, String region);
}

package com.safespot.asyncworker.service.disaster;

import com.safespot.asyncworker.payload.DisasterDataCollectedPayload;
import com.safespot.asyncworker.redis.DisasterActiveItem;
import com.safespot.asyncworker.redis.DisasterAlertListItem;
import com.safespot.asyncworker.redis.DisasterDetailCacheValue;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.repository.DisasterAlertRecord;
import com.safespot.asyncworker.repository.DisasterAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisasterReadModelService {

    private final DisasterAlertRepository disasterAlertRepository;
    private final RedisCacheWriter cacheWriter;

    public void rebuild(DisasterDataCollectedPayload payload) {
        String region = payload.region();
        String disasterType = payload.collectionType();

        if (payload.hasExpiredAlerts()) {
            cacheWriter.deleteDisasterActive(region);
            log.info("Deleted disaster:active:{}: hasExpiredAlerts=true", region);
        }

        rebuildActiveList(region);
        rebuildAlertList(region, disasterType);
        rebuildDetails(payload.affectedAlertIds());

        log.info("Disaster read model rebuilt: region={}, disasterType={}", region, disasterType);
    }

    private void rebuildActiveList(String region) {
        List<DisasterAlertRecord> records = disasterAlertRepository.findActiveByRegion(region);
        List<DisasterActiveItem> items = records.stream()
            .map(r -> new DisasterActiveItem(
                r.alertId(), r.disasterType(), r.region(),
                r.level(), r.issuedAt(), r.expiredAt()
            ))
            .toList();
        cacheWriter.setDisasterActive(region, items);
        log.info("disaster:active:{} SET: count={}", region, items.size());
    }

    private void rebuildAlertList(String region, String disasterType) {
        List<DisasterAlertRecord> records = disasterAlertRepository.findByRegionAndDisasterType(region, disasterType);
        List<DisasterAlertListItem> items = records.stream()
            .map(r -> new DisasterAlertListItem(r.alertId(), r.level(), r.issuedAt(), r.expiredAt()))
            .toList();
        cacheWriter.setDisasterAlertList(region, disasterType, items);
        log.info("disaster:alert:list:{}:{} SET: count={}", region, disasterType, items.size());
    }

    private void rebuildDetails(List<Long> affectedAlertIds) {
        for (Long alertId : affectedAlertIds) {
            disasterAlertRepository.findById(alertId).ifPresentOrElse(
                r -> {
                    DisasterDetailCacheValue value = new DisasterDetailCacheValue(
                        r.alertId(), r.disasterType(), r.region(),
                        r.level(), r.message(), r.source(),
                        r.issuedAt(), r.expiredAt()
                    );
                    cacheWriter.setDisasterDetail(alertId, value);
                },
                () -> log.warn("disaster:detail:{} skipped: alertId not found in RDS", alertId)
            );
        }
    }
}

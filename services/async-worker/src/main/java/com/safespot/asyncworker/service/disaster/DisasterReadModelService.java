package com.safespot.asyncworker.service.disaster;

import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.payload.DisasterDataCollectedPayload;
import com.safespot.asyncworker.redis.DisasterActiveItem;
import com.safespot.asyncworker.redis.DisasterAlertListItem;
import com.safespot.asyncworker.redis.DisasterDetailCacheValue;
import com.safespot.asyncworker.redis.DisasterLatestCacheValue;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.repository.DisasterAlertRecord;
import com.safespot.asyncworker.repository.DisasterAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Profile("readmodel-worker")
@Slf4j
@Service
@RequiredArgsConstructor
public class DisasterReadModelService {

    private final DisasterAlertRepository disasterAlertRepository;
    private final RedisCacheWriter cacheWriter;

    public void rebuild(DisasterDataCollectedPayload payload) {
        validate(payload);
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

    private void validate(DisasterDataCollectedPayload payload) {
        if (payload.region() == null || payload.region().isBlank()) {
            throw new EventProcessingException("DisasterDataCollected payload missing region");
        }
        if (payload.collectionType() == null || payload.collectionType().isBlank()) {
            throw new EventProcessingException("DisasterDataCollected payload missing collectionType");
        }
        if (payload.affectedAlertIds() == null) {
            throw new EventProcessingException("DisasterDataCollected payload missing affectedAlertIds");
        }
    }

    public void rebuildActiveList(String region) {
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

    public void rebuildAlertList(String region, String disasterType) {
        List<DisasterAlertRecord> records = disasterAlertRepository.findByRegionAndDisasterType(region, disasterType);
        List<DisasterAlertListItem> items = records.stream()
            .map(r -> new DisasterAlertListItem(r.alertId(), r.level(), r.issuedAt(), r.expiredAt()))
            .toList();
        cacheWriter.setDisasterAlertList(region, disasterType, items);
        log.info("disaster:alert:list:{}:{} SET: count={}", region, disasterType, items.size());
    }

    public void rebuildDetail(Long alertId) {
        rebuildDetails(List.of(alertId));
    }

    public void rebuildLatest(String disasterType, String region) {
        disasterAlertRepository.findLatestActiveByTypeAndRegion(disasterType, region)
            .ifPresentOrElse(
                r -> {
                    // pointer 형태만 저장 — detail은 disaster:detail:{alertId}를 참조
                    cacheWriter.setDisasterLatest(disasterType, region, new DisasterLatestCacheValue(r.alertId()));
                    log.info("disaster:latest:{}:{} SET: alertId={}", disasterType, region, r.alertId());
                },
                () -> {
                    cacheWriter.deleteDisasterLatest(disasterType, region);
                    log.info("disaster:latest:{}:{} DEL: no active alert found", disasterType, region);
                }
            );
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

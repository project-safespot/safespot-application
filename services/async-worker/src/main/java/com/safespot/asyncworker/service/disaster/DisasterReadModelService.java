package com.safespot.asyncworker.service.disaster;

import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.payload.DisasterDataCollectedPayload;
import com.safespot.asyncworker.redis.DisasterDetailCacheValue;
import com.safespot.asyncworker.redis.DisasterMessageItem;
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

    private static final int RECENT_LIMIT = 5;
    private static final int LIST_LIMIT   = 50;

    private final DisasterAlertRepository disasterAlertRepository;
    private final RedisCacheWriter cacheWriter;

    // DisasterDataCollected 처리 진입점
    // async-worker.md §5.2 rebuild 순서: detail → recent → core → list
    public void rebuild(DisasterDataCollectedPayload payload) {
        validate(payload);
        rebuildDetails(payload.affectedAlertIds());
        rebuildRecent();
        rebuildCore();
        rebuildList();
        log.info("Disaster read model rebuilt: affectedAlertIds={}", payload.affectedAlertIds());
    }

    // CacheRegenerationRequested 처리 — key별 개별 rebuild
    public void rebuildDetail(Long alertId) {
        rebuildDetails(List.of(alertId));
    }

    public void rebuildRecent() {
        List<DisasterAlertRecord> records = disasterAlertRepository.findInScopeOrderByIssuedAtDesc(RECENT_LIMIT);
        List<DisasterMessageItem> items = records.stream().map(this::toMessageItem).toList();
        cacheWriter.setDisasterMessagesRecent(items);
        log.info("disaster:messages:recent:seoul SET: count={}", items.size());
    }

    public void rebuildCore() {
        disasterAlertRepository.findCoreMessage().ifPresentOrElse(
            r -> {
                cacheWriter.setDisasterMessageCore(toMessageItem(r));
                log.info("disaster:message:core:seoul SET: alertId={}", r.alertId());
            },
            () -> {
                cacheWriter.setDisasterMessageCoreEmpty();
                log.info("disaster:message:core:seoul SET: no candidate, empty wrapper");
            }
        );
    }

    public void rebuildList() {
        List<DisasterAlertRecord> records = disasterAlertRepository.findInScopeOrderByIssuedAtDesc(LIST_LIMIT);
        List<DisasterMessageItem> items = records.stream().map(this::toMessageItem).toList();
        cacheWriter.setDisasterMessagesList(items);
        log.info("disaster:messages:list:seoul SET: count={}", items.size());
    }

    private void rebuildDetails(List<Long> alertIds) {
        for (Long alertId : alertIds) {
            disasterAlertRepository.findById(alertId).ifPresentOrElse(
                r -> {
                    cacheWriter.setDisasterDetail(alertId, toDetailValue(r));
                    log.info("disaster:detail:{} SET", alertId);
                },
                () -> {
                    cacheWriter.deleteDisasterDetail(alertId);
                    log.info("disaster:detail:{} DEL: alertId not found in RDS, stale key removed", alertId);
                }
            );
        }
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

    private DisasterMessageItem toMessageItem(DisasterAlertRecord r) {
        return new DisasterMessageItem(
            1,
            r.alertId(),
            r.disasterType(),
            r.rawType(),
            r.messageCategory(),
            r.level(),
            r.levelRank(),
            r.region(),
            r.issuedAt(),
            r.expiredAt(),
            r.message(),
            r.source(),
            r.isInScope()
        );
    }

    private DisasterDetailCacheValue toDetailValue(DisasterAlertRecord r) {
        return new DisasterDetailCacheValue(
            1,
            r.alertId(),
            r.disasterType(),
            r.rawType(),
            r.messageCategory(),
            r.level(),
            r.levelRank(),
            r.region(),
            r.issuedAt(),
            r.expiredAt(),
            r.message(),
            r.source(),
            r.isInScope()
        );
    }
}

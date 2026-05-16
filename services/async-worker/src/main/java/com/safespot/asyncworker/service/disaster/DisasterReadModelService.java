package com.safespot.asyncworker.service.disaster;

import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.metrics.WorkerMetrics;
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

@Profile({"readmodel-worker", "async-worker"})
@Slf4j
@Service
@RequiredArgsConstructor
public class DisasterReadModelService {

    private static final int RECENT_LIMIT = 5;
    private static final int LIST_LIMIT   = 50;

    private final DisasterAlertRepository disasterAlertRepository;
    private final RedisCacheWriter cacheWriter;
    private final WorkerMetrics workerMetrics;

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

    // DisasterReadModelWarmupRequested 처리 진입점.
    // 기존 Redis writer를 재사용하므로 warmup path와 regeneration path가 동일 TTL 정책을 쓴다.
    // TTL은 safety cap(3600s + 0~120s jitter)이며 freshness 보장 수단이 아니다.
    // 최신성은 ingestion/update event 기반 regeneration이 담당한다.
    public void warmupAll(int limit, boolean includeDetails) {
        List<DisasterAlertRecord> listRecords = disasterAlertRepository.findInScopeOrderByIssuedAtDesc(LIST_LIMIT);
        cacheWriter.setDisasterMessagesList(listRecords.stream().map(this::toMessageItem).toList());
        log.info("disaster:messages:list:seoul SET: count={}", listRecords.size());

        rebuildDetailsFromRecords(listRecords, "warmup-list");
        if (includeDetails) {
            rebuildAllDetails(limit);
        }
        rebuildRecent();
        rebuildCore();
        log.info("Disaster read model warmed up: limit={}, includeDetails={}", limit, includeDetails);
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

    private void rebuildAllDetails(int limit) {
        List<DisasterAlertRecord> records = disasterAlertRepository.findInScopeOrderByIssuedAtDesc(limit);
        rebuildDetailsFromRecords(records, "warmup-all");
    }

    private void rebuildDetails(List<Long> alertIds) {
        for (Long alertId : alertIds) {
            disasterAlertRepository.findById(alertId).ifPresentOrElse(
                r -> {
                    writeDetail(alertId, r, "regeneration");
                },
                () -> {
                    cacheWriter.deleteDisasterDetail(alertId);
                    log.info("disaster:detail:{} DEL: alertId not found in RDS, stale key removed", alertId);
                }
            );
        }
    }

    private void rebuildDetailsFromRecords(List<DisasterAlertRecord> records, String source) {
        for (DisasterAlertRecord record : records) {
            writeDetail(record.alertId(), record, source);
        }
    }

    private void writeDetail(Long alertId, DisasterAlertRecord record, String source) {
        try {
            cacheWriter.setDisasterDetail(alertId, toDetailValue(record));
            workerMetrics.incrementCacheRegenerationCompleted("disaster_detail");
            log.info("disaster:detail:{} SET: source={}", alertId, source);
        } catch (RuntimeException e) {
            workerMetrics.incrementCacheRegenerationFailed("disaster_detail", "redis_write_failure");
            log.warn("disaster:detail:{} write failed: source={}", alertId, source, e);
            throw e;
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

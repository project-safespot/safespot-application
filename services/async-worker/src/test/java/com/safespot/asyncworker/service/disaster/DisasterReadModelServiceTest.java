package com.safespot.asyncworker.service.disaster;

import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import com.safespot.asyncworker.payload.DisasterDataCollectedPayload;
import com.safespot.asyncworker.redis.DisasterDetailCacheValue;
import com.safespot.asyncworker.redis.DisasterMessageItem;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.repository.DisasterAlertRecord;
import com.safespot.asyncworker.repository.DisasterAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;

@ExtendWith(MockitoExtension.class)
class DisasterReadModelServiceTest {

    @Mock private DisasterAlertRepository disasterAlertRepository;
    @Mock private RedisCacheWriter cacheWriter;
    @Mock private WorkerMetrics workerMetrics;

    private DisasterReadModelService service;

    @BeforeEach
    void setUp() {
        service = new DisasterReadModelService(disasterAlertRepository, cacheWriter, workerMetrics);
    }

    private static DisasterAlertRecord sampleRecord(Long alertId) {
        return new DisasterAlertRecord(
                alertId, "FLOOD", "flood", "ALERT",
                "seoul", "WARNING", 3, "flood message", "KMA",
                "2026-04-22T10:00:00", null, true
        );
    }

    @Test
    void rebuild_writes_detail_recent_core_list_in_order() {
        DisasterAlertRecord record = sampleRecord(42L);
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
                "FLOOD", "seoul", List.of(42L), false, "2026-04-22T10:00:00"
        );

        when(disasterAlertRepository.findById(42L)).thenReturn(Optional.of(record));
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(5)).thenReturn(List.of(record));
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(50)).thenReturn(List.of(record));
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.of(record));

        service.rebuild(payload);

        var inOrder = inOrder(cacheWriter);
        inOrder.verify(cacheWriter).setDisasterDetail(eq(42L), any(DisasterDetailCacheValue.class));
        inOrder.verify(cacheWriter).setDisasterMessagesRecent(anyList());
        inOrder.verify(cacheWriter).setDisasterMessageCore(any(DisasterMessageItem.class));
        inOrder.verify(cacheWriter).setDisasterMessagesList(anyList());
        verify(workerMetrics).incrementCacheRegenerationCompleted("disaster_detail");
    }

    @Test
    void affectedAlertIds_each_write_detail() {
        DisasterAlertRecord record = sampleRecord(42L);
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
                "FLOOD", "seoul", List.of(42L), false, "2026-04-22T10:00:00"
        );

        when(disasterAlertRepository.findById(42L)).thenReturn(Optional.of(record));
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.empty());

        service.rebuild(payload);

        verify(cacheWriter).setDisasterDetail(eq(42L), any(DisasterDetailCacheValue.class));
    }

    @Test
    void core_candidate_missing_writes_empty_wrapper() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
                "FLOOD", "seoul", List.of(), false, "2026-04-22T10:00:00"
        );

        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.empty());

        service.rebuild(payload);

        verify(cacheWriter).setDisasterMessageCoreEmpty();
        verify(cacheWriter, never()).setDisasterMessageCore(any());
    }

    @Test
    void recent_and_list_use_top_n_queries() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
                "FLOOD", "seoul", List.of(), false, "2026-04-22T10:00:00"
        );

        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.empty());

        service.rebuild(payload);

        verify(disasterAlertRepository).findInScopeOrderByIssuedAtDesc(5);
        verify(disasterAlertRepository).findInScopeOrderByIssuedAtDesc(50);
    }

    @Test
    void missing_region_throws() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
                "FLOOD", null, List.of(), false, "2026-04-22T10:00:00"
        );

        assertThatThrownBy(() -> service.rebuild(payload))
                .isInstanceOf(EventProcessingException.class);
    }

    @Test
    void missing_alertId_deletes_stale_detail_key() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
                "FLOOD", "seoul", List.of(999L), false, "2026-04-22T10:00:00"
        );
        when(disasterAlertRepository.findById(999L)).thenReturn(Optional.empty());
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.empty());

        service.rebuild(payload);

        verify(cacheWriter).deleteDisasterDetail(999L);
        verify(cacheWriter, never()).setDisasterDetail(eq(999L), any());
    }

    @Test
    void rebuildDetail_missing_alertId_deletes_key() {
        when(disasterAlertRepository.findById(999L)).thenReturn(Optional.empty());

        service.rebuildDetail(999L);

        verify(cacheWriter).deleteDisasterDetail(999L);
        verify(cacheWriter, never()).setDisasterDetail(eq(999L), any());
    }

    @Test
    void warmupAll_includeDetails_true_warms_list_and_details() {
        DisasterAlertRecord record = sampleRecord(42L);
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(10)).thenReturn(List.of(record));
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(5)).thenReturn(List.of(record));
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(50)).thenReturn(List.of(record));
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.of(record));

        service.warmupAll(10, true);

        verify(cacheWriter).setDisasterMessagesList(anyList());
        verify(cacheWriter, times(2)).setDisasterDetail(eq(42L), any(DisasterDetailCacheValue.class));
        verify(cacheWriter).setDisasterMessagesRecent(anyList());
        verify(cacheWriter).setDisasterMessageCore(any(DisasterMessageItem.class));
    }

    @Test
    void warmupAll_includeDetails_false_still_warms_detail_for_list_entries() {
        DisasterAlertRecord record = sampleRecord(42L);
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(5)).thenReturn(List.of(record));
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(50)).thenReturn(List.of(record));
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.of(record));

        service.warmupAll(10, false);

        verify(cacheWriter).setDisasterMessagesList(anyList());
        verify(cacheWriter).setDisasterDetail(eq(42L), any(DisasterDetailCacheValue.class));
        verify(disasterAlertRepository, never()).findInScopeOrderByIssuedAtDesc(10);
    }

    @Test
    void warmupAll_detailWriteFailure_failsWholeWarmup() {
        DisasterAlertRecord record = sampleRecord(42L);
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(50)).thenReturn(List.of(record));
        doThrow(new RedisCacheException("Redis SET failed: key=disaster:detail:42", new RuntimeException()))
                .when(cacheWriter).setDisasterDetail(eq(42L), any(DisasterDetailCacheValue.class));

        assertThatThrownBy(() -> service.warmupAll(10, false))
                .isInstanceOf(RedisCacheException.class)
                .hasMessageContaining("disaster:detail:42");

        verify(workerMetrics).incrementCacheRegenerationFailed("disaster_detail", "redis_write_failure");
    }

    @Test
    void redisSetFailure_propagates() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
                "FLOOD", "seoul", List.of(), false, "2026-04-22T10:00:00"
        );
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        doThrow(new RedisCacheException("Redis SET failed: key=disaster:messages:recent:seoul", new RuntimeException()))
                .when(cacheWriter).setDisasterMessagesRecent(any());

        assertThatThrownBy(() -> service.rebuild(payload))
                .isInstanceOf(RedisCacheException.class)
                .hasMessageContaining("disaster:messages:recent:seoul");
    }
}

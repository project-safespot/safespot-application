package com.safespot.asyncworker.service.disaster;

import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisasterReadModelServiceTest {

    @Mock private DisasterAlertRepository disasterAlertRepository;
    @Mock private RedisCacheWriter cacheWriter;

    private DisasterReadModelService service;

    @BeforeEach
    void setUp() {
        service = new DisasterReadModelService(disasterAlertRepository, cacheWriter);
    }

    private static DisasterAlertRecord sampleRecord(Long alertId) {
        return new DisasterAlertRecord(
            alertId, "FLOOD", "홍수 주의보", "ALERT",
            "서울", "WARNING", 3, "홍수 발생", "KMA",
            "2026-04-22T10:00:00", null, true
        );
    }

    @Test
    void rebuild_호출시_detail_recent_core_list_순서로_재생성() {
        DisasterAlertRecord record = sampleRecord(42L);
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(42L), false, "2026-04-22T10:00:00"
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
    }

    @Test
    void affectedAlertIds_각각_detail_SET() {
        DisasterAlertRecord record = sampleRecord(42L);
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(42L), false, "2026-04-22T10:00:00"
        );

        when(disasterAlertRepository.findById(42L)).thenReturn(Optional.of(record));
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.empty());

        service.rebuild(payload);

        verify(cacheWriter).setDisasterDetail(eq(42L), any(DisasterDetailCacheValue.class));
    }

    @Test
    void core_candidate_없으면_empty_wrapper_SET() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(), false, "2026-04-22T10:00:00"
        );

        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.empty());

        service.rebuild(payload);

        verify(cacheWriter).setDisasterMessageCoreEmpty();
        verify(cacheWriter, never()).setDisasterMessageCore(any());
    }

    @Test
    void recent_list_Top_N_쿼리_각각_호출() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(), false, "2026-04-22T10:00:00"
        );

        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.empty());

        service.rebuild(payload);

        verify(disasterAlertRepository).findInScopeOrderByIssuedAtDesc(5);
        verify(disasterAlertRepository).findInScopeOrderByIssuedAtDesc(50);
    }

    @Test
    void region_누락시_EventProcessingException() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", null, List.of(), false, "2026-04-22T10:00:00"
        );

        assertThatThrownBy(() -> service.rebuild(payload))
            .isInstanceOf(EventProcessingException.class);
    }

    @Test
    void alertId_없음시_detail_key_DEL_stale_제거() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(999L), false, "2026-04-22T10:00:00"
        );
        when(disasterAlertRepository.findById(999L)).thenReturn(Optional.empty());
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        when(disasterAlertRepository.findCoreMessage()).thenReturn(Optional.empty());

        service.rebuild(payload);

        verify(cacheWriter).deleteDisasterDetail(999L);
        verify(cacheWriter, never()).setDisasterDetail(eq(999L), any());
    }

    @Test
    void rebuildDetail_alertId_없음시_detail_key_DEL() {
        when(disasterAlertRepository.findById(999L)).thenReturn(Optional.empty());

        service.rebuildDetail(999L);

        verify(cacheWriter).deleteDisasterDetail(999L);
        verify(cacheWriter, never()).setDisasterDetail(eq(999L), any());
    }

    @Test
    void Redis_SET_실패시_예외_전파() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(), false, "2026-04-22T10:00:00"
        );
        when(disasterAlertRepository.findInScopeOrderByIssuedAtDesc(anyInt())).thenReturn(List.of());
        doThrow(new RedisCacheException("Redis SET failed: key=disaster:messages:recent:seoul", new RuntimeException()))
            .when(cacheWriter).setDisasterMessagesRecent(any());

        assertThatThrownBy(() -> service.rebuild(payload))
            .isInstanceOf(RedisCacheException.class)
            .hasMessageContaining("disaster:messages:recent:seoul");
    }
}

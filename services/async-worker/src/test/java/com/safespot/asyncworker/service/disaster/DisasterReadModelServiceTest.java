package com.safespot.asyncworker.service.disaster;

import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.payload.DisasterDataCollectedPayload;
import com.safespot.asyncworker.redis.DisasterActiveItem;
import com.safespot.asyncworker.redis.DisasterAlertListItem;
import com.safespot.asyncworker.redis.DisasterDetailCacheValue;
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

    @Test
    void hasExpiredAlerts_true이면_DEL_후_SET() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(1L), true, "2026-04-22T10:00:00"
        );
        when(disasterAlertRepository.findActiveByRegion("서울")).thenReturn(List.of());
        when(disasterAlertRepository.findByRegionAndDisasterType("서울", "FLOOD")).thenReturn(List.of());

        service.rebuild(payload);

        var inOrder = inOrder(cacheWriter);
        inOrder.verify(cacheWriter).deleteDisasterActive("서울");
        inOrder.verify(cacheWriter).setDisasterActive(eq("서울"), anyList());
    }

    @Test
    void hasExpiredAlerts_false이면_DEL_없이_SET() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(), false, "2026-04-22T10:00:00"
        );
        when(disasterAlertRepository.findActiveByRegion("서울")).thenReturn(List.of());
        when(disasterAlertRepository.findByRegionAndDisasterType("서울", "FLOOD")).thenReturn(List.of());

        service.rebuild(payload);

        verify(cacheWriter, never()).deleteDisasterActive(any());
        verify(cacheWriter).setDisasterActive(eq("서울"), anyList());
    }

    @Test
    void affectedAlertIds_각각_detail_SET() {
        DisasterAlertRecord record = new DisasterAlertRecord(
            42L, "FLOOD", "서울", "HIGH", "홍수 경보", "KMA",
            "2026-04-22T10:00:00", null
        );
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(42L), false, "2026-04-22T10:00:00"
        );
        when(disasterAlertRepository.findActiveByRegion("서울")).thenReturn(List.of(record));
        when(disasterAlertRepository.findByRegionAndDisasterType("서울", "FLOOD")).thenReturn(List.of(record));
        when(disasterAlertRepository.findById(42L)).thenReturn(Optional.of(record));

        service.rebuild(payload);

        verify(cacheWriter).setDisasterDetail(eq(42L), any(DisasterDetailCacheValue.class));
    }

    @Test
    void Redis_SET_실패시_예외_전파() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(), false, "2026-04-22T10:00:00"
        );
        when(disasterAlertRepository.findActiveByRegion("서울")).thenReturn(List.of());
        doThrow(new RedisCacheException("Redis SET failed: key=disaster:active:서울", new RuntimeException()))
            .when(cacheWriter).setDisasterActive(any(), any());

        assertThatThrownBy(() -> service.rebuild(payload))
            .isInstanceOf(RedisCacheException.class)
            .hasMessageContaining("disaster:active:서울");
    }

    @Test
    void Redis_DEL_실패시_예외_전파() {
        DisasterDataCollectedPayload payload = new DisasterDataCollectedPayload(
            "FLOOD", "서울", List.of(), true, "2026-04-22T10:00:00"
        );
        doThrow(new RedisCacheException("Redis DEL failed: key=disaster:active:서울", new RuntimeException()))
            .when(cacheWriter).deleteDisasterActive("서울");

        assertThatThrownBy(() -> service.rebuild(payload))
            .isInstanceOf(RedisCacheException.class)
            .hasMessageContaining("disaster:active:서울");
    }
}

package com.safespot.asyncworker.service.shelter;

import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.exception.ResourceNotFoundException;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.repository.EvacuationEntryRepository;
import com.safespot.asyncworker.repository.ShelterInfo;
import com.safespot.asyncworker.repository.ShelterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShelterStatusServiceTest {

    @Mock private EvacuationEntryRepository entryRepository;
    @Mock private ShelterRepository shelterRepository;
    @Mock private RedisCacheWriter cacheWriter;

    @InjectMocks private ShelterStatusService service;

    @Test
    void 정상_재계산_50퍼센트_NORMAL() {
        when(shelterRepository.findById(101L))
            .thenReturn(Optional.of(new ShelterInfo(101L, 100, "OPERATING")));
        when(entryRepository.countEntered(101L)).thenReturn(50);

        service.recalculate(101L);

        ArgumentCaptor<ShelterStatusValue> captor = ArgumentCaptor.forClass(ShelterStatusValue.class);
        verify(cacheWriter).setShelterStatus(eq(101L), captor.capture());

        ShelterStatusValue value = captor.getValue();
        assertThat(value.currentOccupancy()).isEqualTo(50);
        assertThat(value.availableCapacity()).isEqualTo(50);
        assertThat(value.congestionLevel()).isEqualTo("NORMAL");
        assertThat(value.shelterStatus()).isEqualTo("OPERATING");
    }

    @Test
    void capacity_null_availableCapacity_0_congestion_AVAILABLE() {
        // V3 migration: shelter.capacity is nullable
        // NULL capacity → availableCapacity=0, congestionLevel=AVAILABLE
        // api-public-read ShelterStatusCache(int availableCapacity) 역직렬화 호환 보장
        when(shelterRepository.findById(101L))
            .thenReturn(Optional.of(new ShelterInfo(101L, null, "OPERATING")));
        when(entryRepository.countEntered(101L)).thenReturn(5);

        service.recalculate(101L);

        ArgumentCaptor<ShelterStatusValue> captor = ArgumentCaptor.forClass(ShelterStatusValue.class);
        verify(cacheWriter).setShelterStatus(eq(101L), captor.capture());

        ShelterStatusValue value = captor.getValue();
        assertThat(value.currentOccupancy()).isEqualTo(5);
        assertThat(value.availableCapacity()).isZero();
        assertThat(value.congestionLevel()).isEqualTo("AVAILABLE");
        assertThat(value.shelterStatus()).isEqualTo("OPERATING");
    }

    @Test
    void availableCapacity_음수_방지() {
        when(shelterRepository.findById(101L))
            .thenReturn(Optional.of(new ShelterInfo(101L, 10, "OPERATING")));
        when(entryRepository.countEntered(101L)).thenReturn(15);

        service.recalculate(101L);

        ArgumentCaptor<ShelterStatusValue> captor = ArgumentCaptor.forClass(ShelterStatusValue.class);
        verify(cacheWriter).setShelterStatus(eq(101L), captor.capture());
        assertThat(captor.getValue().availableCapacity()).isZero();
    }

    @Test
    void warmUpAll_전체_대피소_상태를_배치_조회해_캐시에_쓴다() {
        List<ShelterInfo> shelters = List.of(
            new ShelterInfo(101L, 100, "OPERATING"),
            new ShelterInfo(102L, 50, "OPERATING"),
            new ShelterInfo(103L, null, "OPERATING")
        );
        when(shelterRepository.findAllForStatusWarmup()).thenReturn(shelters);
        when(entryRepository.countEnteredByShelterIds(List.of(101L, 102L, 103L)))
            .thenReturn(Map.of(101L, 40, 102L, 50));

        int count = service.warmUpAll();

        assertThat(count).isEqualTo(3);
        verify(entryRepository).countEnteredByShelterIds(List.of(101L, 102L, 103L));

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<ShelterStatusValue> valueCaptor = ArgumentCaptor.forClass(ShelterStatusValue.class);
        verify(cacheWriter, times(3)).setShelterStatus(idCaptor.capture(), valueCaptor.capture());

        assertThat(idCaptor.getAllValues()).containsExactly(101L, 102L, 103L);
        assertThat(valueCaptor.getAllValues())
            .extracting(ShelterStatusValue::currentOccupancy)
            .containsExactly(40, 50, 0);
        assertThat(valueCaptor.getAllValues())
            .extracting(ShelterStatusValue::availableCapacity)
            .containsExactly(60, 0, 0);
        assertThat(valueCaptor.getAllValues())
            .extracting(ShelterStatusValue::congestionLevel)
            .containsExactly("NORMAL", "FULL", "AVAILABLE");
    }

    @Test
    void warmUpAll_대피소가_없으면_0을_반환하고_캐시에_쓰지_않는다() {
        when(shelterRepository.findAllForStatusWarmup()).thenReturn(List.of());

        int count = service.warmUpAll();

        assertThat(count).isZero();
        verifyNoInteractions(entryRepository);
        verifyNoInteractions(cacheWriter);
    }

    @Test
    void 대피소_미존재시_ResourceNotFoundException() {
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recalculate(999L))
            .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(cacheWriter);
    }

    @Test
    void Redis_실패시_예외_전파() {
        when(shelterRepository.findById(101L))
            .thenReturn(Optional.of(new ShelterInfo(101L, 100, "OPERATING")));
        when(entryRepository.countEntered(101L)).thenReturn(30);
        doThrow(new RedisCacheException("Redis SET failed", new RuntimeException()))
            .when(cacheWriter).setShelterStatus(any(), any());

        assertThatThrownBy(() -> service.recalculate(101L))
            .isInstanceOf(RedisCacheException.class);
    }
}

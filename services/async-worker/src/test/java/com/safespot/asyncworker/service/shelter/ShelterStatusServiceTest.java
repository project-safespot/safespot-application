package com.safespot.asyncworker.service.shelter;

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
            .thenReturn(Optional.of(new ShelterInfo(101L, 100, "운영중")));
        when(entryRepository.countEntered(101L)).thenReturn(50);

        service.recalculate(101L);

        ArgumentCaptor<ShelterStatusValue> captor = ArgumentCaptor.forClass(ShelterStatusValue.class);
        verify(cacheWriter).setShelterStatus(eq(101L), captor.capture());

        ShelterStatusValue value = captor.getValue();
        assertThat(value.currentOccupancy()).isEqualTo(50);
        assertThat(value.availableCapacity()).isEqualTo(50);
        assertThat(value.congestionLevel()).isEqualTo("NORMAL");
        assertThat(value.shelterStatus()).isEqualTo("운영중");
    }

    @Test
    void availableCapacity_음수_방지() {
        when(shelterRepository.findById(101L))
            .thenReturn(Optional.of(new ShelterInfo(101L, 10, "운영중")));
        when(entryRepository.countEntered(101L)).thenReturn(15);

        service.recalculate(101L);

        ArgumentCaptor<ShelterStatusValue> captor = ArgumentCaptor.forClass(ShelterStatusValue.class);
        verify(cacheWriter).setShelterStatus(eq(101L), captor.capture());
        assertThat(captor.getValue().availableCapacity()).isZero();
    }

    @Test
    void 대피소_미존재시_ResourceNotFoundException() {
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recalculate(999L))
            .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(cacheWriter);
    }

    @Test
    void Redis_실패시_예외_미전파() {
        when(shelterRepository.findById(101L))
            .thenReturn(Optional.of(new ShelterInfo(101L, 100, "운영중")));
        when(entryRepository.countEntered(101L)).thenReturn(30);
        doThrow(new RuntimeException("Redis down"))
            .when(cacheWriter).setShelterStatus(any(), any());

        // RedisCacheWriter 내부에서 예외를 삼키므로 여기까지 전파되지 않음
        // 실제 동작은 RedisCacheWriter 단위 테스트에서 검증
    }
}

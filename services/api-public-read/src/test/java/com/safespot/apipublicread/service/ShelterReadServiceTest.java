package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.Shelter;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterStatusCache;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.repository.EvacuationEntryRepository;
import com.safespot.apipublicread.repository.ShelterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShelterReadServiceTest {

    @Mock ShelterRepository shelterRepository;
    @Mock EvacuationEntryRepository evacuationEntryRepository;
    @Mock RedisReadCache redisReadCache;
    @Mock SuppressWindowService suppressWindowService;
    @Mock CacheRegenerationPublisher cacheRegenerationPublisher;

    @InjectMocks ShelterReadService shelterReadService;

    private Shelter shelter;

    @BeforeEach
    void setUp() {
        shelter = mock(Shelter.class);
        lenient().when(shelter.getShelterId()).thenReturn(101L);
        lenient().when(shelter.getName()).thenReturn("서울시민체육관");
        lenient().when(shelter.getShelterType()).thenReturn("민방위대피소");
        lenient().when(shelter.getDisasterType()).thenReturn("EARTHQUAKE");
        lenient().when(shelter.getAddress()).thenReturn("서울특별시 마포구");
        lenient().when(shelter.getLatitude()).thenReturn(BigDecimal.valueOf(37.5687));
        lenient().when(shelter.getLongitude()).thenReturn(BigDecimal.valueOf(126.9081));
        lenient().when(shelter.getCapacity()).thenReturn(120);
        lenient().when(shelter.getUpdatedAt()).thenReturn(OffsetDateTime.now());
    }

    @Test
    void findById_cacheHit_returnsFromCache() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        ShelterStatusCache cachedStatus = new ShelterStatusCache(68, 52, "NORMAL", "운영중", "2026-04-15T09:00:00+09:00");
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cachedStatus, null));

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.shelterId()).isEqualTo(101L);
        assertThat(result.currentOccupancy()).isEqualTo(68);
        assertThat(result.congestionLevel()).isEqualTo("NORMAL");
        verify(evacuationEntryRepository, never()).countCurrentOccupancy(anyLong());
    }

    @Test
    void findById_cacheMiss_fallsBackToRdsAndEmitsEvent() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(68L);
        when(suppressWindowService.tryPublish("shelter:status:101")).thenReturn(true);

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.currentOccupancy()).isEqualTo(68);
        verify(redisReadCache).recordFallback(eq("/shelters/{shelterId}"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(redisReadCache).recordDbFallbackQuery("/shelters/{shelterId}");
        verify(cacheRegenerationPublisher).publish("shelter:status:101");
    }

    @Test
    void findById_redisDown_fallsBackToRdsAndEmitsEvent() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_DOWN));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(30L);
        when(suppressWindowService.tryPublish("shelter:status:101")).thenReturn(true);

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.currentOccupancy()).isEqualTo(30);
        verify(redisReadCache).recordFallback(eq("/shelters/{shelterId}"), eq(RedisReadCache.FallbackReason.REDIS_DOWN));
    }

    @Test
    void findById_suppressWindow_doesNotEmitSecondTime() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(10L);
        when(suppressWindowService.tryPublish("shelter:status:101")).thenReturn(false);

        shelterReadService.findById(101L);

        verify(cacheRegenerationPublisher, never()).publish(anyString());
    }

    @Test
    void findById_notFound_throwsApiException() {
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());
        // shelter BeforeEach stubs are lenient for this test case
        assertThatThrownBy(() -> shelterReadService.findById(999L))
                .isInstanceOf(ApiException.class);
    }
}

package com.safespot.asyncworker.service.shelter;

import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.redis.ShelterMapItemValue;
import com.safespot.asyncworker.repository.ShelterMapSource;
import com.safespot.asyncworker.repository.ShelterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShelterMapReadModelServiceTest {

    @Mock private ShelterRepository shelterRepository;
    @Mock private RedisCacheWriter cacheWriter;

    @InjectMocks private ShelterMapReadModelService service;

    @Test
    void rebuildMapItems_targetIds를_dedupe하고_mapItem을_쓴다() {
        when(shelterRepository.findByIdsForMapItems(List.of(101L, 202L))).thenReturn(List.of(
            source(101L, "지정대피소", "FLOOD", 37.55, 126.98),
            source(202L, "임시대피소", "FLOOD", 37.56, 126.99)
        ));

        service.rebuildMapItems(List.of(101L, 202L, 101L));

        verify(shelterRepository).findByIdsForMapItems(List.of(101L, 202L));
        verify(cacheWriter, times(2)).setShelterMapItem(anyLong(), any(ShelterMapItemValue.class));
    }

    @Test
    void rebuildGeoIndex_all_disaster_shelter_dimension에_GEOADD한다() {
        when(shelterRepository.findAllForMapReadModel()).thenReturn(List.of(
            source(101L, "지정대피소", "FLOOD", 37.55, 126.98)
        ));

        service.rebuildGeoIndex();

        verify(cacheWriter).deleteKeys(argThat(keys -> keys.size() == 16 && keys.contains("shelter:geo:seoul:all:all")));
        verify(cacheWriter).geoAddShelter("all", "all", 126.98, 37.55, 101L);
        verify(cacheWriter).geoAddShelter("FLOOD", "all", 126.98, 37.55, 101L);
        verify(cacheWriter).geoAddShelter("all", "DESIGNATED", 126.98, 37.55, 101L);
        verify(cacheWriter).geoAddShelter("FLOOD", "DESIGNATED", 126.98, 37.55, 101L);
    }

    @Test
    void rebuildMapTiles_zoom11부터16까지_tile_key를_쓴다() {
        when(shelterRepository.findAllForMapReadModel()).thenReturn(List.of(
            source(101L, "지정대피소", "FLOOD", 37.5665, 126.9780),
            source(202L, "지정대피소", "FLOOD", 37.5665, 126.9780)
        ));

        service.rebuildMapTiles();

        verify(cacheWriter).deleteByPattern("shelter:map:tile:*");
        verify(cacheWriter, atLeast(24)).setShelterMapTile(anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(cacheWriter, atLeastOnce()).setShelterMapTile(eq(11), anyInt(), anyInt(), eq("FLOOD"), eq("DESIGNATED"), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(101L, 202L);
    }

    private ShelterMapSource source(Long shelterId, String shelterType, String disasterType, double latitude, double longitude) {
        return new ShelterMapSource(
            shelterId,
            "대피소-" + shelterId,
            shelterType,
            disasterType,
            "서울시 어딘가",
            BigDecimal.valueOf(latitude),
            BigDecimal.valueOf(longitude),
            OffsetDateTime.parse("2026-05-15T10:00:00Z")
        );
    }
}

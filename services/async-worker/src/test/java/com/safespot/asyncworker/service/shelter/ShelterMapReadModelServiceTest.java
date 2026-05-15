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
            source(101L, "지정대피소", "FLOOD", 120, 37.55, 126.98),
            source(202L, "임시대피소", "FLOOD", 80, 37.56, 126.99)
        ));

        service.rebuildMapItems(List.of(101L, 202L, 101L));

        verify(shelterRepository).findByIdsForMapItems(List.of(101L, 202L));
        ArgumentCaptor<ShelterMapItemValue> valueCaptor = ArgumentCaptor.forClass(ShelterMapItemValue.class);
        verify(cacheWriter, times(2)).setShelterMapItem(anyLong(), valueCaptor.capture());
        assertThat(valueCaptor.getAllValues())
            .extracting(ShelterMapItemValue::capacityTotal)
            .containsExactly(120, 80);
    }

    @Test
    void rebuildGeoIndex_all_disaster_shelter_dimension에_GEOADD한다() {
        when(shelterRepository.findAllForMapReadModel()).thenReturn(List.of(
            source(101L, "지정대피소", "FLOOD", 120, 37.55, 126.98)
        ));

        service.rebuildGeoIndex();

        verify(cacheWriter).deleteKeys(argThat(keys -> keys.size() == 16 && keys.stream().allMatch(key -> key.startsWith("shelter:geo:tmp:"))));
        verify(cacheWriter).geoAddShelterToKey(matches("shelter:geo:tmp:.*:seoul:all:all"), eq(126.98), eq(37.55), eq(101L));
        verify(cacheWriter).geoAddShelterToKey(matches("shelter:geo:tmp:.*:seoul:FLOOD:all"), eq(126.98), eq(37.55), eq(101L));
        verify(cacheWriter).geoAddShelterToKey(matches("shelter:geo:tmp:.*:seoul:all:DESIGNATED"), eq(126.98), eq(37.55), eq(101L));
        verify(cacheWriter).geoAddShelterToKey(matches("shelter:geo:tmp:.*:seoul:FLOOD:DESIGNATED"), eq(126.98), eq(37.55), eq(101L));
        verify(cacheWriter).deleteKeys(argThat(keys -> keys.size() == 12 && keys.stream().allMatch(key -> key.startsWith("shelter:geo:seoul:"))));
        verify(cacheWriter, times(4)).renameKey(matches("shelter:geo:tmp:.*"), startsWith("shelter:geo:seoul:"));
    }

    @Test
    void rebuildMapTiles_zoom11부터16까지_tile_key를_쓴다() {
        when(shelterRepository.findAllForMapReadModel()).thenReturn(List.of(
            source(101L, "지정대피소", "FLOOD", 120, 37.5665, 126.9780),
            source(202L, "지정대피소", "FLOOD", 120, 37.5665, 126.9780)
        ));

        service.rebuildMapTiles();

        verify(cacheWriter).deleteByPattern("shelter:map:tile:*");
        verify(cacheWriter, atLeast(24)).setShelterMapTileToKey(matches("shelter:map:tmp:tile:.*"), anyList());
        verify(cacheWriter, atLeast(24)).renameKey(matches("shelter:map:tmp:tile:.*"), matches("shelter:map:tile:.*"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(cacheWriter, atLeastOnce()).setShelterMapTileToKey(matches("shelter:map:tmp:tile:.*:11:.*:.*:FLOOD:DESIGNATED"), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(101L, 202L);
    }

    @Test
    void unsupported_shelter_type_EARTHQUAKE는_mapItem_write에서_제외된다() {
        when(shelterRepository.findByIdsForMapItems(List.of(101L))).thenReturn(List.of(
            source(101L, "EARTHQUAKE", "EARTHQUAKE", 120, 37.55, 126.98)
        ));

        service.rebuildMapItems(List.of(101L));

        verify(cacheWriter, never()).setShelterMapItem(anyLong(), any());
    }

    @Test
    void unsupported_shelter_type_EARTHQUAKE는_geo_tile_write에서_제외된다() {
        when(shelterRepository.findAllForMapReadModel()).thenReturn(List.of(
            source(101L, "EARTHQUAKE", "EARTHQUAKE", 120, 37.55, 126.98)
        ));

        service.rebuildGeoIndex();
        service.rebuildMapTiles();

        verify(cacheWriter, never()).geoAddShelterToKey(anyString(), anyDouble(), anyDouble(), anyLong());
        verify(cacheWriter, never()).setShelterMapTileToKey(anyString(), anyList());
    }

    @Test
    void rebuildMapItems_null_or_negative_capacity는_0으로_보정한다() {
        when(shelterRepository.findByIdsForMapItems(List.of(101L, 202L))).thenReturn(List.of(
            source(101L, "지정대피소", "FLOOD", null, 37.55, 126.98),
            source(202L, "임시대피소", "FLOOD", -10, 37.56, 126.99)
        ));

        service.rebuildMapItems(List.of(101L, 202L));

        ArgumentCaptor<ShelterMapItemValue> valueCaptor = ArgumentCaptor.forClass(ShelterMapItemValue.class);
        verify(cacheWriter, times(2)).setShelterMapItem(anyLong(), valueCaptor.capture());
        assertThat(valueCaptor.getAllValues())
            .extracting(ShelterMapItemValue::capacityTotal)
            .containsExactly(0, 0);
    }

    private ShelterMapSource source(Long shelterId, String shelterType, String disasterType, Integer capacityTotal, double latitude, double longitude) {
        return new ShelterMapSource(
            shelterId,
            "대피소-" + shelterId,
            shelterType,
            disasterType,
            "서울시 어딘가",
            capacityTotal,
            BigDecimal.valueOf(latitude),
            BigDecimal.valueOf(longitude),
            OffsetDateTime.parse("2026-05-15T10:00:00Z")
        );
    }
}

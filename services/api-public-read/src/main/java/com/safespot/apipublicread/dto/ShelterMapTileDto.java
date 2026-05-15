package com.safespot.apipublicread.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.safespot.apipublicread.dto.cache.ShelterMapItemCacheDto;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShelterMapTileDto(
    int z,
    int x,
    int y,
    List<ShelterMapItemCacheDto> items,
    Boolean degraded
) {}

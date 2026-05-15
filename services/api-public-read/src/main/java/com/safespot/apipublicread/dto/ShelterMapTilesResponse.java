package com.safespot.apipublicread.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShelterMapTilesResponse(
    List<ShelterMapTileDto> tiles,
    Boolean degraded
) {}

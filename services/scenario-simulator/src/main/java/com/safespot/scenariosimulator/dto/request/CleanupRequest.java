package com.safespot.scenariosimulator.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CleanupRequest {

    @NotBlank
    private String scenarioId;
}

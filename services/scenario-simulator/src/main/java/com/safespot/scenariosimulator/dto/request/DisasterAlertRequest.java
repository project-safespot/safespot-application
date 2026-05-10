package com.safespot.scenariosimulator.dto.request;

import com.safespot.scenariosimulator.domain.enums.DisasterLevel;
import com.safespot.scenariosimulator.domain.enums.DisasterType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisasterAlertRequest {

    @NotNull
    private DisasterType disasterType;

    @NotNull
    private String region;

    @NotNull
    private DisasterLevel level;

    @Min(1) @Max(100)
    private int count = 1;

    @Min(0)
    private int intervalSeconds = 0;

    private boolean publishEvents = true;

    private boolean triggerProactiveScale = false;

    private String scenarioName;
}

package com.safespot.scenariosimulator.dto.request;

import com.safespot.scenariosimulator.domain.enums.DisasterLevel;
import com.safespot.scenariosimulator.domain.enums.DisasterType;
import com.safespot.scenariosimulator.domain.enums.ResidentDistribution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ScenarioRunRequest {

    @NotBlank
    private String scenarioName;

    @NotNull
    private DisasterSpec disaster;

    private ResidentSpec residents;

    private CacheSpec cache;

    private ScaleSpec scale;

    @Getter
    @NoArgsConstructor
    public static class DisasterSpec {
        @NotNull
        private DisasterType disasterType;
        @NotNull
        private String region;
        @NotNull
        private DisasterLevel level;
    }

    @Getter
    @NoArgsConstructor
    public static class ResidentSpec {
        private int count = 100;
        private int durationSeconds = 0;
        private ResidentDistribution distribution = ResidentDistribution.RANDOM;
    }

    @Getter
    @NoArgsConstructor
    public static class CacheSpec {
        private boolean triggerRegeneration = true;
    }

    @Getter
    @NoArgsConstructor
    public static class ScaleSpec {
        private boolean triggerProactiveScale = false;
        private int requestedReplicas = 5;
    }
}

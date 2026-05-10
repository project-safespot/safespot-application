package com.safespot.scenariosimulator.dto.request;

import com.safespot.scenariosimulator.domain.enums.DisasterType;
import com.safespot.scenariosimulator.domain.enums.ResidentDistribution;
import com.safespot.scenariosimulator.domain.enums.ShelterSelection;
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
public class ResidentBulkRequest {

    @NotNull
    private DisasterType disasterType;

    @NotNull
    private String region;

    @Min(1) @Max(10000)
    private int residentCount = 100;

    private ShelterSelection shelterSelection = ShelterSelection.ALL;

    private ResidentDistribution distribution = ResidentDistribution.RANDOM;

    private String entryStatus = "ENTERED";

    private boolean publishEvents = true;

    private String scenarioId;
}

package com.safespot.scenariosimulator.service;

import com.safespot.scenariosimulator.domain.entity.SimShelter;
import com.safespot.scenariosimulator.domain.enums.ResidentDistribution;
import com.safespot.scenariosimulator.event.SimulatorEventPublisher;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import com.safespot.scenariosimulator.repository.SimEvacuationEntryRepository;
import com.safespot.scenariosimulator.repository.SimShelterRepository;
import com.safespot.scenariosimulator.repository.TestScenarioRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ResidentDistributionServiceTest {

    @Mock SimShelterRepository shelterRepository;
    @Mock SimEvacuationEntryRepository evacuationEntryRepository;
    @Mock TestScenarioRecordRepository scenarioRecordRepository;
    @Mock SimulatorEventPublisher eventPublisher;
    @Mock SimulatorMetrics metrics;

    ResidentBulkSimulatorService service;

    @BeforeEach
    void setUp() {
        service = new ResidentBulkSimulatorService(
                shelterRepository, evacuationEntryRepository,
                scenarioRecordRepository, eventPublisher, metrics);
    }

    private List<SimShelter> shelters(int... capacities) {
        return Arrays.stream(capacities)
                .mapToObj(cap -> {
                    SimShelter s = new SimShelter();
                    setCapacity(s, cap);
                    return s;
                })
                .toList();
    }

    private void setCapacity(SimShelter shelter, int cap) {
        try {
            var field = SimShelter.class.getDeclaredField("capacity");
            field.setAccessible(true);
            field.set(shelter, cap);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void random_distribution_assigns_all_residents() {
        List<SimShelter> shelters = shelters(100, 200, 300);
        int[] result = service.distribute(shelters, 60, ResidentDistribution.RANDOM);

        int total = 0;
        for (int v : result) total += v;
        assertThat(total).isEqualTo(60);
    }

    @Test
    void weighted_by_capacity_assigns_proportional_residents() {
        List<SimShelter> shelters = shelters(100, 300);
        int[] result = service.distribute(shelters, 400, ResidentDistribution.WEIGHTED_BY_CAPACITY);

        int total = 0;
        for (int v : result) total += v;
        assertThat(total).isEqualTo(400);
        // shelter[0]=100cap → ~100, shelter[1]=300cap → ~300
        assertThat(result[0]).isCloseTo(100, org.assertj.core.data.Offset.offset(5));
        assertThat(result[1]).isCloseTo(300, org.assertj.core.data.Offset.offset(5));
    }

    @Test
    void hotspot_distribution_concentrates_residents_in_first_shelters() {
        List<SimShelter> shelters = shelters(100, 100, 100, 100, 100);
        int total = 1000;
        int[] result = service.distribute(shelters, total, ResidentDistribution.HOTSPOT);

        int sum = 0;
        for (int v : result) sum += v;
        assertThat(sum).isEqualTo(total);

        // hotspot shelters (first 20% = 1 shelter) should have >= 80% of residents
        assertThat(result[0]).isGreaterThanOrEqualTo((int) (total * 0.75));
    }

    @Test
    void weighted_by_capacity_with_single_shelter_assigns_all() {
        List<SimShelter> shelters = shelters(500);
        int[] result = service.distribute(shelters, 250, ResidentDistribution.WEIGHTED_BY_CAPACITY);

        assertThat(result[0]).isEqualTo(250);
    }

    @Test
    void random_distribution_with_single_shelter_assigns_all() {
        List<SimShelter> shelters = shelters(100);
        int[] result = service.distribute(shelters, 50, ResidentDistribution.RANDOM);

        assertThat(result[0]).isEqualTo(50);
    }
}

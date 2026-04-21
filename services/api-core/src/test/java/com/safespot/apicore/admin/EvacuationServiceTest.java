package com.safespot.apicore.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.admin.dto.CreateEntryRequest;
import com.safespot.apicore.admin.dto.CreateEntryResponse;
import com.safespot.apicore.admin.dto.ExitEntryRequest;
import com.safespot.apicore.admin.dto.ExitEntryResponse;
import com.safespot.apicore.admin.service.EvacuationService;
import com.safespot.apicore.common.exception.ApiException;
import com.safespot.apicore.domain.entity.*;
import com.safespot.apicore.domain.enums.EntryStatus;
import com.safespot.apicore.domain.enums.EventHistoryType;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvacuationServiceTest {

    @Mock EvacuationEntryRepository entryRepository;
    @Mock EntryDetailRepository entryDetailRepository;
    @Mock ShelterRepository shelterRepository;
    @Mock DisasterAlertRepository disasterAlertRepository;
    @Mock EvacuationEventHistoryRepository historyRepository;
    @Mock AdminAuditLogRepository auditLogRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ApiCoreMetrics metrics;

    @InjectMocks
    private EvacuationService evacuationService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    @BeforeEach
    void injectObjectMapper() throws Exception {
        var field = EvacuationService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(evacuationService, objectMapper);
    }

    private Shelter shelterWithCapacity(int capacity) {
        return Shelter.builder()
                .shelterId(101L).name("테스트대피소").shelterType("민방위대피소")
                .address("서울").capacity(capacity)
                .latitude(java.math.BigDecimal.valueOf(37.5))
                .longitude(java.math.BigDecimal.valueOf(126.9))
                .build();
    }

    private CreateEntryRequest buildCreateRequest(Long shelterId) {
        CreateEntryRequest req = new CreateEntryRequest();
        setField(req, "shelterId", shelterId);
        setField(req, "name", "홍길동");
        return req;
    }

    @Test
    void createEntry_success() {
        Shelter shelter = shelterWithCapacity(10);
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(entryRepository.countByShelterIdAndEntryStatus(101L, EntryStatus.ENTERED)).thenReturn(5L);

        EvacuationEntry savedEntry = EvacuationEntry.builder()
                .entryId(301L).shelterId(101L).entryStatus(EntryStatus.ENTERED)
                .enteredAt(OffsetDateTime.now()).build();
        when(entryRepository.save(any())).thenReturn(savedEntry);
        when(entryDetailRepository.save(any())).thenReturn(mock(EntryDetail.class));
        when(historyRepository.save(any())).thenReturn(mock(EvacuationEventHistory.class));
        when(auditLogRepository.save(any())).thenReturn(mock(AdminAuditLog.class));

        CreateEntryRequest req = buildCreateRequest(101L);
        CreateEntryResponse response = evacuationService.createEntry(req, 7L, "127.0.0.1");

        assertThat(response.getEntryId()).isEqualTo(301L);
        assertThat(response.getEntryStatus()).isEqualTo("ENTERED");
        verify(eventPublisher).publishEvent(any());
        verify(metrics).incCheckin();
    }

    @Test
    void createEntry_shelterFull_throws409() {
        Shelter shelter = shelterWithCapacity(5);
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(entryRepository.countByShelterIdAndEntryStatus(101L, EntryStatus.ENTERED)).thenReturn(5L);

        CreateEntryRequest req = buildCreateRequest(101L);

        assertThatThrownBy(() -> evacuationService.createEntry(req, 7L, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHELTER_FULL"));

        verify(metrics).incCheckinFailed("SHELTER_FULL");
    }

    @Test
    void createEntry_shelterNotFound_throws404() {
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());

        CreateEntryRequest req = buildCreateRequest(999L);

        assertThatThrownBy(() -> evacuationService.createEntry(req, 7L, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void exitEntry_success() {
        EvacuationEntry entry = EvacuationEntry.builder()
                .entryId(301L).shelterId(101L).entryStatus(EntryStatus.ENTERED)
                .enteredAt(OffsetDateTime.now()).build();
        when(entryRepository.findById(301L)).thenReturn(Optional.of(entry));
        when(entryRepository.save(any())).thenReturn(entry);
        when(historyRepository.save(any())).thenReturn(mock(EvacuationEventHistory.class));
        when(auditLogRepository.save(any())).thenReturn(mock(AdminAuditLog.class));

        ExitEntryRequest req = new ExitEntryRequest();
        setField(req, "reason", "자택 복귀");

        ExitEntryResponse response = evacuationService.exitEntry(301L, req, 7L, "127.0.0.1");

        assertThat(response.getEntryStatus()).isEqualTo("EXITED");
        assertThat(response.getExitedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any());
        verify(metrics).incCheckout();
    }

    @Test
    void exitEntry_alreadyExited_throws409() {
        EvacuationEntry entry = EvacuationEntry.builder()
                .entryId(301L).shelterId(101L).entryStatus(EntryStatus.EXITED)
                .enteredAt(OffsetDateTime.now()).build();
        when(entryRepository.findById(301L)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> evacuationService.exitEntry(301L, null, 7L, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ALREADY_EXITED"));
    }

    @Test
    void listEntries_shelterNotFound_throws404() {
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> evacuationService.listEntries(999L, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("NOT_FOUND"));
    }

    private void setField(Object obj, String fieldName, Object value) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

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
import com.safespot.apicore.domain.enums.HealthStatus;
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

    private EvacuationEntry savedEntry(Long entryId) {
        return EvacuationEntry.builder()
                .entryId(entryId).shelterId(101L).entryStatus(EntryStatus.ENTERED)
                .enteredAt(OffsetDateTime.now()).build();
    }

    @Test
    void createEntry_success() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelterWithCapacity(10)));
        when(entryRepository.save(any())).thenReturn(savedEntry(301L));
        when(entryDetailRepository.save(any())).thenReturn(mock(EntryDetail.class));
        when(historyRepository.save(any())).thenReturn(mock(EvacuationEventHistory.class));
        when(auditLogRepository.save(any())).thenReturn(mock(AdminAuditLog.class));

        CreateEntryResponse response = evacuationService.createEntry(buildCreateRequest(101L), 7L, "127.0.0.1");

        assertThat(response.getEntryId()).isEqualTo(301L);
        assertThat(response.getEntryStatus()).isEqualTo("ENTERED");
        verify(eventPublisher).publishEvent(any());
        verify(metrics).incCheckin();
    }

    @Test
    void createEntry_overCapacity_allowsEntry() {
        // 재난 상황에서는 정원 초과 시에도 입소 허용 — capacity는 혼잡도 기준치로만 사용
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelterWithCapacity(1)));
        when(entryRepository.save(any())).thenReturn(savedEntry(301L));
        when(entryDetailRepository.save(any())).thenReturn(mock(EntryDetail.class));
        when(historyRepository.save(any())).thenReturn(mock(EvacuationEventHistory.class));
        when(auditLogRepository.save(any())).thenReturn(mock(AdminAuditLog.class));

        CreateEntryResponse response = evacuationService.createEntry(buildCreateRequest(101L), 7L, "127.0.0.1");

        assertThat(response.getEntryStatus()).isEqualTo("ENTERED");
        verify(metrics).incCheckin();
    }

    @Test
    void createEntry_shelterNotFound_throws404() {
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> evacuationService.createEntry(buildCreateRequest(999L), 7L, "127.0.0.1"))
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

    @Test
    void createEntry_invalidHealthStatus_throws400() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelterWithCapacity(10)));

        CreateEntryRequest req = buildCreateRequest(101L);
        setField(req, "healthStatus", "당뇨");

        assertThatThrownBy(() -> evacuationService.createEntry(req, 7L, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void createEntry_validHealthStatus_succeeds() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelterWithCapacity(10)));
        when(entryRepository.save(any())).thenReturn(savedEntry(302L));
        when(entryDetailRepository.save(any())).thenReturn(mock(EntryDetail.class));
        when(historyRepository.save(any())).thenReturn(mock(EvacuationEventHistory.class));
        when(auditLogRepository.save(any())).thenReturn(mock(AdminAuditLog.class));

        CreateEntryRequest req = buildCreateRequest(101L);
        setField(req, "healthStatus", "부상");

        assertThat(evacuationService.createEntry(req, 7L, "127.0.0.1").getEntryStatus()).isEqualTo("ENTERED");
    }

    @Test
    void updateEntry_invalidHealthStatus_throws400() {
        EvacuationEntry entry = EvacuationEntry.builder()
                .entryId(301L).shelterId(101L).entryStatus(EntryStatus.ENTERED)
                .enteredAt(OffsetDateTime.now()).build();
        when(entryRepository.findById(301L)).thenReturn(Optional.of(entry));
        when(entryDetailRepository.findByEntryId(301L)).thenReturn(Optional.of(
                EntryDetail.builder()
                        .detailId(1L).entryId(301L)
                        .healthStatus(HealthStatus.정상)
                        .specialProtectionFlag(false)
                        .createdAt(OffsetDateTime.now())
                        .updatedAt(OffsetDateTime.now())
                        .build()));

        com.safespot.apicore.admin.dto.UpdateEntryRequest req =
                new com.safespot.apicore.admin.dto.UpdateEntryRequest();
        setField(req, "healthStatus", "invalid_value");

        assertThatThrownBy(() -> evacuationService.updateEntry(301L, req, 7L, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void updateEntry_omitHealthStatus_preservesExisting() {
        EvacuationEntry entry = EvacuationEntry.builder()
                .entryId(301L).shelterId(101L).entryStatus(EntryStatus.ENTERED)
                .enteredAt(OffsetDateTime.now()).build();
        EntryDetail detail = EntryDetail.builder()
                .detailId(1L).entryId(301L)
                .healthStatus(HealthStatus.응급)
                .specialProtectionFlag(false)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        when(entryRepository.findById(301L)).thenReturn(Optional.of(entry));
        when(entryRepository.save(any())).thenReturn(entry);
        when(entryDetailRepository.findByEntryId(301L)).thenReturn(Optional.of(detail));
        when(entryDetailRepository.save(any())).thenReturn(detail);
        when(historyRepository.save(any())).thenReturn(mock(EvacuationEventHistory.class));
        when(auditLogRepository.save(any())).thenReturn(mock(AdminAuditLog.class));

        com.safespot.apicore.admin.dto.UpdateEntryRequest req =
                new com.safespot.apicore.admin.dto.UpdateEntryRequest();
        // healthStatus 미포함 → 기존값 유지

        evacuationService.updateEntry(301L, req, 7L, "127.0.0.1");

        assertThat(detail.getHealthStatus()).isEqualTo(HealthStatus.응급);
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

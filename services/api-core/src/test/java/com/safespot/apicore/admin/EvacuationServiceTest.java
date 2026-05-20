package com.safespot.apicore.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.admin.dto.CreateEntryRequest;
import com.safespot.apicore.admin.dto.CreateEntryResponse;
import com.safespot.apicore.admin.dto.EvacuationEntryPageResponse;
import com.safespot.apicore.admin.dto.ExitEntryRequest;
import com.safespot.apicore.admin.dto.ExitEntryResponse;
import com.safespot.apicore.admin.dto.UpdateEntryRequest;
import com.safespot.apicore.admin.service.EvacuationService;
import com.safespot.apicore.common.exception.ApiException;
import com.safespot.apicore.domain.entity.AdminAuditLog;
import com.safespot.apicore.domain.entity.EntryDetail;
import com.safespot.apicore.domain.entity.EvacuationEntry;
import com.safespot.apicore.domain.entity.EvacuationEventHistory;
import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.DisasterType;
import com.safespot.apicore.domain.enums.EntryStatus;
import com.safespot.apicore.domain.enums.HealthStatus;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.repository.AdminAuditLogRepository;
import com.safespot.apicore.repository.DisasterAlertRepository;
import com.safespot.apicore.repository.EntryDetailRepository;
import com.safespot.apicore.repository.EvacuationEntryRepository;
import com.safespot.apicore.repository.EvacuationEventHistoryRepository;
import com.safespot.apicore.repository.ShelterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void injectObjectMapper() throws Exception {
        var field = EvacuationService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(evacuationService, objectMapper);
    }

    @Test
    void listEntries_withoutShelterId_returnsPagedEnteredEntries() {
        OffsetDateTime now = OffsetDateTime.now();
        when(entryRepository.searchAdminEntries(eq(null), eq(EntryStatus.ENTERED), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(row(11L, 101L, "Alpha Shelter", "Kim", "01011112222", true, now)),
                        PageRequest.of(0, 50),
                        1
                ));

        EvacuationEntryPageResponse response = evacuationService.listEntries(null, null, null, null, 0, 50);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getShelterName()).isEqualTo("Alpha Shelter");
        assertThat(response.getItems().get(0).getEntryStatus()).isEqualTo("ENTERED");
        assertThat(response.getItems().get(0).getDetail().getSpecialProtectionFlag()).isTrue();
        assertThat(response.getPage()).isEqualTo(0);
        assertThat(response.getSize()).isEqualTo(50);
        assertThat(response.getTotalElements()).isEqualTo(1);
        verify(shelterRepository, never()).findById(any());
        verify(entryDetailRepository, never()).findByEntryId(any());
    }

    @Test
    void listEntries_withShelterId_filtersByShelter() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelterWithCapacity(10)));
        when(entryRepository.searchAdminEntries(eq(101L), eq(EntryStatus.ENTERED), eq("kim"), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        evacuationService.listEntries(101L, "ENTERED", " kim ", true, 0, 50);

        verify(shelterRepository).findById(101L);
        verify(entryRepository).searchAdminEntries(eq(101L), eq(EntryStatus.ENTERED), eq("kim"), eq(true), any(Pageable.class));
        verify(entryDetailRepository, never()).findByEntryId(any());
    }

    @Test
    void listEntries_sizeGreaterThan100_capsPageSize() {
        when(entryRepository.searchAdminEntries(eq(null), eq(EntryStatus.ENTERED), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        evacuationService.listEntries(null, "ENTERED", null, null, 0, 500);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(entryRepository).searchAdminEntries(eq(null), eq(EntryStatus.ENTERED), eq(null), eq(null), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void listEntries_invalidStatus_throws400() {
        assertThatThrownBy(() -> evacuationService.listEntries(null, "WRONG", null, null, 0, 50))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void listEntries_shelterNotFound_throws404() {
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> evacuationService.listEntries(999L, null, null, null, 0, 50))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("NOT_FOUND"));
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
        setField(req, "reason", "done");

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
    void createEntry_invalidHealthStatus_throws400() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelterWithCapacity(10)));

        CreateEntryRequest req = buildCreateRequest(101L);
        setField(req, "healthStatus", "invalid_value");

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
        setField(req, "healthStatus", HealthStatus.values()[0].name());

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
                        .detailId(1L)
                        .entryId(301L)
                        .healthStatus(HealthStatus.values()[0])
                        .specialProtectionFlag(false)
                        .createdAt(OffsetDateTime.now())
                        .updatedAt(OffsetDateTime.now())
                        .build()));

        UpdateEntryRequest req = new UpdateEntryRequest();
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
                .detailId(1L)
                .entryId(301L)
                .healthStatus(HealthStatus.values()[0])
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

        UpdateEntryRequest req = new UpdateEntryRequest();

        evacuationService.updateEntry(301L, req, 7L, "127.0.0.1");

        assertThat(detail.getHealthStatus()).isEqualTo(HealthStatus.values()[0]);
    }

    private EvacuationEntryRepository.EvacuationEntryListRow row(
            Long entryId,
            Long shelterId,
            String shelterName,
            String visitorName,
            String visitorPhone,
            Boolean specialProtectionFlag,
            OffsetDateTime enteredAt
    ) {
        return new EvacuationEntryRepository.EvacuationEntryListRow() {
            @Override public Long getEntryId() { return entryId; }
            @Override public Long getShelterId() { return shelterId; }
            @Override public String getShelterName() { return shelterName; }
            @Override public Long getAlertId() { return 501L; }
            @Override public Long getUserId() { return 601L; }
            @Override public String getVisitorName() { return visitorName; }
            @Override public String getVisitorPhone() { return visitorPhone; }
            @Override public String getAddress() { return "Seoul"; }
            @Override public EntryStatus getEntryStatus() { return EntryStatus.ENTERED; }
            @Override public OffsetDateTime getEnteredAt() { return enteredAt; }
            @Override public OffsetDateTime getExitedAt() { return null; }
            @Override public String getNote() { return "note"; }
            @Override public String getFamilyInfo() { return "family"; }
            @Override public HealthStatus getHealthStatus() { return HealthStatus.values()[0]; }
            @Override public Boolean getSpecialProtectionFlag() { return specialProtectionFlag; }
        };
    }

    private Shelter shelterWithCapacity(int capacity) {
        return Shelter.builder()
                .shelterId(101L)
                .name("Test Shelter")
                .shelterType("indoor")
                .disasterType(DisasterType.EARTHQUAKE)
                .address("Seoul")
                .capacity(capacity)
                .latitude(BigDecimal.valueOf(37.5))
                .longitude(BigDecimal.valueOf(126.9))
                .build();
    }

    private CreateEntryRequest buildCreateRequest(Long shelterId) {
        CreateEntryRequest req = new CreateEntryRequest();
        setField(req, "shelterId", shelterId);
        setField(req, "name", "tester");
        return req;
    }

    private EvacuationEntry savedEntry(Long entryId) {
        return EvacuationEntry.builder()
                .entryId(entryId)
                .shelterId(101L)
                .entryStatus(EntryStatus.ENTERED)
                .enteredAt(OffsetDateTime.now())
                .build();
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

package com.safespot.apicore.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.domain.entity.AdminAuditLog;
import com.safespot.apicore.domain.entity.AppUser;
import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.DisasterType;
import com.safespot.apicore.domain.enums.HealthStatus;
import com.safespot.apicore.domain.enums.Role;
import com.safespot.apicore.repository.AdminAuditLogRepository;
import com.safespot.apicore.repository.AppUserRepository;
import com.safespot.apicore.repository.EvacuationEntryRepository;
import com.safespot.apicore.repository.ShelterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminEvacuationControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository appUserRepository;
    @Autowired ShelterRepository shelterRepository;
    @Autowired EvacuationEntryRepository entryRepository;
    @Autowired AdminAuditLogRepository auditLogRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String adminToken;
    private Long shelterId;

    @BeforeEach
    void setUp() throws Exception {
        auditLogRepository.deleteAll();
        entryRepository.deleteAll();
        shelterRepository.deleteAll();
        appUserRepository.deleteAll();

        AppUser admin = AppUser.builder()
                .username("admin01")
                .passwordHash(passwordEncoder.encode("P@ssw0rd!"))
                .name("admin")
                .role(Role.ADMIN)
                .active(true)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        appUserRepository.save(admin);

        Shelter shelter = Shelter.builder()
                .name("Primary Shelter")
                .shelterType("indoor")
                .disasterType(DisasterType.EARTHQUAKE)
                .address("Seoul")
                .latitude(BigDecimal.valueOf(37.5687))
                .longitude(BigDecimal.valueOf(126.9081))
                .capacity(10)
                .build();
        shelterId = shelterRepository.save(shelter).getShelterId();

        String loginBody = objectMapper.writeValueAsString(
                Map.of("loginId", "admin01", "password", "P@ssw0rd!"));
        String resp = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andReturn()
                .getResponse()
                .getContentAsString();
        adminToken = objectMapper.readTree(resp).get("data").get("accessToken").asText();
    }

    @Test
    void createEntry_success_returns201() throws Exception {
        Map<String, Object> body = Map.of(
                "shelterId", shelterId,
                "name", "tester",
                "phoneNumber", "01012345678",
                "healthStatus", HealthStatus.values()[0].name(),
                "specialProtectionFlag", false);

        mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.entryStatus").value("ENTERED"))
                .andExpect(jsonPath("$.data.shelterId").value(shelterId));
    }

    @Test
    void createEntry_missingName_returns400() throws Exception {
        Map<String, Object> body = Map.of("shelterId", shelterId);

        mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
    }

    @Test
    void createEntry_overCapacity_allowsEntry() throws Exception {
        Shelter smallShelter = Shelter.builder()
                .name("Small Shelter")
                .shelterType("indoor")
                .disasterType(DisasterType.FLOOD)
                .address("Seoul")
                .latitude(BigDecimal.valueOf(37.5))
                .longitude(BigDecimal.valueOf(126.9))
                .capacity(1)
                .build();
        Long smallShelterId = shelterRepository.save(smallShelter).getShelterId();

        createEntryForShelter(smallShelterId, "first", false);

        Map<String, Object> body = Map.of(
                "shelterId", smallShelterId,
                "name", "second",
                "healthStatus", HealthStatus.values()[0].name());

        mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.entryStatus").value("ENTERED"));
    }

    @Test
    void exitEntry_success_returns200() throws Exception {
        Long entryId = createEntryForShelter(shelterId, "visitor", false);

        mockMvc.perform(post("/admin/evacuation-entries/" + entryId + "/exit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "done"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entryStatus").value("EXITED"))
                .andExpect(jsonPath("$.data.exitedAt").isNotEmpty());
    }

    @Test
    void exitEntry_alreadyExited_returns409() throws Exception {
        Long entryId = createEntryForShelter(shelterId, "visitor", false);

        mockMvc.perform(post("/admin/evacuation-entries/" + entryId + "/exit")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        mockMvc.perform(post("/admin/evacuation-entries/" + entryId + "/exit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_EXITED"));
    }

    @Test
    void adminEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/admin/evacuation-entries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listEntries_withoutShelterId_returnsPagedResponse() throws Exception {
        createEntryForShelter(shelterId, "alpha", false);

        mockMvc.perform(get("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "ENTERED")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].shelterId").value(shelterId))
                .andExpect(jsonPath("$.data.items[0].shelterName").value("Primary Shelter"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void listEntries_withFilters_appliesShelterKeywordAndSpecialOnly() throws Exception {
        Shelter secondShelter = Shelter.builder()
                .name("Second Shelter")
                .shelterType("indoor")
                .disasterType(DisasterType.FLOOD)
                .address("Busan")
                .latitude(BigDecimal.valueOf(35.1))
                .longitude(BigDecimal.valueOf(129.0))
                .capacity(10)
                .build();
        Long secondShelterId = shelterRepository.save(secondShelter).getShelterId();

        createEntryForShelter(shelterId, "target-user", true);
        createEntryForShelter(secondShelterId, "other-user", false);

        mockMvc.perform(get("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("shelterId", shelterId.toString())
                        .param("status", "ENTERED")
                        .param("keyword", "target")
                        .param("specialOnly", "true")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].visitorName").value("target-user"))
                .andExpect(jsonPath("$.data.items[0].shelterId").value(shelterId))
                .andExpect(jsonPath("$.data.items[0].detail.specialProtectionFlag").value(true));
    }

    @Test
    void listEntries_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "WRONG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void patchShelter_withReason_returns200() throws Exception {
        Map<String, Object> body = Map.of(
                "capacityTotal", 20,
                "shelterStatus", "OPERATING",
                "reason", "inspection");

        mockMvc.perform(patch("/admin/shelters/" + shelterId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shelterId").value(shelterId));
    }

    @Test
    void patchShelter_missingReason_returns400() throws Exception {
        Map<String, Object> body = Map.of("capacityTotal", 20);

        mockMvc.perform(patch("/admin/shelters/" + shelterId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
    }

    @Test
    void createEntry_invalidHealthStatus_returns400() throws Exception {
        Map<String, Object> body = Map.of(
                "shelterId", shelterId,
                "name", "tester",
                "healthStatus", "invalid_value");

        mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createEntry_validHealthStatus_returns201() throws Exception {
        Map<String, Object> body = Map.of(
                "shelterId", shelterId,
                "name", "tester",
                "healthStatus", HealthStatus.values()[0].name());

        mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.entryStatus").value("ENTERED"));
    }

    @Test
    void createEntry_auditLog_persistsJsonbPayloadWithoutTypeError() throws Exception {
        Map<String, Object> body = Map.of(
                "shelterId", shelterId,
                "name", "jsonb test",
                "phoneNumber", "01099998888",
                "healthStatus", HealthStatus.values()[0].name(),
                "specialProtectionFlag", false);

        mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        List<AdminAuditLog> logs = auditLogRepository.findAll();
        assertThat(logs).isNotEmpty();
        AdminAuditLog log = logs.get(0);
        assertThat(log.getPayloadAfter()).isNotNull();
        assertThat(log.getPayloadAfter()).contains("entryId");
        assertThat(log.getPayloadBefore()).isNull();
    }

    private Long createEntryForShelter(Long targetShelterId, String name, boolean specialProtectionFlag) throws Exception {
        Map<String, Object> body = Map.of(
                "shelterId", targetShelterId,
                "name", name,
                "phoneNumber", "01012345678",
                "healthStatus", HealthStatus.values()[0].name(),
                "specialProtectionFlag", specialProtectionFlag);

        String response = mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("data").get("entryId").asLong();
    }
}

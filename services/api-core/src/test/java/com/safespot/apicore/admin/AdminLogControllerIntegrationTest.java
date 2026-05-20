package com.safespot.apicore.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.domain.entity.AdminAuditLog;
import com.safespot.apicore.domain.entity.AppUser;
import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.DisasterType;
import com.safespot.apicore.domain.enums.HealthStatus;
import com.safespot.apicore.domain.enums.Role;
import com.safespot.apicore.domain.enums.ShelterStatus;
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
class AdminLogControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository appUserRepository;
    @Autowired ShelterRepository shelterRepository;
    @Autowired EvacuationEntryRepository entryRepository;
    @Autowired AdminAuditLogRepository adminAuditLogRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String adminToken;
    private Long shelterId;

    @BeforeEach
    void setUp() throws Exception {
        adminAuditLogRepository.deleteAll();
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
                .name("로그 테스트 대피소")
                .shelterType("indoor")
                .disasterType(DisasterType.FLOOD)
                .address("서울")
                .latitude(BigDecimal.valueOf(37.5))
                .longitude(BigDecimal.valueOf(127.0))
                .capacity(10)
                .shelterStatus(ShelterStatus.OPERATING)
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
    void listLogs_returnsPagedResponse() throws Exception {
        createEntry("alpha");

        mockMvc.perform(get("/admin/logs")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].logId").isNumber())
                .andExpect(jsonPath("$.data.items[0].action").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].summary").isNotEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void listLogs_filtersByActionAndTargetType() throws Exception {
        createEntry("alpha");

        mockMvc.perform(patch("/admin/shelters/" + shelterId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "capacityTotal", 20,
                                "shelterStatus", "OPERATING",
                                "reason", "inspection"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/logs")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("action", "ENTRY_CREATE")
                        .param("targetType", "evacuation_entry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value("ENTRY_CREATE"))
                .andExpect(jsonPath("$.data.items[0].targetType").value("evacuation_entry"));
    }

    @Test
    void listLogs_filtersByDateRangeAndClampsSize() throws Exception {
        createEntry("alpha");
        createHistoricalLog();

        mockMvc.perform(get("/admin/logs")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("from", "2026-05-20")
                        .param("to", "2026-05-21")
                        .param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value("ENTRY_CREATE"));
    }

    @Test
    void listLogs_invalidDate_returns400() throws Exception {
        mockMvc.perform(get("/admin/logs")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("from", "2026-99-99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private void createEntry(String name) throws Exception {
        mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "shelterId", shelterId,
                                "name", name,
                                "phoneNumber", "01012345678",
                                "healthStatus", HealthStatus.values()[0].name(),
                                "specialProtectionFlag", false))))
                .andExpect(status().isCreated());
    }

    private void createHistoricalLog() {
        AdminAuditLog historical = AdminAuditLog.builder()
                .adminId(1L)
                .action("ENTRY_UPDATE")
                .targetType("evacuation_entry")
                .targetId(999L)
                .payloadBefore("{\"before\":true}")
                .payloadAfter("{\"after\":true}")
                .ipAddress("10.0.0.5")
                .createdAt(OffsetDateTime.parse("2026-05-19T10:00:00+09:00"))
                .build();
        adminAuditLogRepository.save(historical);
        assertThat(adminAuditLogRepository.findAll()).isNotEmpty();
    }
}

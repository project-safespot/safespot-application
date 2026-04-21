package com.safespot.apicore.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.domain.entity.AppUser;
import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.Role;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminEvacuationControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository appUserRepository;
    @Autowired ShelterRepository shelterRepository;
    @Autowired EvacuationEntryRepository entryRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String adminToken;
    private Long shelterId;

    @BeforeEach
    void setUp() throws Exception {
        entryRepository.deleteAll();
        shelterRepository.deleteAll();
        appUserRepository.deleteAll();

        AppUser admin = AppUser.builder()
                .username("admin01")
                .passwordHash(passwordEncoder.encode("P@ssw0rd!"))
                .name("관리자")
                .role(Role.ADMIN)
                .isActive(true)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        appUserRepository.save(admin);

        Shelter shelter = Shelter.builder()
                .name("테스트대피소")
                .shelterType("민방위대피소")
                .disasterType(com.safespot.apicore.domain.enums.DisasterType.EARTHQUAKE)
                .address("서울특별시 마포구")
                .latitude(BigDecimal.valueOf(37.5687))
                .longitude(BigDecimal.valueOf(126.9081))
                .capacity(10)
                .build();
        shelterId = shelterRepository.save(shelter).getShelterId();

        String loginBody = objectMapper.writeValueAsString(
                Map.of("loginId", "admin01", "password", "P@ssw0rd!"));
        String resp = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andReturn().getResponse().getContentAsString();
        adminToken = objectMapper.readTree(resp).get("data").get("accessToken").asText();
    }

    @Test
    void createEntry_success_returns201() throws Exception {
        Map<String, Object> body = Map.of(
                "shelterId", shelterId,
                "name", "홍길동",
                "phoneNumber", "01012345678",
                "healthStatus", "정상",
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
    void createEntry_shelterFull_returns409() throws Exception {
        Shelter fullShelter = Shelter.builder()
                .name("꽉찬대피소").shelterType("민방위대피소")
                .disasterType(com.safespot.apicore.domain.enums.DisasterType.FLOOD)
                .address("서울").latitude(BigDecimal.valueOf(37.5))
                .longitude(BigDecimal.valueOf(126.9)).capacity(1).build();
        Long fullShelterId = shelterRepository.save(fullShelter).getShelterId();

        // 첫 번째 입소
        Map<String, Object> body = Map.of("shelterId", fullShelterId, "name", "첫번째");
        mockMvc.perform(post("/admin/evacuation-entries")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));

        // 두 번째 입소 → SHELTER_FULL
        Map<String, Object> body2 = Map.of("shelterId", fullShelterId, "name", "두번째");
        mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHELTER_FULL"));
    }

    @Test
    void exitEntry_success_returns200() throws Exception {
        // 입소
        Map<String, Object> createBody = Map.of("shelterId", shelterId, "name", "홍길동");
        String createResp = mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andReturn().getResponse().getContentAsString();
        Long entryId = objectMapper.readTree(createResp).get("data").get("entryId").asLong();

        // 퇴소
        mockMvc.perform(post("/admin/evacuation-entries/" + entryId + "/exit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "자택 복귀"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entryStatus").value("EXITED"))
                .andExpect(jsonPath("$.data.exitedAt").isNotEmpty());
    }

    @Test
    void exitEntry_alreadyExited_returns409() throws Exception {
        Map<String, Object> createBody = Map.of("shelterId", shelterId, "name", "홍길동");
        String createResp = mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andReturn().getResponse().getContentAsString();
        Long entryId = objectMapper.readTree(createResp).get("data").get("entryId").asLong();

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
        mockMvc.perform(get("/admin/evacuation-entries?shelterId=" + shelterId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patchShelter_withReason_returns200() throws Exception {
        Map<String, Object> body = Map.of(
                "capacityTotal", 20,
                "shelterStatus", "운영중",
                "reason", "현장 재점검");

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
}

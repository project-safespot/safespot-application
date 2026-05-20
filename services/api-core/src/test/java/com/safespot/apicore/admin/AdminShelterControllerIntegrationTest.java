package com.safespot.apicore.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.domain.entity.AppUser;
import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.DisasterType;
import com.safespot.apicore.domain.enums.HealthStatus;
import com.safespot.apicore.domain.enums.Role;
import com.safespot.apicore.domain.enums.ShelterStatus;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminShelterControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository appUserRepository;
    @Autowired ShelterRepository shelterRepository;
    @Autowired EvacuationEntryRepository entryRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String adminToken;
    private Long operatingShelterId;

    @BeforeEach
    void setUp() throws Exception {
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

        Shelter operatingShelter = Shelter.builder()
                .name("역삼 운영 대피소")
                .shelterType("indoor")
                .disasterType(DisasterType.FLOOD)
                .address("서울 강남구 역삼동")
                .latitude(BigDecimal.valueOf(37.5))
                .longitude(BigDecimal.valueOf(127.0))
                .capacity(10)
                .shelterStatus(ShelterStatus.OPERATING)
                .manager("담당자1")
                .contact("02-1111-1111")
                .build();
        operatingShelterId = shelterRepository.save(operatingShelter).getShelterId();

        Shelter preparingShelter = Shelter.builder()
                .name("대치 준비 대피소")
                .shelterType("school")
                .disasterType(DisasterType.EARTHQUAKE)
                .address("서울 강남구 대치동")
                .latitude(BigDecimal.valueOf(37.49))
                .longitude(BigDecimal.valueOf(127.06))
                .capacity(20)
                .shelterStatus(ShelterStatus.PREPARING)
                .manager("담당자2")
                .contact("02-2222-2222")
                .build();
        shelterRepository.save(preparingShelter);

        String loginBody = objectMapper.writeValueAsString(
                Map.of("loginId", "admin01", "password", "P@ssw0rd!"));
        String resp = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andReturn()
                .getResponse()
                .getContentAsString();
        adminToken = objectMapper.readTree(resp).get("data").get("accessToken").asText();

        createEntryForShelter(operatingShelterId, "alpha");
        createEntryForShelter(operatingShelterId, "beta");
    }

    @Test
    void listShelters_returnsPagedResponse() throws Exception {
        mockMvc.perform(get("/admin/shelters")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].shelterId").isNumber())
                .andExpect(jsonPath("$.data.items[0].currentOccupants").exists())
                .andExpect(jsonPath("$.data.items[0].availableCapacity").exists())
                .andExpect(jsonPath("$.data.items[0].crowdingLevel").exists())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void listShelters_filtersByKeyword() throws Exception {
        mockMvc.perform(get("/admin/shelters")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("keyword", "역삼")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("역삼 운영 대피소"))
                .andExpect(jsonPath("$.data.items[0].currentOccupants").value(2));
    }

    @Test
    void listShelters_filtersByStatus() throws Exception {
        mockMvc.perform(get("/admin/shelters")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "OPERATING")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].shelterId").value(operatingShelterId))
                .andExpect(jsonPath("$.data.items[0].shelterStatus").value("OPERATING"));
    }

    private void createEntryForShelter(Long shelterId, String name) throws Exception {
        Map<String, Object> body = Map.of(
                "shelterId", shelterId,
                "name", name,
                "phoneNumber", "01012345678",
                "healthStatus", HealthStatus.values()[0].name(),
                "specialProtectionFlag", false);

        mockMvc.perform(post("/admin/evacuation-entries")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }
}

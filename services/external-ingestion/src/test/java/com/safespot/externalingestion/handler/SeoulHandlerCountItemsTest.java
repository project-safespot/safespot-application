package com.safespot.externalingestion.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.handler.groupa1.SeoulEarthquakeHandler;
import com.safespot.externalingestion.handler.groupa1.SeoulRiverLevelHandler;
import com.safespot.externalingestion.handler.groupb.SeoulShelterEarthquakeHandler;
import com.safespot.externalingestion.handler.groupb.SeoulShelterLandslideHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SeoulHandlerCountItemsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Official response shape for SEOUL_EARTHQUAKE (TbEqkKenvinfo)
    private static final String EARTHQUAKE_BODY = """
        {"TbEqkKenvinfo":{"row":[
          {"OCCR_DT":"2026-04-21 10:05:00","OCCR_PLC":"서울 서초구","MAGNTD_1":"3.2"}
        ]}}
        """;

    // Official response shape for SEOUL_RIVER_LEVEL (ListRiverStageService)
    private static final String RIVER_BODY = """
        {"ListRiverStageService":{"row":[
          {"STATION":"한강대교","STD_DT":"2026-04-21 10:00:00","WATER_LEVEL":"4.5"},
          {"STATION":"잠수교","STD_DT":"2026-04-21 10:00:00","WATER_LEVEL":"2.1"}
        ]}}
        """;

    // Official response shape for SEOUL_SHELTER_EARTHQUAKE (TlEtqkP)
    private static final String SHELTER_EQ_BODY = """
        {"TlEtqkP":{"row":[
          {"SHELTER_NM":"서초구민회관","RD_ADDR":"서울특별시 서초구"},
          {"SHELTER_NM":"양재천공원","RD_ADDR":"서울특별시 서초구"}
        ]}}
        """;

    // odcloud response shape for SEOUL_SHELTER_LANDSLIDE
    private static final String SHELTER_LS_BODY = """
        {"currentCount":2,"data":[
          {"SHELTER_NM":"은평구 생활문화센터","RD_ADDR":"서울특별시 은평구"},
          {"SHELTER_NM":"진관동 주민센터","RD_ADDR":"서울특별시 은평구"}
        ],"matchCount":2,"page":1,"perPage":1000,"totalCount":2}
        """;

    private SeoulEarthquakeHandler eqHandler;
    private SeoulRiverLevelHandler riverHandler;
    private SeoulShelterEarthquakeHandler shelterEqHandler;
    private SeoulShelterLandslideHandler shelterLsHandler;

    @BeforeEach
    void setup() {
        eqHandler = new SeoulEarthquakeHandler();
        riverHandler = new SeoulRiverLevelHandler();
        shelterEqHandler = new SeoulShelterEarthquakeHandler();
        shelterLsHandler = new SeoulShelterLandslideHandler();
        // Inject ObjectMapper into the abstract parent's autowired field
        for (Object h : new Object[]{eqHandler, riverHandler, shelterEqHandler, shelterLsHandler}) {
            ReflectionTestUtils.setField(h, "objectMapper", objectMapper);
        }
    }

    @Test
    void seoulEarthquakeCountsFromTbEqkKenvinfo() {
        assertThat(countItems(eqHandler, EARTHQUAKE_BODY)).isEqualTo(1);
    }

    @Test
    void seoulEarthquakeReturnsZeroForOldRoot() {
        String oldBody = """
            {"ListEqkEq":{"row":[{"OCCR_DT":"2026-04-21 10:05:00"}]}}
            """;
        assertThat(countItems(eqHandler, oldBody)).isEqualTo(0);
    }

    @Test
    void seoulRiverLevelCountsFromListRiverStageService() {
        assertThat(countItems(riverHandler, RIVER_BODY)).isEqualTo(2);
    }

    @Test
    void seoulRiverLevelReturnsZeroForOldRoot() {
        String oldBody = """
            {"ListStnWaterLevelEntry":{"row":[{"STATION":"한강대교"}]}}
            """;
        assertThat(countItems(riverHandler, oldBody)).isEqualTo(0);
    }

    @Test
    void seoulShelterEarthquakeCountsFromTlEtqkP() {
        assertThat(countItems(shelterEqHandler, SHELTER_EQ_BODY)).isEqualTo(2);
    }

    @Test
    void seoulShelterEarthquakeReturnsZeroForOldRoot() {
        String oldBody = """
            {"TbEqkShelterInfo":{"row":[{"SHELTER_NM":"서초구민회관"}]}}
            """;
        assertThat(countItems(shelterEqHandler, oldBody)).isEqualTo(0);
    }

    @Test
    void seoulShelterLandslideCountsFromOdcloudData() {
        assertThat(countItems(shelterLsHandler, SHELTER_LS_BODY)).isEqualTo(2);
    }

    @Test
    void seoulShelterLandslideReturnsZeroForOldRoot() {
        String oldBody = """
            {"TbLdslShelterInfo":{"row":[{"SHELTER_NM":"은평구 생활문화센터"}]}}
            """;
        assertThat(countItems(shelterLsHandler, oldBody)).isEqualTo(0);
    }

    private int countItems(AbstractIngestionHandler handler, String body) {
        try {
            var method = handler.getClass().getDeclaredMethod("countItems", String.class);
            method.setAccessible(true);
            return (int) method.invoke(handler, body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

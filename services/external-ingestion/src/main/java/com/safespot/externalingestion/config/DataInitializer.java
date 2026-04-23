package com.safespot.externalingestion.config;

import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.repository.ExternalApiSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * external_api_source 초기 데이터 seed (DB에 없을 경우에만 INSERT)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final ExternalApiSourceRepository sourceRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedSources();
    }

    private void seedSources() {
        List<SourceSeed> seeds = List.of(
            new SourceSeed("SAFETY_DATA_ALERT",      "재난문자",          "행정안전부", "DISASTER",    "API_KEY", true,  "https://www.safetydata.go.kr/B553559/kakaoApi/api/rest/disasterMsg/selectDisasterNsgList.do"),
            new SourceSeed("KMA_EARTHQUAKE",          "지진 정보",         "기상청",    "DISASTER",    "API_KEY", true,  "https://apis.data.go.kr/1360000/EqkInfoService2/getEqkMsg"),
            new SourceSeed("SEOUL_EARTHQUAKE",        "서울시 지진 발생 현황","서울시",   "DISASTER",    "API_KEY", true,  "https://openapi.seoul.go.kr:8088/{KEY}/json/ListEqkEq/1/20"),
            new SourceSeed("FORESTRY_LANDSLIDE",      "산사태 위험 예측",  "산림청",    "DISASTER",    "API_KEY", false, "https://apis.data.go.kr/1400119/slfswarnApi/getSlfswarnDataList"),
            new SourceSeed("SEOUL_RIVER_LEVEL",       "하천 수위",         "서울시",    "DISASTER",    "API_KEY", true,  "https://openapi.seoul.go.kr:8088/{KEY}/json/ListStnWaterLevelEntry/1/50"),
            new SourceSeed("KMA_WEATHER",             "날씨 단기예보",      "기상청",    "ENVIRONMENT", "API_KEY", true,  "https://apis.data.go.kr/1360000/VilageFcstInfoService2.0/getVilageFcst"),
            new SourceSeed("AIR_KOREA_AIR_QUALITY",   "대기질",            "에어코리아","ENVIRONMENT", "API_KEY", true,  "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty"),
            new SourceSeed("SEOUL_SHELTER_EARTHQUAKE","서울시 지진옥외대피소","서울시",  "SHELTER",     "API_KEY", true,  "https://openapi.seoul.go.kr:8088/{KEY}/json/TbEqkShelterInfo/1/1000"),
            new SourceSeed("SEOUL_SHELTER_LANDSLIDE", "서울시 산사태 대피소","서울시",   "SHELTER",     "API_KEY", true,  "https://openapi.seoul.go.kr:8088/{KEY}/json/TbLdslShelterInfo/1/1000"),
            new SourceSeed("SEOUL_SHELTER_FLOOD",     "서울시 수해 대피소", "서울시",    "SHELTER",     "FILE",    true,  null)
        );

        for (SourceSeed s : seeds) {
            if (sourceRepo.findBySourceCode(s.code).isEmpty()) {
                ExternalApiSource src = new ExternalApiSource();
                src.setSourceCode(s.code);
                src.setSourceName(s.name);
                src.setProvider(s.provider);
                src.setCategory(s.category);
                src.setAuthType(s.authType);
                src.setBaseUrl(s.baseUrl);
                src.setActive(s.active);
                sourceRepo.save(src);
                log.info("[DataInitializer] seeded source={}", s.code);
            }
        }
    }

    private record SourceSeed(String code, String name, String provider, String category,
                               String authType, boolean active, String baseUrl) {}
}

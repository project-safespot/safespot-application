package com.safespot.externalingestion.handler.groupb;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import com.safespot.externalingestion.handler.IngestionResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 서울시 수해 대피소 (SEOUL_SHELTER_FLOOD)
 * 실행 방식: 배치 전용 (xlsx 파일) — CronJob 미생성
 * 정규화 대상: shelter (selective upsert)
 *
 * TODO: xlsx 파일 파싱 구현 필요. 현재 execute()는 비활성 상태.
 *       파일 적재 시 별도 배치 스크립트로 실행.
 */
@Component
public class SeoulShelterFloodHandler extends AbstractIngestionHandler {

    @Override
    public String getSourceCode() {
        return "SEOUL_SHELTER_FLOOD";
    }

    @Override
    public boolean isEnabled() {
        return false; // 배치 전용, polling loop 비활성
    }

    @Override
    public IngestionResult execute() {
        return IngestionResult.skipped("batch-only source — use batch script");
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        return Map.of(); // file-based, no HTTP params
    }

    @Override
    protected int countItems(String responseBody) {
        return 0;
    }
}

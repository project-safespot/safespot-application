package com.safespot.externalingestion.scheduler;

import com.safespot.externalingestion.handler.groupa1.ForestryLandslideHandler;
import com.safespot.externalingestion.handler.groupa1.KmaEarthquakeHandler;
import com.safespot.externalingestion.handler.groupa1.SafetyDataAlertHandler;
import com.safespot.externalingestion.handler.groupa1.SeoulEarthquakeHandler;
import com.safespot.externalingestion.handler.groupa1.SeoulRiverLevelHandler;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.queue.NormalizationMessage;
import com.safespot.externalingestion.queue.NormalizationQueue;
import com.safespot.externalingestion.service.NormalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Group A-1 Polling Scheduler (상시 실행 Deployment 내 polling loop 구현)
 *
 * 운영 환경(K8s): Deployment로 상시 실행, 각 소스가 독립 루프로 동작
 * 로컬/테스트: Spring @Scheduled로 주기 관리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PollingScheduler {

    private final SafetyDataAlertHandler safetyDataAlertHandler;
    private final KmaEarthquakeHandler kmaEarthquakeHandler;
    private final SeoulEarthquakeHandler seoulEarthquakeHandler;
    private final ForestryLandslideHandler forestryLandslideHandler;
    private final SeoulRiverLevelHandler seoulRiverLevelHandler;
    private final NormalizationQueue normalizationQueue;
    private final NormalizationService normalizationService;
    private final IngestionMetrics metrics;

    /** SAFETY_DATA_ALERT: 2분 주기, 일일 1,000회 한도 준수 */
    @Scheduled(fixedDelayString = "${ingestion.schedule.safety-data-alert-ms:120000}")
    public void collectSafetyDataAlerts() {
        metrics.incrementPollingIteration("SAFETY_DATA_ALERT");
        safetyDataAlertHandler.execute();
        drainQueue();
    }

    /** KMA_EARTHQUAKE: 1분 주기 */
    @Scheduled(fixedDelayString = "${ingestion.schedule.kma-earthquake-ms:60000}")
    public void collectKmaEarthquake() {
        metrics.incrementPollingIteration("KMA_EARTHQUAKE");
        kmaEarthquakeHandler.execute();
        drainQueue();
    }

    /** SEOUL_EARTHQUAKE: 30초 주기 */
    @Scheduled(fixedDelayString = "${ingestion.schedule.seoul-earthquake-ms:30000}")
    public void collectSeoulEarthquake() {
        metrics.incrementPollingIteration("SEOUL_EARTHQUAKE");
        seoulEarthquakeHandler.execute();
        drainQueue();
    }

    /**
     * FORESTRY_LANDSLIDE: 5분 주기
     * isEnabled()=false 이므로 handler 내부에서 SKIP 처리됨 (인증키 승인 대기 중)
     */
    @Scheduled(fixedDelayString = "${ingestion.schedule.forestry-landslide-ms:300000}")
    public void collectForestryLandslide() {
        metrics.incrementPollingIteration("FORESTRY_LANDSLIDE");
        forestryLandslideHandler.execute();
        drainQueue();
    }

    /** SEOUL_RIVER_LEVEL: 30초 주기 */
    @Scheduled(fixedDelayString = "${ingestion.schedule.seoul-river-level-ms:30000}")
    public void collectSeoulRiverLevel() {
        metrics.incrementPollingIteration("SEOUL_RIVER_LEVEL");
        seoulRiverLevelHandler.execute();
        drainQueue();
    }

    /** 인메모리 큐를 소진하여 정규화 처리 */
    private void drainQueue() {
        NormalizationMessage msg;
        while ((msg = normalizationQueue.poll()) != null) {
            normalizationService.process(msg);
        }
    }
}

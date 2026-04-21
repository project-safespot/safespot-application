package com.safespot.externalingestion.scheduler;

import com.safespot.externalingestion.handler.groupa2.AirKoreaAirQualityHandler;
import com.safespot.externalingestion.handler.groupa2.KmaWeatherHandler;
import com.safespot.externalingestion.handler.groupb.SeoulShelterEarthquakeHandler;
import com.safespot.externalingestion.handler.groupb.SeoulShelterLandslideHandler;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.queue.NormalizationMessage;
import com.safespot.externalingestion.queue.NormalizationQueue;
import com.safespot.externalingestion.service.NormalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Group A-2 / Group B CronJob Scheduler
 *
 * 운영 환경(K8s): Kubernetes CronJob 리소스로 직접 관리 (concurrencyPolicy: Forbid)
 * 로컬/테스트: Spring @Scheduled cron으로 주기 관리
 * CronJob 실행 시간이 짧으므로 Prometheus scrape 대상 제외
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronJobScheduler {

    private final KmaWeatherHandler kmaWeatherHandler;
    private final AirKoreaAirQualityHandler airKoreaAirQualityHandler;
    private final SeoulShelterEarthquakeHandler seoulShelterEarthquakeHandler;
    private final SeoulShelterLandslideHandler seoulShelterLandslideHandler;
    private final NormalizationQueue normalizationQueue;
    private final NormalizationService normalizationService;
    private final IngestionMetrics metrics;

    /** KMA_WEATHER: 매시 정각 (cron: 0 * * * *) */
    @Scheduled(cron = "${ingestion.schedule.kma-weather-cron:0 0 * * * *}")
    public void collectKmaWeather() {
        log.info("[CronJob] KMA_WEATHER start");
        kmaWeatherHandler.execute();
        drainQueue();
    }

    /** AIR_KOREA_AIR_QUALITY: 매시 정각 (cron: 0 * * * *) */
    @Scheduled(cron = "${ingestion.schedule.air-korea-cron:0 0 * * * *}")
    public void collectAirKoreaAirQuality() {
        log.info("[CronJob] AIR_KOREA_AIR_QUALITY start");
        airKoreaAirQualityHandler.execute();
        drainQueue();
    }

    /** SEOUL_SHELTER_EARTHQUAKE: 매일 새벽 2시 (cron: 0 2 * * *) */
    @Scheduled(cron = "${ingestion.schedule.seoul-shelter-earthquake-cron:0 0 2 * * *}")
    public void collectSeoulShelterEarthquake() {
        log.info("[CronJob] SEOUL_SHELTER_EARTHQUAKE start");
        seoulShelterEarthquakeHandler.execute();
        drainQueue();
    }

    /** SEOUL_SHELTER_LANDSLIDE: 매일 새벽 2시 (cron: 0 2 * * *) */
    @Scheduled(cron = "${ingestion.schedule.seoul-shelter-landslide-cron:0 0 2 * * *}")
    public void collectSeoulShelterLandslide() {
        log.info("[CronJob] SEOUL_SHELTER_LANDSLIDE start");
        seoulShelterLandslideHandler.execute();
        drainQueue();
    }

    private void drainQueue() {
        NormalizationMessage msg;
        while ((msg = normalizationQueue.poll()) != null) {
            normalizationService.process(msg);
        }
    }
}

package com.safespot.externalingestion.scheduler;

import com.safespot.externalingestion.handler.groupa2.AirKoreaAirQualityHandler;
import com.safespot.externalingestion.handler.groupa2.KmaWeatherHandler;
import com.safespot.externalingestion.handler.groupb.SeoulShelterEarthquakeHandler;
import com.safespot.externalingestion.handler.groupb.SeoulShelterLandslideHandler;
import com.safespot.externalingestion.queue.NormalizationMessage;
import com.safespot.externalingestion.queue.NormalizationQueue;
import com.safespot.externalingestion.service.NormalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs a one-shot warmup collection when the pod starts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupIngestionRunner {

    private final KmaWeatherHandler kmaWeatherHandler;
    private final AirKoreaAirQualityHandler airKoreaAirQualityHandler;
    private final SeoulShelterEarthquakeHandler seoulShelterEarthquakeHandler;
    private final SeoulShelterLandslideHandler seoulShelterLandslideHandler;
    private final NormalizationQueue normalizationQueue;
    private final NormalizationService normalizationService;

    @Value("${ingestion.startup.enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void runOnceOnStartup() {
        if (!enabled) {
            log.info("[StartupIngestion] disabled");
            return;
        }

        log.info("[StartupIngestion] started sources=KMA_WEATHER,AIR_KOREA_AIR_QUALITY,SEOUL_SHELTER_EARTHQUAKE,SEOUL_SHELTER_LANDSLIDE");
        collect("KMA_WEATHER", kmaWeatherHandler::execute);
        collect("AIR_KOREA_AIR_QUALITY", airKoreaAirQualityHandler::execute);
        collect("SEOUL_SHELTER_EARTHQUAKE", seoulShelterEarthquakeHandler::execute);
        collect("SEOUL_SHELTER_LANDSLIDE", seoulShelterLandslideHandler::execute);
        log.info("[StartupIngestion] completed");
    }

    private void collect(String sourceCode, Runnable task) {
        try {
            log.info("[StartupIngestion] source={} start", sourceCode);
            task.run();
            drainQueue();
            log.info("[StartupIngestion] source={} completed", sourceCode);
        } catch (Exception e) {
            log.warn("[StartupIngestion] source={} failed", sourceCode, e);
        }
    }

    private void drainQueue() {
        NormalizationMessage msg;
        while ((msg = normalizationQueue.poll()) != null) {
            normalizationService.process(msg);
        }
    }
}

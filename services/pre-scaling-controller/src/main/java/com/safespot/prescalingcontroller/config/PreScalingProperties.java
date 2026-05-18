package com.safespot.prescalingcontroller.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "pre-scaling")
@Getter
@Setter
public class PreScalingProperties {

    private boolean enabled = true;

    /** Kubernetes namespace where the target HPA lives. */
    private String namespace = "application";

    private Trigger trigger = new Trigger();
    private Surge surge = new Surge();
    private Base base = new Base();
    private Routing routing = new Routing();
    private Restore restore = new Restore();

    /** DB polling interval in milliseconds. */
    private long pollingIntervalMs = 30_000;

    @Getter
    @Setter
    public static class Trigger {
        /** Disaster types that activate pre-scaling. */
        private List<String> disasterTypes = List.of("EARTHQUAKE", "FLOOD", "LANDSLIDE");

        /** Minimum level_rank to trigger (inclusive). WARNING=3, CRITICAL=4. */
        private int minLevelRank = 3;

        /**
         * How far back to look for active alerts.
         * Alerts issued before this window are ignored even if expired_at IS NULL.
         */
        private int lookbackMinutes = 10;
    }

    @Getter
    @Setter
    public static class Surge {
        private String targetHpaName = "api-public-read-surge";
        private int normalMinReplicas = 1;
        private int peakMinReplicas = 8;
        private int sustainedMinReplicas = 3;
        private long peakWindowSeconds = 1800;
    }

    @Getter
    @Setter
    public static class Base {
        private boolean enabled = true;
        private String targetHpaName = "api-public-read";
        private int normalMinReplicas = 1;
        private int normalMaxReplicas = 5;
        private int disasterMinReplicas = 1;
        private int disasterMaxReplicas = 1;
        private boolean restrictAfterRouting = true;
    }

    @Getter
    @Setter
    public static class Routing {
        private boolean enabled = true;
        private String ingressName = "api-public-read";
        private String actionAnnotationKey = "alb.ingress.kubernetes.io/actions.api-public-read-weighted";
        private String baseServiceName = "api-public-read";
        private String surgeServiceName = "api-public-read-surge";
        private int normalBaseWeight = 100;
        private int normalSurgeWeight = 0;
        private int disasterBaseWeight = 0;
        private int disasterSurgeWeight = 100;
    }

    @Getter
    @Setter
    public static class Restore {
        private long baseReadinessTimeoutSeconds = 120;
    }
}

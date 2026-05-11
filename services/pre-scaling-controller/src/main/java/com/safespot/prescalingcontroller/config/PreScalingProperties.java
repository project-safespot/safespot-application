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

    /** Name of the HPA to patch. */
    private String targetHpaName = "api-public-read-surge";

    private Trigger trigger = new Trigger();
    private Surge surge = new Surge();

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
        /** HPA minReplicas when no disaster is active. */
        private int normalMinReplicas = 0;

        /** HPA minReplicas when a disaster is active. */
        private int disasterMinReplicas = 3;

        /** HPA maxReplicas upper bound (read-only reference; not patched by this controller). */
        private int maxReplicas = 10;

        /** Seconds to wait after disaster clears before recovering to normalMinReplicas. */
        private long cooldownSeconds = 1800;
    }
}

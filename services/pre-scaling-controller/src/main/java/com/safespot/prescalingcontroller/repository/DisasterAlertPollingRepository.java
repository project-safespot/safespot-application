package com.safespot.prescalingcontroller.repository;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Polls disaster_alert for active qualifying alerts.
 *
 * Qualifying conditions (§5, §6 of pre-scaling-controller.md):
 *   - disaster_type IN configured trigger types
 *   - level_rank >= minLevelRank (NULL level_rank rows are excluded)
 *   - issued_at >= NOW() - lookbackMinutes
 *   - expired_at IS NULL (alert is still active)
 *
 * Returns DisasterConditionResult to distinguish a successful "no disaster" query
 * from a failed DB query (UNKNOWN), preventing false recovery on DB outage.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class DisasterAlertPollingRepository {

    private final JdbcTemplate jdbc;
    private final PreScalingProperties properties;

    public DisasterConditionResult queryCondition() {
        PreScalingProperties.Trigger trigger = properties.getTrigger();
        List<String> types = trigger.getDisasterTypes();

        String placeholders = String.join(", ", types.stream().map(t -> "?").toList());

        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM disaster_alert
                    WHERE disaster_type IN (%s)
                      AND level_rank >= ?
                      AND issued_at >= NOW() - (? * INTERVAL '1 minute')
                      AND expired_at IS NULL
                )
                """.formatted(placeholders);

        Object[] params = buildParams(types, trigger.getMinLevelRank(), trigger.getLookbackMinutes());

        log.debug("[PreScaling] DB poll — types={}, minLevelRank={}, lookbackMinutes={}",
                trigger.getDisasterTypes(), trigger.getMinLevelRank(), trigger.getLookbackMinutes());

        Boolean result = jdbc.queryForObject(sql, Boolean.class, params);
        return Boolean.TRUE.equals(result) ? DisasterConditionResult.ACTIVE : DisasterConditionResult.INACTIVE;
    }

    private Object[] buildParams(List<String> types, int minLevelRank, int lookbackMinutes) {
        Object[] params = new Object[types.size() + 2];
        int i = 0;
        for (String type : types) {
            params[i++] = type;
        }
        params[i++] = minLevelRank;
        params[i] = lookbackMinutes;
        return params;
    }
}

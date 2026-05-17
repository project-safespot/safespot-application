package com.safespot.scenariosimulator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "simulator.sqs")
public class SimulatorSqsProperties {

    private boolean enabled;
    private String region = "ap-northeast-2";
    private String endpointOverride = "";
    private String cacheRefreshQueueUrl = "";
    private String readmodelRefreshQueueUrl = "";
}

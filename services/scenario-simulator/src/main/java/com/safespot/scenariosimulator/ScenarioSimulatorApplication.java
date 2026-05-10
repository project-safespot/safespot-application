package com.safespot.scenariosimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;

@SpringBootApplication
public class ScenarioSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ScenarioSimulatorApplication.class);
        // Fail fast if accidentally started in prod to prevent test data injection
        app.addListeners((ApplicationEnvironmentPreparedEvent event) -> {
            if (event.getEnvironment().matchesProfiles("prod")) {
                throw new IllegalStateException(
                        "scenario-simulator must not run in prod profile. " +
                        "Enable only in local/dev/test environments.");
            }
        });
        app.run(args);
    }
}

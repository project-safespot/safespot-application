package com.safespot.externalingestion.util;

public final class SeoulOpenApiUrlBuilder {

    static final String KEY_PLACEHOLDER = "{KEY}";

    private SeoulOpenApiUrlBuilder() {}

    public static String buildUrl(String templateUrl, String actualKey) {
        if (templateUrl == null || !templateUrl.contains(KEY_PLACEHOLDER)) {
            throw new IllegalArgumentException(
                "Seoul OpenAPI URL must contain {KEY} placeholder: " + templateUrl);
        }
        return templateUrl.replace(KEY_PLACEHOLDER, actualKey);
    }
}

package com.safespot.externalingestion.client;

import java.util.Map;

public interface ExternalApiClient {
    /**
     * @throws ExternalApiException on network / HTTP error
     */
    String get(String url, Map<String, String> params) throws ExternalApiException;
}

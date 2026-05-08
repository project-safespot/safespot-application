package com.safespot.externalingestion.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RestClientExternalApiClient implements ExternalApiClient {

    private final RestClient restClient;

    @Override
    public String get(String url, Map<String, String> params) throws ExternalApiException {
        URI uri = buildUri(url, params);
        try {
            return restClient.get()
                .uri(uri)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    String body = "";
                    try { body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8); } catch (Exception ignored) {}
                    String snippet = body.length() > 300 ? body.substring(0, 300) : body;
                    log.warn("[ExternalApiClient] 4xx url={} status={} body={}", url, res.getStatusCode().value(), snippet);
                    String detail = snippet.isBlank() ? "" : " body=" + snippet;
                    throw new ExternalApiException(
                        "4xx from " + url + detail, ExternalApiException.ErrorType.CLIENT_ERROR, res.getStatusCode().value());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new ExternalApiException(
                        "5xx from " + url, ExternalApiException.ErrorType.SERVER_ERROR, res.getStatusCode().value());
                })
                .body(String.class);
        } catch (ResourceAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                throw new ExternalApiException("timeout calling " + url, ExternalApiException.ErrorType.TIMEOUT, null, e);
            }
            throw new ExternalApiException("network error calling " + url, ExternalApiException.ErrorType.NETWORK, null, e);
        }
    }

    private URI buildUri(String url, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(builder::queryParam);
        return builder.build().encode().toUri();
    }
}

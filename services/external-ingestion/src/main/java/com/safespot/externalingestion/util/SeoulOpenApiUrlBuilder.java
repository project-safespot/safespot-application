package com.safespot.externalingestion.util;

/**
 * 서울 열린데이터광장 OpenAPI URL 빌더.
 * URL 형식: http://openapi.seoul.go.kr:8088/{KEY}/json/{service}/{start}/{end}
 * API 인증키는 path segment으로 전달하며 query param KEY/Type/pIndex/pSize는 사용하지 않는다.
 */
public final class SeoulOpenApiUrlBuilder {

    static final String KEY_PLACEHOLDER = "{KEY}";

    private SeoulOpenApiUrlBuilder() {}

    /**
     * templateUrl 내 {@code {KEY}} placeholder를 actualKey로 치환한 URL을 반환한다.
     *
     * @throws IllegalArgumentException templateUrl이 null이거나 {@code {KEY}}를 포함하지 않는 경우
     */
    public static String buildUrl(String templateUrl, String actualKey) {
        if (templateUrl == null || !templateUrl.contains(KEY_PLACEHOLDER)) {
            throw new IllegalArgumentException(
                "Seoul OpenAPI URL must contain {KEY} placeholder: " + templateUrl);
        }
        return templateUrl.replace(KEY_PLACEHOLDER, actualKey);
    }
}

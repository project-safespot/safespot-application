package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.dto.ApiResponse;
import com.safespot.apipublicread.dto.DisasterCacheWarmupRequest;
import com.safespot.apipublicread.service.DisasterCacheWarmupService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// TODO: /internal/test/cache-warmup/** 경로는 k8s Ingress / CloudFront level에서
//       외부 트래픽이 도달하지 않도록 차단해야 한다. X-Warmup-Secret은 보조 방어선이다.
//       k8s-manifest Ingress annotation: nginx.ingress.kubernetes.io/whitelist-source-range 또는
//       path prefix block 설정이 필요하다.
@RestController
@ConditionalOnProperty(name = "safespot.warmup.enabled", havingValue = "true")
@RequiredArgsConstructor
public class InternalCacheWarmupController {

    @Value("${safespot.warmup.secret:}")
    private String warmupSecret;

    private final DisasterCacheWarmupService disasterCacheWarmupService;

    @PostMapping("/internal/test/cache-warmup/disasters")
    public ResponseEntity<ApiResponse<Void>> warmupDisasters(
            @RequestHeader(value = "X-Warmup-Secret", required = false) String secret,
            @RequestBody DisasterCacheWarmupRequest request) {
        if (warmupSecret.isBlank() || !warmupSecret.equals(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("warmup.unauthorized", "X-Warmup-Secret 헤더가 올바르지 않습니다."));
        }
        boolean published = disasterCacheWarmupService.requestWarmup(request);
        if (!published) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("warmup.suppressed", "Warmup suppressed: retry after suppress window expires"));
        }
        return ResponseEntity.accepted().body(ApiResponse.ok(null));
    }
}

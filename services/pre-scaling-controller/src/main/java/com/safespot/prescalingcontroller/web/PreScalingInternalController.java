package com.safespot.prescalingcontroller.web;

import com.safespot.prescalingcontroller.service.PreScalingRestoreService;
import com.safespot.prescalingcontroller.service.RestoreNormalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/pre-scaling")
@RequiredArgsConstructor
public class PreScalingInternalController {

    private final PreScalingRestoreService restoreService;

    @PostMapping("/restore-normal")
    public ResponseEntity<RestoreNormalResult> restoreNormal() {
        RestoreNormalResult result = restoreService.restoreNormal();
        return ResponseEntity.status(result.success() ? HttpStatus.OK : HttpStatus.CONFLICT).body(result);
    }
}

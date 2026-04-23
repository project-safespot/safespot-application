package com.safespot.apicore.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ExitEntryRequest {

    @Size(max = 200, message = "VALIDATION_ERROR")
    private String reason;
}

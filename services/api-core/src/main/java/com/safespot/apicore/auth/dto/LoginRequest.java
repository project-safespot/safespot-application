package com.safespot.apicore.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LoginRequest {

    @NotBlank(message = "MISSING_REQUIRED_FIELD")
    @Size(min = 1, max = 50, message = "VALIDATION_ERROR")
    private String loginId;

    @NotBlank(message = "MISSING_REQUIRED_FIELD")
    @Size(min = 1, max = 100, message = "VALIDATION_ERROR")
    private String password;
}

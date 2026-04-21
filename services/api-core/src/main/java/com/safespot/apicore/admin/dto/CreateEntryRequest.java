package com.safespot.apicore.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CreateEntryRequest {

    @NotNull(message = "MISSING_REQUIRED_FIELD")
    private Long shelterId;

    private Long alertId;
    private Long userId;

    @NotBlank(message = "MISSING_REQUIRED_FIELD")
    @Size(min = 1, max = 50, message = "VALIDATION_ERROR")
    private String name;

    @Pattern(regexp = "^[0-9]{10,11}$", message = "VALIDATION_ERROR")
    private String phoneNumber;

    @Size(max = 255, message = "VALIDATION_ERROR")
    private String address;

    @Size(max = 100, message = "VALIDATION_ERROR")
    private String familyInfo;

    private String healthStatus;

    private Boolean specialProtectionFlag;

    @Size(max = 500, message = "VALIDATION_ERROR")
    private String note;
}

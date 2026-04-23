package com.safespot.apicore.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateEntryRequest {

    @Size(max = 255, message = "VALIDATION_ERROR")
    private String address;

    @Size(max = 100, message = "VALIDATION_ERROR")
    private String familyInfo;

    private String healthStatus;

    private Boolean specialProtectionFlag;

    @Size(max = 500, message = "VALIDATION_ERROR")
    private String note;

    @Size(max = 200, message = "VALIDATION_ERROR")
    private String reason;
}

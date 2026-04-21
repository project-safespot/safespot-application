package com.safespot.apicore.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateShelterRequest {

    @Min(value = 1, message = "VALIDATION_ERROR")
    private Integer capacityTotal;

    private String shelterStatus;

    @Size(max = 50, message = "VALIDATION_ERROR")
    private String manager;

    @Size(max = 50, message = "VALIDATION_ERROR")
    private String contact;

    @Size(max = 500, message = "VALIDATION_ERROR")
    private String note;

    @NotBlank(message = "MISSING_REQUIRED_FIELD")
    @Size(max = 200, message = "VALIDATION_ERROR")
    private String reason;
}

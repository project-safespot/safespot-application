package com.safespot.apipublicread.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DisasterAlertPageResponse {

    private final List<DisasterAlertItem> items;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean hasNext;
}

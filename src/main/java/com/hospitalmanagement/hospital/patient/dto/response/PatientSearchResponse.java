package com.hospitalmanagement.hospital.patient.dto.response;

import java.util.List;

public record PatientSearchResponse(
        List<PatientSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public PatientSearchResponse {
        items = List.copyOf(items);
    }
}

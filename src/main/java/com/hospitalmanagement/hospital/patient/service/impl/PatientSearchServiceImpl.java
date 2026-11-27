package com.hospitalmanagement.hospital.patient.service.impl;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.patient.dto.request.PatientSearchRequest;
import com.hospitalmanagement.hospital.patient.dto.response.PatientSearchResponse;
import com.hospitalmanagement.hospital.patient.dto.response.PatientSummaryResponse;
import com.hospitalmanagement.hospital.patient.entity.MedicalRecordAccessLog;
import com.hospitalmanagement.hospital.patient.entity.Patient;
import com.hospitalmanagement.hospital.patient.enums.AuditActorType;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOperation;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOutcome;
import com.hospitalmanagement.hospital.patient.repository.MedicalRecordAccessLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientSpecifications;
import com.hospitalmanagement.hospital.patient.service.PatientSearchService;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
@Slf4j
public class PatientSearchServiceImpl implements PatientSearchService {
    private final PatientRepository patientRepository;
    private final MedicalRecordAccessLogRepository accessLogRepository;

    @Override
    @Transactional
    public PatientSearchResponse searchPatients(
            PatientSearchRequest request,
            String authenticatedSubject
    ) {
        log.atInfo()
                .addKeyValue("event", "patient.search.started")
                .addKeyValue("stage", "validation")
                .log("Patient search started");

        UUID actorUserId = parseActorUserId(authenticatedSubject);

        validateSearchRequest(request);

        PageRequest pageable = createPageRequest(request);

        log.atInfo()
                .addKeyValue("event", "patient.search.query.started")
                .addKeyValue("stage", "query")
                .addKeyValue("search.filters", filterTypes(request))
                .addKeyValue("search.page", request.page())
                .addKeyValue("search.size", request.size())
                .addKeyValue("search.sort", request.sort())
                .log("Patient search query started");

        Page<@NonNull PatientSummaryResponse> page = searchPatientPage(request, pageable);

        persistAccessLogs(page, actorUserId);

        PatientSearchResponse response = new PatientSearchResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );

        logAfterCommit(request, response);

        return response;
    }

    private BusinessException rejectedRequest(ErrorCode errorCode) {
        log.atWarn()
                .addKeyValue("event", "patient.search.rejected")
                .addKeyValue("stage", "validation")
                .addKeyValue("errorCode", errorCode.name())
                .log("Patient search rejected");

        return new BusinessException(errorCode);
    }

    private UUID parseActorUserId(String authenticatedSubject) {
        try {
            return UUID.fromString(authenticatedSubject);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw rejectedRequest(ErrorCode.INVALID_ACTOR_ID);
        }
    }

    private void validateSearchRequest(
            PatientSearchRequest patientSearchRequest
    ) {
        if (patientSearchRequest.medicalRecordNumber() == null
                && patientSearchRequest.name() == null
                && patientSearchRequest.dateOfBirth() == null
        ) {
            throw rejectedRequest(ErrorCode.PATIENT_SEARCH_FILTER_REQUIRED);
        }

        long searchCount = (long) patientSearchRequest.page() * patientSearchRequest.size();

        if (searchCount > Integer.MAX_VALUE) {
            throw rejectedRequest(ErrorCode.PATIENT_SEARCH_PAGE_OUT_OF_RANGE);
        }
    }

    private PageRequest createPageRequest(
            PatientSearchRequest patientSearchRequest
    ) {
        String[] sort = patientSearchRequest.sort().split(",");

        return PageRequest.of(
                patientSearchRequest.page(),
                patientSearchRequest.size(),
                Sort.by(Sort.Direction.fromString(sort[1]), sort[0])
                        .and(Sort.by(Patient.Fields.id))
        );
    }

    private List<String> filterTypes(PatientSearchRequest request) {
        List<String> filters = new ArrayList<>();

        if (request.medicalRecordNumber() != null) {
            filters.add("medicalRecordNumber");
        }

        if (request.name() != null) {
            filters.add("name");
        }

        if (request.dateOfBirth() != null) {
            filters.add("dateOfBirth");
        }

        return List.copyOf(filters);
    }

    private Page<@NonNull PatientSummaryResponse> searchPatientPage(
            PatientSearchRequest patientSearchRequest,
            PageRequest pageable
    ) {
        try {
            Page<@NonNull PatientSummaryResponse> page = patientRepository.findBy(
                    PatientSpecifications.matching(patientSearchRequest),
                    query -> query
                            .as(PatientSummaryResponse.class)
                            .page(pageable)
            );

            log.atInfo()
                    .addKeyValue("event", "patient.search.query.completed")
                    .addKeyValue("stage", "query")
                    .addKeyValue("resultCount", page.getNumberOfElements())
                    .addKeyValue("search.total_elements", page.getTotalElements())
                    .addKeyValue("search.total_pages", page.getTotalPages())
                    .log("Patient search query completed");

            return page;
        } catch (DataAccessException exception) {
            log.atError()
                    .addKeyValue("event", "patient.search.query.failed")
                    .addKeyValue("stage", "query")
                    .addKeyValue("error.type", exception.getClass().getName())
                    .log("Patient search query failed");

            throw exception;
        }
    }

    private void persistAccessLogs(
            Page<@NonNull PatientSummaryResponse> page,
            UUID actorUserId
    ) {
        if (!page.isEmpty()) {
            log.atInfo()
                    .addKeyValue("event", "patient.search.access_logs.started")
                    .addKeyValue("stage", "access_logging")
                    .addKeyValue("accessLogCount", page.getNumberOfElements())
                    .log("Patient access-log persistence started");

            List<MedicalRecordAccessLog> accessLogs = page.getContent()
                    .stream()
                    .map(patient -> MedicalRecordAccessLog.builder()
                            .patient(patientRepository.getReferenceById(patient.id()))
                            .actorUserId(actorUserId)
                            .actorType(AuditActorType.USER)
                            .operation(RecordAccessOperation.SEARCH)
                            .entityType("patients")
                            .entityId(patient.id())
                            .purposeCode("patient_lookup")
                            .outcome(RecordAccessOutcome.ALLOWED)
                            .build())
                    .toList();

            // Flush before returning; an audit failure must roll back the whole search.
            try {
                accessLogRepository.saveAllAndFlush(accessLogs);

                log.atInfo()
                        .addKeyValue("event", "patient.search.access_logs.flushed")
                        .addKeyValue("stage", "access_logging")
                        .addKeyValue("accessLogCount", accessLogs.size())
                        .log("Patient access logs flushed; awaiting transaction commit");
            } catch (DataAccessException exception) {
                log.atError()
                        .addKeyValue("event", "patient.search.access_logs.failed")
                        .addKeyValue("stage", "access_logging")
                        .addKeyValue("errorCode", ErrorCode.PATIENT_ACCESS_LOG_FAILED.name())
                        .addKeyValue("error.type", exception.getClass().getName())
                        .addKeyValue("accessLogCount", accessLogs.size())
                        .log("Patient access-log persistence failed");

                throw new BusinessException(ErrorCode.PATIENT_ACCESS_LOG_FAILED);
            }
        }
    }

    private void logAfterCommit(
            PatientSearchRequest request,
            PatientSearchResponse response
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.atInfo()
                    .addKeyValue("event", "patient.search.prepared")
                    .addKeyValue("stage", "response_prepared")
                    .addKeyValue("resultCount", response.items().size())
                    .log("Patient search prepared without a managed transaction");

            return;
        }

        // Capture only safe metadata, not the request, patient DTOs or actor identity.
        int page = response.page();
        int size = response.size();
        int resultCount = response.items().size();
        long totalElements = response.totalElements();
        int totalPages = response.totalPages();

        String sort = request.sort();

        List<String> filters = filterTypes(request);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.atInfo()
                        .addKeyValue("event", "patient.search.completed")
                        .addKeyValue("stage", "committed")
                        .addKeyValue("search.filters", filters)
                        .addKeyValue("search.page", page)
                        .addKeyValue("search.size", size)
                        .addKeyValue("search.sort", sort)
                        .addKeyValue("resultCount", resultCount)
                        .addKeyValue("search.total_elements", totalElements)
                        .addKeyValue("search.total_pages", totalPages)
                        .addKeyValue("accessLogCount", resultCount)
                        .log("Patient search completed");
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    log.atError()
                            .addKeyValue("event", "patient.search.transaction.failed")
                            .addKeyValue("stage", status == STATUS_ROLLED_BACK
                                    ? "transaction_rolled_back" : "transaction_unknown")
                            .log("Patient search transaction did not commit");
                }
            }
        });
    }
}

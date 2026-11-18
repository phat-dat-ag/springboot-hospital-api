package com.hospitalmanagement.hospital.patient.service;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.logging.StructuredJsonEncoder;
import com.hospitalmanagement.hospital.patient.dto.request.PatientSearchRequest;
import com.hospitalmanagement.hospital.patient.dto.response.PatientSummaryResponse;
import com.hospitalmanagement.hospital.patient.entity.Patient;
import com.hospitalmanagement.hospital.patient.repository.MedicalRecordAccessLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;
import com.hospitalmanagement.hospital.patient.service.impl.PatientSearchServiceImpl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PatientSearchLoggingTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private final PatientRepository patients = mock(PatientRepository.class);
    private final MedicalRecordAccessLogRepository accessLogs =
            mock(MedicalRecordAccessLogRepository.class);
    private final PatientSearchServiceImpl service =
            new PatientSearchServiceImpl(patients, accessLogs);
    private final Logger logger = (Logger) LoggerFactory.getLogger(PatientSearchServiceImpl.class);
    private final StructuredJsonEncoder encoder = new StructuredJsonEncoder();
    private final ObjectMapper mapper = new ObjectMapper();
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;
    private boolean previousAdditive;
    private Map<String, String> previousMdc;

    @BeforeEach
    void captureStructuredEvents() {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        previousMdc = MDC.getCopyOfContextMap();

        MDC.clear();
        MDC.put("correlationId", "search-logging-test");
        MDC.put("traceId", "0123456789abcdef0123456789abcdef");

        appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();

        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);

        encoder.setContext(logger.getLoggerContext());
        encoder.start();
    }

    @AfterEach
    void restoreLoggingAndTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        encoder.stop();

        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);

        appender.stop();

        MDC.clear();

        if (previousMdc != null) {
            MDC.setContextMap(previousMdc);
        }
    }

    @Test
    void recordsStagesAndLogsSuccessOnlyAfterCommitWithoutPrivateValues() throws Exception {
        pageWithPatient();

        TransactionSynchronizationManager.initSynchronization();

        service.searchPatients(request(), ACTOR.toString());

        assertThat(eventNames()).containsExactly(
                "patient.search.started",
                "patient.search.query.started",
                "patient.search.query.completed",
                "patient.search.access_logs.started",
                "patient.search.access_logs.flushed"
        );
        assertThat(appender.list)
                .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()
        ) {
            synchronization.afterCommit();
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }

        JsonNode completed = json(appender.list.get(appender.list.size() - 1));

        assertThat(completed.path("event").asText()).isEqualTo("patient.search.completed");
        assertThat(completed.path("stage").asText()).isEqualTo("committed");
        assertThat(completed.path("level").asText()).isEqualTo("INFO");
        assertThat(completed.path("resultCount").asInt()).isEqualTo(1);
        assertThat(completed.path("accessLogCount").asInt()).isEqualTo(1);
        assertThat(completed.path("search.page").isIntegralNumber()).isTrue();
        assertThat(completed.path("search.size").asInt()).isEqualTo(20);
        assertThat(completed.path("search.total_elements").asLong()).isEqualTo(1);
        assertThat(completed.path("search.filters").toString())
                .isEqualTo("[\"medicalRecordNumber\",\"name\",\"dateOfBirth\"]");
        assertThat(completed.path("correlationId").asText()).isEqualTo("search-logging-test");
        assertThat(completed.path("traceId").asText())
                .isEqualTo("0123456789abcdef0123456789abcdef");
        assertPrivateValuesExcluded();
    }

    @Test
    void rollbackDoesNotProduceACompletedEvent() throws Exception {
        pageWithPatient();

        TransactionSynchronizationManager.initSynchronization();

        service.searchPatients(request(), ACTOR.toString());

        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(eventNames()).doesNotContain("patient.search.completed");
        JsonNode failure = json(appender.list.get(appender.list.size() - 1));
        assertThat(failure.path("event").asText()).isEqualTo("patient.search.transaction.failed");
        assertThat(failure.path("stage").asText()).isEqualTo("transaction_rolled_back");
        assertThat(failure.has("outcome")).isFalse();
        assertThat(failure.path("level").asText()).isEqualTo("ERROR");
    }

    @Test
    void emptyResultsCompleteWithZeroAccessEvents() throws Exception {
        givenPage(Page.empty(PageRequest.of(0, 20)));

        TransactionSynchronizationManager.initSynchronization();

        service.searchPatients(request(), ACTOR.toString());

        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verifyNoInteractions(accessLogs);

        assertThat(eventNames()).doesNotContain("patient.search.access_logs.started");

        JsonNode completed = json(appender.list.get(appender.list.size() - 1));

        assertThat(completed.path("event").asText()).isEqualTo("patient.search.completed");
        assertThat(completed.path("resultCount").asInt()).isZero();
        assertThat(completed.path("accessLogCount").asInt()).isZero();
    }

    @Test
    void invalidActorIsWarnedWithoutLoggingTheSubject() throws Exception {
        assertThatThrownBy(() -> service.searchPatients(request(), "private-invalid-subject"))
                .isInstanceOf(BusinessException.class);

        JsonNode rejected = json(appender.list.get(appender.list.size() - 1));

        assertThat(rejected.path("event").asText()).isEqualTo("patient.search.rejected");
        assertThat(rejected.path("errorCode").asText()).isEqualTo("INVALID_ACTOR_ID");
        assertThat(rejected.path("level").asText()).isEqualTo("WARN");
        assertThat(rejected.toString()).doesNotContain("private-invalid-subject");

        verifyNoInteractions(patients, accessLogs);
    }

    @Test
    void accessLogFailureHasSafeTypeAndCodeAndNoSuccessEvent() throws Exception {
        pageWithPatient();

        doThrow(new DataAccessResourceFailureException("private-db-error"))
                .when(accessLogs).saveAllAndFlush(any());

        assertThatThrownBy(() -> service.searchPatients(request(), ACTOR.toString()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PATIENT_ACCESS_LOG_FAILED)
                );

        assertThat(eventNames()).doesNotContain("patient.search.completed");

        JsonNode failed = json(appender.list.get(appender.list.size() - 1));

        assertThat(failed.path("event").asText()).isEqualTo("patient.search.access_logs.failed");
        assertThat(failed.path("errorCode").asText()).isEqualTo("PATIENT_ACCESS_LOG_FAILED");
        assertThat(failed.path("error.type").asText())
                .isEqualTo(DataAccessResourceFailureException.class.getName());
        assertThat(failed.has("stack_trace")).isFalse();
        assertPrivateValuesExcluded();
    }

    @Test
    void queryFailureLogsTheStageWithoutTheDatabaseMessage() throws Exception {
        failQuery();

        assertThatThrownBy(() -> service.searchPatients(request(), ACTOR.toString()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(eventNames()).doesNotContain("patient.search.completed");

        JsonNode failed = json(appender.list.get(appender.list.size() - 1));

        assertThat(failed.path("event").asText()).isEqualTo("patient.search.query.failed");
        assertThat(failed.path("stage").asText()).isEqualTo("query");
        assertPrivateValuesExcluded();

        verifyNoInteractions(accessLogs);
    }

    private void pageWithPatient() {
        var summary = new PatientSummaryResponse(
                PATIENT,
                "private-mrn",
                "private-name",
                "private-family",
                LocalDate.of(1995, 6, 15)
        );

        givenPage(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));
        given(patients.getReferenceById(PATIENT)).willReturn(mock(Patient.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenPage(Page<PatientSummaryResponse> page) {
        doReturn(page).when(patients).findBy(any(Specification.class), any(Function.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void failQuery() {
        doThrow(new DataAccessResourceFailureException("private-db-error"))
                .when(patients).findBy(any(Specification.class), any(Function.class));
    }

    private PatientSearchRequest request() {
        return new PatientSearchRequest(
                "private-mrn",
                "private-name",
                LocalDate.of(1995, 6, 15),
                0,
                20,
                "createdAt,desc"
        );
    }

    private JsonNode json(ILoggingEvent event) throws Exception {
        return mapper.readTree(encoder.encode(event));
    }

    private List<String> eventNames() {
        return appender.list.stream()
                .map(event -> event.getKeyValuePairs().stream()
                        .filter(pair -> "event".equals(pair.key))
                        .findFirst()
                        .orElseThrow()
                        .value.toString())
                .toList();
    }

    private void assertPrivateValuesExcluded() throws Exception {
        for (ILoggingEvent event : appender.list) {
            assertThat(json(event).toString()).doesNotContain(
                    "private-mrn",
                    "private-name",
                    "private-family",
                    "private-db-error",
                    "1995-06-15",
                    ACTOR.toString(),
                    PATIENT.toString()
            );
        }
    }
}

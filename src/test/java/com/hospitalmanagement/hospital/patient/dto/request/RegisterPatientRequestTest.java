package com.hospitalmanagement.hospital.patient.dto.request;

import com.hospitalmanagement.hospital.patient.enums.ContactType;
import com.hospitalmanagement.hospital.patient.enums.SexAtBirth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterPatientRequestTest {
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void configureValidation() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-11-17T12:00:00Z"),
                ZoneOffset.UTC
        );
        validatorFactory = Validation.byDefaultProvider()
                .configure()
                .clockProvider(() -> clock)
                .buildValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidation() {
        validatorFactory.close();
    }

    @Test
    void deserializesDocumentedRegistrationIncludingPrimaryContact() {
        RegisterPatientRequest request = JsonMapper.builder()
                .build()
                .readValue("""
                        {
                          "medicalRecordNumber": " LEARN-0001 ",
                          "givenName": "An",
                          "familyName": "Example",
                          "dateOfBirth": "1995-06-15",
                          "sexAtBirth": "female",
                          "contacts": [{
                            "contactType": "self",
                            "phone": "+66000000000",
                            "isPrimary": true
                          }]
                        }
                        """, RegisterPatientRequest.class);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.medicalRecordNumber()).isEqualTo("LEARN-0001");
        assertThat(request.dateOfBirth()).isEqualTo(LocalDate.of(1995, 6, 15));
        assertThat(request.sexAtBirth()).isEqualTo(SexAtBirth.FEMALE);
        assertThat(request.contacts().get(0).isPrimary()).isTrue();
    }

    @Test
    void acceptsOmittedOptionalFieldsAndContacts() {
        RegisterPatientRequest request = JsonMapper.builder()
                .build()
                .readValue("""
                        {"medicalRecordNumber": "LEARN-0002", "givenName": "An"}
                        """, RegisterPatientRequest.class);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.contacts()).isEmpty();
    }

    @Test
    void defaultsOmittedPrimaryContactFlagToFalse() {
        RegisterPatientRequest request = JsonMapper.builder()
                .build()
                .readValue("""
                        {"medicalRecordNumber":"LEARN-0004","givenName":"An",
                         "contacts":[{"contactType":"self","phone":"test-phone"}]}
                        """, RegisterPatientRequest.class);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.contacts().get(0).isPrimary()).isFalse();
    }

    @Test
    void rejectsUnknownEnumValuesDuringDeserialization() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertThatThrownBy(() -> mapper.readValue("""
                {"medicalRecordNumber":"LEARN-0005","givenName":"An",
                 "sexAtBirth":"invalid"}
                """, RegisterPatientRequest.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue("""
                {"medicalRecordNumber":"LEARN-0005","givenName":"An",
                 "contacts":[{"contactType":"invalid","phone":"test-phone"}]}
                """, RegisterPatientRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void reportsInvalidDemographicsAsFieldViolations() {
        RegisterPatientRequest request = new RegisterPatientRequest(
                " ",
                " ",
                "x".repeat(101),
                LocalDate.of(2026, 11, 18),
                null,
                null
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "medicalRecordNumber",
                        "givenName",
                        "familyName",
                        "dateOfBirth"
                );
        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains(
                        "Medical record number is required",
                        "Given name is required",
                        "Family name must not exceed 100 characters",
                        "Date of birth must not be in the future"
                );
    }

    @Test
    void cascadesValidationIntoContactFields() {
        ContactRequest contact = new ContactRequest(
                null,
                null,
                null,
                "x".repeat(41),
                "invalid-email",
                null,
                false
        );

        RegisterPatientRequest request = registrationWithContacts(List.of(contact));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "contacts[0].contactType",
                        "contacts[0].phone",
                        "contacts[0].email"
                );
        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains(
                        "Contact type is required",
                        "Phone must not exceed 40 characters",
                        "Email must be a valid email address"
                );
    }

    @Test
    void rejectsNullContactEntriesThroughValidation() {
        RegisterPatientRequest request = registrationWithContacts(
                Collections.singletonList(null)
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("contacts[0].<list element>");
        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("Contact must not be null");
    }

    @Test
    void acceptsFiveContactsAndRejectsSix() {
        ContactRequest contact = new ContactRequest(
                ContactType.SELF,
                null,
                null,
                "+66000000000",
                null,
                null,
                false
        );

        RegisterPatientRequest fiveContacts = registrationWithContacts(
                Collections.nCopies(5, contact)
        );

        RegisterPatientRequest sixContacts = registrationWithContacts(
                Collections.nCopies(6, contact)
        );

        assertThat(validator.validate(fiveContacts)).isEmpty();
        assertThat(validator.validate(sixContacts))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("contacts");
        assertThat(validator.validate(sixContacts))
                .extracting(violation -> violation.getMessage())
                .contains("At most 5 contacts are allowed");
    }

    @Test
    void keepsContactListImmutable() {
        RegisterPatientRequest request = registrationWithContacts(List.of());

        assertThatThrownBy(() -> request.contacts().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static RegisterPatientRequest registrationWithContacts(
            List<ContactRequest> contacts
    ) {
        return new RegisterPatientRequest(
                "LEARN-0003",
                "An",
                null,
                null,
                null,
                contacts
        );
    }
}

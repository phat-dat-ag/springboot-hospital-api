-- Executed in a rolled-back transaction, never as a Flyway migration.
CREATE FUNCTION pg_temp.expect_failure(sql_text text, expected_state text)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    actual_state text;
BEGIN
    BEGIN
        EXECUTE sql_text;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = expected_state THEN
            RETURN;
        END IF;
        RAISE EXCEPTION 'Expected SQLSTATE %, got % for %', expected_state, actual_state, sql_text;
    END;
    RAISE EXCEPTION 'Expected failure for %', sql_text;
END;
$$;

DO $$
DECLARE
    patient uuid;
    other_patient uuid;
    doctor uuid;
    encounter uuid;
    diagnosis_code uuid;
    second_code uuid;
    diagnosis uuid;
    category uuid;
    lab_test uuid;
    lab_order uuid;
    lab_item uuid;
    lab_result uuid;
    medication uuid;
    prescription uuid;
    note uuid;
    original_created timestamptz;
    original_updated timestamptz;
    record_id uuid;
    table_name text;
BEGIN
    INSERT INTO patients(medical_record_number, given_name)
        VALUES ('SCHEMA-1', 'Schema test')
        RETURNING id, created_at, updated_at INTO patient, original_created, original_updated;
    IF patient IS NULL OR original_created IS NULL OR original_updated IS NULL THEN
        RAISE EXCEPTION 'Missing UUID/timestamp defaults';
    END IF;
    UPDATE patients SET given_name = 'Updated', created_at = '2000-01-01',
        updated_at = '2000-01-01' WHERE id = patient;
    IF NOT EXISTS (SELECT 1 FROM patients WHERE id = patient
        AND created_at = original_created AND updated_at > original_updated) THEN
        RAISE EXCEPTION 'Timestamp trigger failed';
    END IF;
    PERFORM pg_temp.expect_failure(
        'INSERT INTO patients(medical_record_number,given_name) VALUES (''SCHEMA-1'',''Duplicate'')',
        '23505');
    UPDATE patients SET deleted_at = now() WHERE id = patient;
    PERFORM pg_temp.expect_failure(
        'INSERT INTO patients(medical_record_number,given_name) VALUES (''SCHEMA-1'',''Duplicate'')',
        '23505');
    INSERT INTO patients(medical_record_number,given_name) VALUES ('SCHEMA-2','Other')
        RETURNING id INTO other_patient;
    PERFORM pg_temp.expect_failure(
        'INSERT INTO patients(medical_record_number,given_name,date_of_birth,deceased_on)
         VALUES (''BAD-DATES'',''Test'',''2020-01-01'',''2019-01-01'')', '23514');
    PERFORM pg_temp.expect_failure(
        'INSERT INTO patients(medical_record_number,given_name,created_at)
         VALUES (''BAD-TIME'',''Test'',''infinity'')', '23514');

    INSERT INTO doctors(license_authority,license_number,given_name)
        VALUES ('SCHEMA','LICENSE-1','Test') RETURNING id INTO doctor;
    PERFORM pg_temp.expect_failure(
        'INSERT INTO doctors(license_authority,license_number,given_name)
         VALUES (''SCHEMA'',''LICENSE-1'',''Duplicate'')', '23505');
    INSERT INTO specialties(code,name) VALUES ('TEST','Test') RETURNING id INTO record_id;
    PERFORM pg_temp.expect_failure(
        'INSERT INTO specialties(code,name) VALUES (''TEST'',''Duplicate'')', '23505');
    UPDATE specialties SET deleted_at = now() WHERE id = record_id;
    INSERT INTO specialties(code,name) VALUES ('TEST','Replacement') RETURNING id INTO category;
    INSERT INTO doctor_specialties(doctor_id,specialty_id,is_primary) VALUES (doctor,category,true);
    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO doctor_specialties(doctor_id,specialty_id,is_primary) VALUES (%L,%L,true)',
        doctor,record_id), '23505');
    INSERT INTO patient_contacts(patient_id,contact_type,phone,is_primary)
        VALUES (patient,'self','test',true) RETURNING id INTO record_id;
    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO patient_contacts(patient_id,contact_type,phone,is_primary)
         VALUES (%L,''self'',''test'',true)',patient), '23505');
    UPDATE patient_contacts SET deleted_at = now() WHERE id = record_id;
    INSERT INTO patient_contacts(patient_id,contact_type,phone,is_primary)
        VALUES (patient,'self','test',true);

    INSERT INTO medical_encounters(encounter_number,patient_id,doctor_id)
        VALUES ('SCHEMA-1',patient,doctor) RETURNING id INTO encounter;
    PERFORM pg_temp.expect_failure(
        'INSERT INTO medical_encounters(encounter_number,patient_id)
         VALUES (''ORPHAN'',gen_random_uuid())', '23503');
    PERFORM pg_temp.expect_failure(format(
        'UPDATE medical_encounters SET status = ''invalid'' WHERE id = %L',encounter), '23514');
    PERFORM pg_temp.expect_failure(format(
        'UPDATE medical_encounters SET status = ''completed'' WHERE id = %L',encounter), '23514');
    INSERT INTO vital_signs(encounter_id,heart_rate_bpm) VALUES (encounter,75),(encounter,80);
    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO vital_signs(encounter_id,oxygen_saturation_pct) VALUES (%L,101)',encounter),
        '23514');
    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO vital_signs(encounter_id,weight_kg) VALUES (%L,-1)',encounter), '23514');
    INSERT INTO clinical_notes(encounter_id,author_doctor_id,note_type,content)
        VALUES (encounter,doctor,'assessment','Test') RETURNING id INTO note;
    UPDATE clinical_notes SET status = 'signed', signed_at = clock_timestamp() WHERE id = note;
    PERFORM pg_temp.expect_failure(format(
        'UPDATE clinical_notes SET content = ''Altered'' WHERE id = %L',note), '23514');
    UPDATE clinical_notes SET status = 'entered_in_error',correction_reason = 'Test correction'
        WHERE id = note;

    INSERT INTO diagnosis_codes(coding_system,system_version,code,description)
        VALUES ('TEST','1','A','Test A') RETURNING id INTO diagnosis_code;
    INSERT INTO diagnosis_codes(coding_system,system_version,code,description)
        VALUES ('TEST','1','B','Test B') RETURNING id INTO second_code;
    INSERT INTO encounter_diagnoses(encounter_id,diagnosis_code_id,diagnosing_doctor_id,is_primary)
        VALUES (encounter,diagnosis_code,doctor,true) RETURNING id INTO diagnosis;
    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO encounter_diagnoses(encounter_id,diagnosis_code_id,diagnosing_doctor_id,is_primary)
         VALUES (%L,%L,%L,true)',encounter,second_code,doctor), '23505');
    UPDATE encounter_diagnoses SET status = 'superseded' WHERE id = diagnosis;
    INSERT INTO encounter_diagnoses(encounter_id,diagnosis_code_id,diagnosing_doctor_id,is_primary)
        VALUES (encounter,second_code,doctor,true);

    INSERT INTO lab_test_categories(code,name) VALUES ('TEST','Test') RETURNING id INTO category;
    INSERT INTO lab_tests(category_id,code,name) VALUES (category,'PANEL','Test panel')
        RETURNING id INTO lab_test;
    INSERT INTO lab_orders(order_number,encounter_id,ordering_doctor_id)
        VALUES ('TEST',encounter,doctor) RETURNING id INTO lab_order;
    INSERT INTO lab_order_items(lab_order_id,lab_test_id,item_number)
        VALUES (lab_order,lab_test,1) RETURNING id INTO lab_item;
    INSERT INTO lab_results(lab_order_item_id,component_code,component_name,numeric_value,
        status,observed_at,verified_at,verified_by_user_id)
        VALUES (lab_item,'A','A',1,'verified',now(),now(),gen_random_uuid())
        RETURNING id INTO lab_result;
    PERFORM pg_temp.expect_failure(format(
        'UPDATE lab_results SET numeric_value = 2 WHERE id = %L',lab_result), '23514');
    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO lab_results(lab_order_item_id,component_code,component_name,revision)
         VALUES (%L,''A'',''Duplicate current'',2)',lab_item), '23505');
    UPDATE lab_results SET status = 'superseded',correction_reason = 'Test revision'
        WHERE id = lab_result;
    INSERT INTO lab_results(lab_order_item_id,component_code,component_name,revision,
        supersedes_result_id,correction_reason,text_value,status,observed_at)
        VALUES (lab_item,'A','A',2,lab_result,'Test revision','Negative','preliminary',now());
    INSERT INTO lab_results(lab_order_item_id,component_code,component_name,text_value,status,observed_at)
        VALUES (lab_item,'B','Other component','Negative','preliminary',now());
    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO lab_results(lab_order_item_id,component_code,component_name,status)
         VALUES (%L,''C'',''Unverified'',''verified'')',lab_item), '23514');
    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO lab_results(lab_order_item_id,component_code,component_name,numeric_value,text_value)
         VALUES (%L,''C'',''Ambiguous'',1,''Positive'')',lab_item), '23514');

    INSERT INTO medication_categories(code,name) VALUES ('TEST','Test') RETURNING id INTO category;
    INSERT INTO medications(category_id,code,generic_name,dosage_form,strength)
        VALUES (category,'TEST','Test','tablet','1 mg') RETURNING id INTO medication;
    INSERT INTO prescriptions(prescription_number,encounter_id,prescribing_doctor_id)
        VALUES ('TEST',encounter,doctor) RETURNING id INTO prescription;
    INSERT INTO prescription_items(prescription_id,medication_id,item_number,dosage,
        administration_route,frequency,quantity,quantity_unit,duration_days)
        VALUES (prescription,medication,1,'1 tablet','oral','daily',7,'tablets',7);
    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO prescription_items(prescription_id,medication_id,item_number,dosage,
         administration_route,frequency,quantity,quantity_unit,duration_days)
         VALUES (%L,%L,2,''1'',''oral'',''daily'',0,''tablets'',-1)',prescription,medication), '23514');
    UPDATE prescriptions SET status = 'signed',signed_at = clock_timestamp() WHERE id = prescription;
    PERFORM pg_temp.expect_failure(format(
        'UPDATE prescription_items SET quantity = 10 WHERE prescription_id = %L',prescription),
        '23514');
    PERFORM pg_temp.expect_failure(format(
        'UPDATE prescriptions SET status = ''draft'' WHERE id = %L',prescription), '23514');
    UPDATE prescriptions SET status = 'cancelled', reason = 'Test cancellation' WHERE id = prescription;

    PERFORM pg_temp.expect_failure(format(
        'INSERT INTO medical_attachments(patient_id,encounter_id,uploaded_by_user_id,
         file_name,media_type,storage_provider,storage_key,byte_size)
         VALUES (%L,%L,gen_random_uuid(),''test.pdf'',''application/pdf'',''test'',''key'',1)',
        other_patient,encounter), '23503');
    INSERT INTO medical_attachments(patient_id,encounter_id,uploaded_by_user_id,
        file_name,media_type,storage_provider,storage_key,byte_size)
        VALUES (patient,encounter,gen_random_uuid(),'test.pdf','application/pdf','test','key',1);
    UPDATE medical_encounters SET status = 'completed',started_at = now(),ended_at = now()
        WHERE id = encounter;
    PERFORM pg_temp.expect_failure(format(
        'UPDATE medical_encounters SET patient_id = %L WHERE id = %L',other_patient,encounter),
        '23514');
    UPDATE medical_encounters SET status = 'entered_in_error',correction_reason = 'Test correction'
        WHERE id = encounter;

    INSERT INTO encounter_status_histories(encounter_id,to_status) VALUES (encounter,'scheduled');
    INSERT INTO diagnosis_histories(encounter_diagnosis_id,new_code_id,new_status,is_primary,reason)
        VALUES (diagnosis,diagnosis_code,'active',true,'Test history');
    INSERT INTO prescription_histories(prescription_id,to_status,reason)
        VALUES (prescription,'draft','Test history');
    INSERT INTO clinical_audit_logs(patient_id,actor_type,operation,entity_type,entity_id)
        VALUES (patient,'system','create','patients',patient);
    INSERT INTO medical_record_access_logs(patient_id,actor_type,operation,purpose_code,outcome)
        VALUES (patient,'system','view','schema_test','allowed');
    FOREACH table_name IN ARRAY ARRAY['encounter_status_histories','diagnosis_histories',
        'prescription_histories','clinical_audit_logs','medical_record_access_logs'] LOOP
        PERFORM pg_temp.expect_failure(format('UPDATE %I SET occurred_at = now()',table_name), '23514');
        PERFORM pg_temp.expect_failure(format('DELETE FROM %I',table_name), '23514');
        PERFORM pg_temp.expect_failure(format('TRUNCATE %I CASCADE',table_name), '23514');
    END LOOP;
    PERFORM pg_temp.expect_failure(format('DELETE FROM patients WHERE id = %L',patient), '23514');
    PERFORM pg_temp.expect_failure(format('DELETE FROM medical_encounters WHERE id = %L',encounter), '23514');
END;
$$;

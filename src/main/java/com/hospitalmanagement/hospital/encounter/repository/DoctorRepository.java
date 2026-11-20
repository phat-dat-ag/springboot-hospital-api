package com.hospitalmanagement.hospital.encounter.repository;

import com.hospitalmanagement.hospital.encounter.entity.Doctor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface DoctorRepository extends JpaRepository<Doctor, UUID> {
    @Query("select d.id from Doctor d where d.externalUserId = :account and d.deletedAt is null")
    Optional<UUID> findActiveIdByAccount(@Param("account") UUID account);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select doctor from Doctor doctor where doctor.id = :id")
    Optional<Doctor> findByIdForUpdate(@Param("id") UUID id);
}


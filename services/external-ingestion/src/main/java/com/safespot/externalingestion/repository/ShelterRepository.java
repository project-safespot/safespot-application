package com.safespot.externalingestion.repository;

import com.safespot.externalingestion.domain.entity.Shelter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShelterRepository extends JpaRepository<Shelter, Long> {
    // TODO: shelter 자연키(외부 ID) 미정의 — name+address+disasterType으로 임시 식별
    Optional<Shelter> findByNameAndAddressAndDisasterType(String name, String address, String disasterType);
}

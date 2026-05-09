package com.safespot.scenariosimulator.repository;

import com.safespot.scenariosimulator.domain.entity.SimShelter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimShelterRepository extends JpaRepository<SimShelter, Long> {

    List<SimShelter> findByDisasterTypeAndShelterStatus(String disasterType, String shelterStatus);

    List<SimShelter> findByShelterStatus(String shelterStatus);
}

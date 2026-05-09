package com.safespot.scenariosimulator.repository;

import com.safespot.scenariosimulator.domain.entity.SimDisasterAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimDisasterAlertRepository extends JpaRepository<SimDisasterAlert, Long> {

    List<SimDisasterAlert> findBySource(String source);
}

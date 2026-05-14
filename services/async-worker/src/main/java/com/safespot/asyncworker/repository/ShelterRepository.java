package com.safespot.asyncworker.repository;

import java.util.List;
import java.util.Optional;

public interface ShelterRepository {

    Optional<ShelterInfo> findById(Long shelterId);

    List<ShelterInfo> findAllForStatusWarmup();

    List<ShelterInfo> findByIds(List<Long> shelterIds);
}

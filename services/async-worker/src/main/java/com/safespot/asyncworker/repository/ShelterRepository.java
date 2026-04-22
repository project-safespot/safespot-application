package com.safespot.asyncworker.repository;

import java.util.Optional;

public interface ShelterRepository {

    Optional<ShelterInfo> findById(Long shelterId);
}

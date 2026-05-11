package com.safespot.asyncworker.repository;

import java.util.Collection;
import java.util.Map;

public interface EvacuationEntryRepository {

    int countEntered(Long shelterId);

    Map<Long, Integer> countEnteredByShelterIds(Collection<Long> shelterIds);
}

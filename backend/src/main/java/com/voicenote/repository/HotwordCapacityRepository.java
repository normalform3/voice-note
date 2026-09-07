package com.voicenote.repository;

import com.voicenote.domain.HotwordCapacity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface HotwordCapacityRepository extends JpaRepository<HotwordCapacity, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select capacity from HotwordCapacity capacity where capacity.id = 1")
    Optional<HotwordCapacity> lockCapacity();
}

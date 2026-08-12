package com.voicenote.repository;

import com.voicenote.domain.MemoryIndexStatus;
import com.voicenote.domain.UserMemoryDeletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface UserMemoryDeletionRepository extends JpaRepository<UserMemoryDeletion, String> {
    List<UserMemoryDeletion> findTop10ByStatusOrderByUpdatedAtAsc(MemoryIndexStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from UserMemoryDeletion value where value.id = :id")
    Optional<UserMemoryDeletion> findByIdForUpdate(@Param("id") String id);
}

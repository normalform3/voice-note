package com.voicenote.repository;

import com.voicenote.domain.MemoryIndexStatus;
import com.voicenote.domain.UserMemoryVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserMemoryVersionRepository extends JpaRepository<UserMemoryVersion, String> {
    List<UserMemoryVersion> findByMemoryIdOrderByVersionNumberDesc(String memoryId);
    Optional<UserMemoryVersion> findTopByMemoryIdOrderByVersionNumberDesc(String memoryId);
    List<UserMemoryVersion> findTop10ByIndexStatusOrderByCreatedAtAsc(MemoryIndexStatus status);
    List<UserMemoryVersion> findByIdIn(Collection<String> ids);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from UserMemoryVersion value where value.id = :id")
    Optional<UserMemoryVersion> findByIdForUpdate(@Param("id") String id);
    void deleteByMemoryId(String memoryId);
}

package com.voicenote.repository;

import com.voicenote.domain.UserMemory;
import com.voicenote.domain.UserMemoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserMemoryRepository extends JpaRepository<UserMemory, String> {
    List<UserMemory> findByOwnerIdAndStatusOrderByUpdatedAtDesc(String ownerId, UserMemoryStatus status);
    List<UserMemory> findByIdInAndOwnerIdAndStatus(Collection<String> ids, String ownerId, UserMemoryStatus status);
    Optional<UserMemory> findByOwnerIdAndSemanticKey(String ownerId, String semanticKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from UserMemory value where value.ownerId = :ownerId and value.semanticKey = :semanticKey")
    Optional<UserMemory> findByOwnerIdAndSemanticKeyForUpdate(@Param("ownerId") String ownerId, @Param("semanticKey") String semanticKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from UserMemory value where value.id = :id")
    Optional<UserMemory> findByIdForUpdate(@Param("id") String id);
    long countByOwnerIdAndStatus(String ownerId, UserMemoryStatus status);
}

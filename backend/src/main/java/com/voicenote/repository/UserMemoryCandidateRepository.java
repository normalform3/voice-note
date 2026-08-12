package com.voicenote.repository;

import com.voicenote.domain.UserMemoryCandidate;
import com.voicenote.domain.UserMemoryCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserMemoryCandidateRepository extends JpaRepository<UserMemoryCandidate, String> {
    List<UserMemoryCandidate> findByOwnerIdAndStatusOrderByCreatedAtDesc(String ownerId, UserMemoryCandidateStatus status);
    Optional<UserMemoryCandidate> findByOwnerIdAndSourceTurnIdAndSemanticKey(String ownerId, String sourceTurnId, String semanticKey);
    Optional<UserMemoryCandidate> findFirstByOwnerIdAndSemanticKeyAndStatusOrderByCreatedAtDesc(String ownerId, String semanticKey, UserMemoryCandidateStatus status);
    long countByOwnerIdAndStatus(String ownerId, UserMemoryCandidateStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from UserMemoryCandidate value where value.id = :id")
    Optional<UserMemoryCandidate> findByIdForUpdate(@Param("id") String id);
    void deleteBySourceTurnIdInAndStatusNot(Collection<String> turnIds, UserMemoryCandidateStatus status);
    void deleteByTargetMemoryId(String memoryId);
}

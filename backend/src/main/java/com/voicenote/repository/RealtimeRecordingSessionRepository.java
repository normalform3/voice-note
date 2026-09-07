package com.voicenote.repository;

import com.voicenote.domain.RealtimeRecordingSession;
import com.voicenote.domain.RealtimeRecordingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RealtimeRecordingSessionRepository extends JpaRepository<RealtimeRecordingSession, String> {
    Optional<RealtimeRecordingSession> findByIdAndOwnerId(String id, String ownerId);
    List<RealtimeRecordingSession> findByTranscriptionTaskId(String transcriptionTaskId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from RealtimeRecordingSession session where session.id = :id and session.ownerId = :ownerId")
    Optional<RealtimeRecordingSession> findOwnedForUpdate(@Param("id") String id, @Param("ownerId") String ownerId);
    List<RealtimeRecordingSession> findTop100ByStatusInAndExpiresAtBefore(Collection<RealtimeRecordingStatus> statuses, Instant cutoff);
    List<RealtimeRecordingSession> findTop20ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(RealtimeRecordingStatus status, Instant cutoff);
    List<RealtimeRecordingSession> findTop20ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(Collection<RealtimeRecordingStatus> statuses, Instant cutoff);
    boolean existsByHotwordLibraryIdAndStatusIn(String hotwordLibraryId, Collection<RealtimeRecordingStatus> statuses);

    @Modifying
    @Transactional
    @Query(value = "UPDATE realtime_recording_sessions SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP(6), version = version + 1 " +
            "WHERE id = :id AND (status IN ('FINALIZING', 'FAILED') OR (status = 'PROCESSING' AND updated_at < :staleBefore))", nativeQuery = true)
    int claimFinalization(@Param("id") String id, @Param("staleBefore") Instant staleBefore);
}

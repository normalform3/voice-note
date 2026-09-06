package com.voicenote.repository;

import com.voicenote.domain.RealtimeRecordingPart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface RealtimeRecordingPartRepository extends JpaRepository<RealtimeRecordingPart, String> {
    Optional<RealtimeRecordingPart> findBySessionIdAndPartNumber(String sessionId, int partNumber);
    List<RealtimeRecordingPart> findBySessionIdOrderByPartNumber(String sessionId);
    @Transactional void deleteBySessionId(String sessionId);
}

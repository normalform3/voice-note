package com.echotrace.repository;

import com.echotrace.domain.AudioBlob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AudioBlobRepository extends JpaRepository<AudioBlob, String> {
    Optional<AudioBlob> findByOwnerIdAndSha256(String ownerId, String sha256);
}

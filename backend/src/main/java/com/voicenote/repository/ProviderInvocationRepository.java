package com.voicenote.repository;

import com.voicenote.domain.ProviderInvocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProviderInvocationRepository extends JpaRepository<ProviderInvocation, String> {
    Optional<ProviderInvocation> findByTaskAttemptIdAndInvocationType(String taskAttemptId, String invocationType);
}

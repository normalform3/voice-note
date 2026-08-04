package com.voicenote.repository;

import com.voicenote.domain.OrganizationInvocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OrganizationInvocationRepository extends JpaRepository<OrganizationInvocation, String> {
    Optional<OrganizationInvocation> findByOrganizedDocumentIdAndStageName(String documentId, String stageName);
    void deleteByOrganizedDocumentId(String documentId);
}

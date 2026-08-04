package com.voicenote.repository;

import com.voicenote.domain.OrganizedDocumentBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OrganizedDocumentBlockRepository extends JpaRepository<OrganizedDocumentBlock, String> {
    List<OrganizedDocumentBlock> findByOrganizedDocumentIdOrderByBlockIndex(String organizedDocumentId);
    void deleteByOrganizedDocumentId(String organizedDocumentId);
}

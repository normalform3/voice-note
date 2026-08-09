package com.voicenote.repository;

import com.voicenote.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SkillDefinitionRepository extends JpaRepository<SkillDefinition, String> {
    List<SkillDefinition> findBySourceAndStatusNotOrderByUpdatedAtDesc(SkillSource source, SkillStatus status);
    List<SkillDefinition> findByOwnerIdAndStatusNotOrderByUpdatedAtDesc(String ownerId, SkillStatus status);
    long countByOwnerIdAndSourceAndStatusNot(String ownerId, SkillSource source, SkillStatus status);
}

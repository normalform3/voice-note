package com.voicenote.repository;

import com.voicenote.domain.SkillVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SkillVersionRepository extends JpaRepository<SkillVersion, String> {
    Optional<SkillVersion> findBySkillDefinitionIdAndVersionName(String definitionId, String versionName);
    List<SkillVersion> findBySkillDefinitionIdOrderByVersionNumberDesc(String definitionId);
}

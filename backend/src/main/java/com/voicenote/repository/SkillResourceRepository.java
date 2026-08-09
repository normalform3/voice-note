package com.voicenote.repository;

import com.voicenote.domain.SkillResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface SkillResourceRepository extends JpaRepository<SkillResource, String> {
    List<SkillResource> findBySkillVersionIdOrderBySortOrderAsc(String skillVersionId);
    Optional<SkillResource> findByIdAndSkillVersionId(String id, String skillVersionId);
    @Modifying
    @Query("delete from SkillResource resource where resource.skillVersionId = :skillVersionId")
    void deleteBySkillVersionId(@Param("skillVersionId") String skillVersionId);
}

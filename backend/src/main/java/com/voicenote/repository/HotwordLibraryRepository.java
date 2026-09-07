package com.voicenote.repository;

import com.voicenote.domain.HotwordLibrary;
import com.voicenote.domain.HotwordLibraryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HotwordLibraryRepository extends JpaRepository<HotwordLibrary, String> {
    List<HotwordLibrary> findByOwnerIdAndStatusNotOrderByUpdatedAtDesc(String ownerId, HotwordLibraryStatus status);
    long countByStatusNot(HotwordLibraryStatus status);
    boolean existsByOwnerIdAndDisplayNameIgnoreCaseAndStatusNot(String ownerId, String displayName, HotwordLibraryStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select library from HotwordLibrary library where library.id = :id and library.ownerId = :ownerId")
    Optional<HotwordLibrary> findOwnedForUpdate(@Param("id") String id, @Param("ownerId") String ownerId);
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select library from HotwordLibrary library where library.id = :id and library.ownerId = :ownerId")
    Optional<HotwordLibrary> findOwnedForUse(@Param("id") String id, @Param("ownerId") String ownerId);
    List<HotwordLibrary> findTop10ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(Collection<HotwordLibraryStatus> statuses, Instant cutoff);
}

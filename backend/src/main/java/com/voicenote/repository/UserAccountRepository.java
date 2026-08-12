package com.voicenote.repository;

import com.voicenote.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {
    Optional<UserAccount> findByAccount(String account);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from UserAccount value where value.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") String id);
}

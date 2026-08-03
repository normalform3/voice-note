package com.voicenote.repository;

import com.voicenote.domain.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, String> { }

package com.echotrace.service;

import com.echotrace.domain.IdempotencyRecord;
import com.echotrace.repository.IdempotencyRecordRepository;
import com.echotrace.web.ApiException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {
    @Test
    void reusesSameKeyForSameRequestFingerprint() {
        IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
        IdempotencyRecord existing = new IdempotencyRecord("user", "CREATE_TASK", "key", "request-hash");
        when(repository.findByOwnerIdAndOperationNameAndIdempotencyKey("user", "CREATE_TASK", "key")).thenReturn(Optional.of(existing));
        assertThat(new IdempotencyService(repository).reserve("user", "CREATE_TASK", "key", "request-hash")).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsReusedKeyWithDifferentRequestFingerprint() {
        IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
        when(repository.findByOwnerIdAndOperationNameAndIdempotencyKey("user", "CREATE_TASK", "key"))
                .thenReturn(Optional.of(new IdempotencyRecord("user", "CREATE_TASK", "key", "first")));
        assertThatThrownBy(() -> new IdempotencyService(repository).reserve("user", "CREATE_TASK", "key", "second"))
                .isInstanceOf(ApiException.class).hasMessageContaining("different request");
    }
}

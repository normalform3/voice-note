package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.web.ApiException;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObjectStorageTest {
    @Test
    void reportsAnUnreachableMinioEndpoint() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        when(minio.bucketExists(any())).thenThrow(new IOException("Connection refused"));

        assertStorageError(minio, "OBJECT_STORAGE_UNREACHABLE", "Cannot reach MinIO");
    }

    @Test
    void reportsRejectedMinioCredentialsOrPermissions() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        when(minio.bucketExists(any())).thenThrow(errorResponse("AccessDenied", 403));

        assertStorageError(minio, "OBJECT_STORAGE_CREDENTIALS_REJECTED", "MinIO rejected");
    }

    @Test
    void reportsAnInvalidBucketConfiguration() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        when(minio.bucketExists(any())).thenThrow(new IllegalArgumentException("Invalid bucket name"));

        assertStorageError(minio, "OBJECT_STORAGE_CONFIGURATION_INVALID", "configuration is invalid");
    }

    private static void assertStorageError(MinioClient minio, String code, String message) {
        ObjectStorage storage = new ObjectStorage(minio, properties());

        assertThatThrownBy(() -> storage.put("owners/owner-a/audio/blob-a/source", new ByteArrayInputStream(new byte[] {1}), 1, "audio/mpeg"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(code);
                    assertThat(exception.getMessage()).contains(message);
                });
    }

    private static ErrorResponseException errorResponse(String code, int status) {
        ErrorResponse error = new ErrorResponse(code, "MinIO rejected the request", null, null, null, null, null);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://localhost/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("MinIO failure")
                .build();
        return new ErrorResponseException(error, response, "");
    }

    private static AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getStorage().setBucket("voice-note");
        return properties;
    }
}

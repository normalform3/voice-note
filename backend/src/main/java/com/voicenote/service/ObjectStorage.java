package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.web.ApiException;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.io.IOException;

@Service
public class ObjectStorage {
    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);
    private final MinioClient minio;
    private final AppProperties properties;
    public ObjectStorage(MinioClient minio, AppProperties properties) { this.minio = minio; this.properties = properties; }

    public void put(String objectKey, InputStream data, long length, String contentType) {
        try {
            ensureBucket();
            minio.putObject(PutObjectArgs.builder().bucket(properties.getStorage().getBucket()).object(objectKey)
                    .stream(data, length, 10 * 1024 * 1024).contentType(contentType).build());
        } catch (Exception exception) {
            throw storageFailure("write", exception);
        }
    }

    public InputStream get(String objectKey) {
        try {
            return minio.getObject(GetObjectArgs.builder().bucket(properties.getStorage().getBucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw storageFailure("read", exception);
        }
    }

    public void removeQuietly(String objectKey) {
        try { minio.removeObject(RemoveObjectArgs.builder().bucket(properties.getStorage().getBucket()).object(objectKey).build()); }
        catch (Exception exception) { log.warn("MinIO rollback delete failed: category={}, minioCode={}, exceptionType={}",
                classify(exception).category(), minioErrorCode(exception), exception.getClass().getSimpleName()); }
    }

    private void ensureBucket() throws Exception {
        String bucket = properties.getStorage().getBucket();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private ApiException storageFailure(String operation, Exception exception) {
        StorageFailure failure = classify(exception);
        log.error("MinIO {} failed: category={}, minioCode={}, exceptionType={}", operation,
                failure.category(), minioErrorCode(exception), exception.getClass().getSimpleName());
        return new ApiException(HttpStatus.BAD_GATEWAY, failure.code(), failure.message());
    }

    private static StorageFailure classify(Exception exception) {
        if (exception instanceof ErrorResponseException responseException) {
            return switch (minioErrorCode(responseException)) {
                case "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidToken", "ExpiredToken" -> StorageFailure.CREDENTIALS;
                case "NoSuchBucket", "InvalidBucketName", "BucketAlreadyExists" -> StorageFailure.BUCKET;
                default -> StorageFailure.SERVICE;
            };
        }
        if (exception instanceof IOException) return StorageFailure.CONNECTIVITY;
        if (exception instanceof IllegalArgumentException) return StorageFailure.CONFIGURATION;
        return StorageFailure.SERVICE;
    }

    private static String minioErrorCode(Exception exception) {
        if (exception instanceof ErrorResponseException responseException && responseException.errorResponse() != null
                && responseException.errorResponse().code() != null) {
            return responseException.errorResponse().code();
        }
        return "n/a";
    }

    private enum StorageFailure {
        CONNECTIVITY("CONNECTIVITY", "OBJECT_STORAGE_UNREACHABLE", "Cannot reach MinIO. Check the endpoint and network tunnel."),
        CREDENTIALS("CREDENTIALS", "OBJECT_STORAGE_CREDENTIALS_REJECTED", "MinIO rejected the configured access key, secret key, or write permission."),
        BUCKET("BUCKET", "OBJECT_STORAGE_BUCKET_UNAVAILABLE", "The configured MinIO bucket is invalid, missing, or not writable."),
        CONFIGURATION("CONFIGURATION", "OBJECT_STORAGE_CONFIGURATION_INVALID", "MinIO configuration is invalid. Check the endpoint and bucket name."),
        SERVICE("SERVICE", "OBJECT_STORAGE_UNAVAILABLE", "MinIO could not complete the request. Check its service logs and configuration.");

        private final String category;
        private final String code;
        private final String message;
        StorageFailure(String category, String code, String message) { this.category = category; this.code = code; this.message = message; }
        String category() { return category; }
        String code() { return code; }
        String message() { return message; }
    }
}

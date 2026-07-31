package com.echotrace.service;

import com.echotrace.config.AppProperties;
import com.echotrace.web.ApiException;
import io.minio.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.InputStream;

@Service
public class ObjectStorage {
    private final MinioClient minio;
    private final AppProperties properties;
    public ObjectStorage(MinioClient minio, AppProperties properties) { this.minio = minio; this.properties = properties; }

    public void put(String objectKey, InputStream data, long length, String contentType) {
        try {
            ensureBucket();
            minio.putObject(PutObjectArgs.builder().bucket(properties.getStorage().getBucket()).object(objectKey)
                    .stream(data, length, 10 * 1024 * 1024).contentType(contentType).build());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_UNAVAILABLE", "Unable to store the audio file");
        }
    }

    public InputStream get(String objectKey) {
        try {
            return minio.getObject(GetObjectArgs.builder().bucket(properties.getStorage().getBucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_UNAVAILABLE", "Unable to read the audio file");
        }
    }

    public void removeQuietly(String objectKey) {
        try { minio.removeObject(RemoveObjectArgs.builder().bucket(properties.getStorage().getBucket()).object(objectKey).build()); }
        catch (Exception ignored) { }
    }

    private void ensureBucket() throws Exception {
        String bucket = properties.getStorage().getBucket();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}

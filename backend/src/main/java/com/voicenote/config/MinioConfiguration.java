package com.voicenote.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfiguration {
    @Bean
    MinioClient minioClient(AppProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getStorage().getEndpoint())
                .credentials(properties.getStorage().getAccessKey(), properties.getStorage().getSecretKey())
                .build();
    }
}

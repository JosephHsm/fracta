package com.fracta.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MinIO 접속 설정. */
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(String endpoint, String accessKey, String secretKey, String bucket) {
}

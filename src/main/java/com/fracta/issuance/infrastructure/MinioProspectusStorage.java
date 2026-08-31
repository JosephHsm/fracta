package com.fracta.issuance.infrastructure;

import java.io.ByteArrayInputStream;

import org.springframework.stereotype.Component;

import com.fracta.common.config.MinioProperties;
import com.fracta.issuance.application.ProspectusStorage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;

import jakarta.annotation.PostConstruct;

/** MinIO 어댑터. 버킷은 기동 시 보장한다. */
@Component
public class MinioProspectusStorage implements ProspectusStorage {

    private final MinioProperties properties;
    private final MinioClient client;

    public MinioProspectusStorage(MinioProperties properties) {
        this.properties = properties;
        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @PostConstruct
    void ensureBucket() {
        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.bucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 버킷 준비 실패: " + properties.bucket(), e);
        }
    }

    @Override
    public void store(String fileKey, byte[] content, String contentType) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(fileKey)
                    .stream(in, content.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("투자설명서 업로드 실패: " + fileKey, e);
        }
    }

    @Override
    public boolean exists(String fileKey) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(fileKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("투자설명서 조회 실패: " + fileKey, e);
        }
    }
}

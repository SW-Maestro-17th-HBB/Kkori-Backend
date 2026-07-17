package com.aisw.kkori;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String TEST_BUCKET = "test-bucket";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        // docker-compose.yml과 동일한 pgvector 동봉 이미지 (임베딩 벡터 스키마 지원)
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }

    @Bean
    MinIOContainer minioContainer() {
        return new MinIOContainer("minio/minio:latest");
    }

    /** MinIO는 @ServiceConnection 미지원이라 Spring Cloud AWS 프로퍼티를 직접 주입한다. */
    @Bean
    DynamicPropertyRegistrar s3Properties(MinIOContainer minio) {
        return registry -> {
            registry.add("spring.cloud.aws.s3.endpoint", minio::getS3URL);
            registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
            registry.add("spring.cloud.aws.credentials.access-key", minio::getUserName);
            registry.add("spring.cloud.aws.credentials.secret-key", minio::getPassword);
            registry.add("spring.cloud.aws.region.static", () -> "ap-northeast-2");
            registry.add("app.s3.bucket", () -> TEST_BUCKET);
        };
    }
}

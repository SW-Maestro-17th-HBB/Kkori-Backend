package com.aisw.kkori;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String TEST_BUCKET = "test-bucket";

    /** docker-compose.yml과 동일 이미지·자격증명(로컬 컨테이너용 더미). */
    static final String S3_IMAGE = "rustfs/rustfs:1.0.0";
    static final String S3_ACCESS_KEY = "kkori";
    static final String S3_SECRET_KEY = "kkori1234";
    static final int S3_PORT = 9000;

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

    /**
     * S3 호환 저장소 — RustFS. MinIO 커뮤니티 에디션이 종료되며 이미지가 Docker Hub(2026-09-11)에 이어
     * quay.io에서도 사라져(CI가 pull 실패로 전 컨텍스트 기동 불가) MinIO 호환 S3 서버인 RustFS로 교체했다.
     * 버킷은 각 테스트가 {@code S3Template.createBucket}으로 만든다.
     */
    @Bean
    RustFsContainer s3Container() {
        return new RustFsContainer();
    }

    /** S3 저장소는 @ServiceConnection 미지원이라 Spring Cloud AWS 프로퍼티를 직접 주입한다. */
    @Bean
    DynamicPropertyRegistrar s3Properties(RustFsContainer s3) {
        return registry -> {
            registry.add("spring.cloud.aws.s3.endpoint", s3::getS3URL);
            registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
            registry.add("spring.cloud.aws.credentials.access-key", () -> S3_ACCESS_KEY);
            registry.add("spring.cloud.aws.credentials.secret-key", () -> S3_SECRET_KEY);
            registry.add("spring.cloud.aws.region.static", () -> "ap-northeast-2");
            registry.add("app.s3.bucket", () -> TEST_BUCKET);
        };
    }
    // 배치 빈 비활성화(app.batch.enabled=false)는 여기가 아니라 BatchDisablingEnvironmentPostProcessor가 담당한다 —
    // DynamicPropertyRegistrar는 빈 정의 등록 뒤에 실행되어 @ConditionalOnProperty 평가에 반영되지 않는다.

    /** 전용 타입으로 둔 이유: Redis도 GenericContainer라 주입 시 타입 모호성이 생긴다. */
    static class RustFsContainer extends GenericContainer<RustFsContainer> {
        RustFsContainer() {
            super(DockerImageName.parse(S3_IMAGE));
            withEnv("RUSTFS_ACCESS_KEY", S3_ACCESS_KEY);
            withEnv("RUSTFS_SECRET_KEY", S3_SECRET_KEY);
            withExposedPorts(S3_PORT);
            waitingFor(Wait.forHttp("/health").forPort(S3_PORT).forStatusCode(200));
        }

        String getS3URL() {
            return "http://" + getHost() + ":" + getMappedPort(S3_PORT);
        }
    }
}

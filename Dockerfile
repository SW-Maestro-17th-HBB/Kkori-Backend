# 프로드 이미지 — ECS 노드가 Graviton(arm64)이므로 arm64로 빌드:
#   docker buildx build --platform linux/arm64 -t <ECR>/kkori/backend:prod --push .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 레이어 — 소스 변경 시에도 캐시 유지
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# t4g.small(2GB) 노드 기준 — 조정은 task definition의 JAVA_TOOL_OPTIONS로 덮어쓰기
ENV JAVA_TOOL_OPTIONS="-Xmx768m"

RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

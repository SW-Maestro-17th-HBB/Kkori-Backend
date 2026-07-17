-- 임베딩 벡터 스키마(resume_chunks.embedding vector(1024), PRD resume.md §1)를 위해
-- 로컬 컨테이너 최초 생성 시 pgvector 확장을 활성화한다. 볼륨이 이미 있으면 실행되지 않으므로
-- 이미지 교체 시 볼륨 재생성 필요 (docker compose down -v).
CREATE EXTENSION IF NOT EXISTS vector;

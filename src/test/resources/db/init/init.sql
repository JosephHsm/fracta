-- Testcontainers PG 초기화 (docker/postgres/init.sql과 동일 내용 유지).
CREATE EXTENSION IF NOT EXISTS vector;

CREATE ROLE fracta LOGIN PASSWORD 'fracta';
GRANT CONNECT, CREATE ON DATABASE fracta TO fracta;
GRANT ALL ON SCHEMA public TO fracta;

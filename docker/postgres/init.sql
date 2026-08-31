-- 컨테이너 최초 기동 시 슈퍼유저로 1회 실행된다 (POSTGRES_DB=fracta에 접속된 상태).
CREATE EXTENSION IF NOT EXISTS vector;

-- 애플리케이션 계정: 비슈퍼유저로 분리해야 audit_log의 UPDATE/DELETE 권한 회수가 실제로 동작한다.
CREATE ROLE fracta LOGIN PASSWORD 'fracta';
GRANT CONNECT, CREATE ON DATABASE fracta TO fracta;
GRANT ALL ON SCHEMA public TO fracta;

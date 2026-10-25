-- InterviewGym SQLite schema（spec: docs/specs/persistent-store.md、resume-rag.md）
-- 幂等 DDL：spring.sql.init.mode=always，每次启动安全执行

CREATE TABLE IF NOT EXISTS sessions (
    id              TEXT PRIMARY KEY,
    status          TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    resume_assisted INTEGER NOT NULL DEFAULT 0,
    version         INTEGER NOT NULL DEFAULT 0,
    payload         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS reports (
    session_id TEXT PRIMARY KEY,
    payload    TEXT NOT NULL
);

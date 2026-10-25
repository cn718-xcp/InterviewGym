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

-- 简历与其向量索引（spec: resume-rag.md）：单活跃简历（id 恒为 1），上传即全量重建
CREATE TABLE IF NOT EXISTS resume (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    updated_at  TEXT NOT NULL,
    source_text TEXT NOT NULL,
    embed_model TEXT NOT NULL,
    embed_dim   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS resume_chunks (
    seq         INTEGER PRIMARY KEY AUTOINCREMENT,
    chunk_index INTEGER NOT NULL,
    section     TEXT NOT NULL,
    text        TEXT NOT NULL,
    embedding   BLOB NOT NULL
);

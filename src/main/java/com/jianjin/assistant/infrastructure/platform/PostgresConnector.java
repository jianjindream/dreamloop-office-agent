package com.jianjin.assistant.infrastructure.platform;

import com.jianjin.assistant.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class PostgresConnector {

    private static final Logger log = LoggerFactory.getLogger(PostgresConnector.class);

    private final AppConfig cfg;
    private volatile Connection conn;
    private volatile String status = "disconnected";

    public PostgresConnector(AppConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        try {
            String url = cfg.getPgJdbcUrl();
            conn = DriverManager.getConnection(url, cfg.getPostgres().getUser(), cfg.getPostgres().getPassword());
            status = "connected";
            initSchema();
            log.info("PostgreSQL 已连接: {}", url);
        } catch (Exception e) {
            log.warn("PostgreSQL 连接失败: {} (将使用内存模式)", e.getMessage());
            status = "disconnected";
        }
    }

    private void initSchema() {
        if (conn == null) return;
        String[] ddls = {
                """
                CREATE TABLE IF NOT EXISTS user_preferences (
                    user_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
                    updated_at TIMESTAMP DEFAULT NOW(), PRIMARY KEY (user_id, key))""",
                """
                CREATE TABLE IF NOT EXISTS task_snapshots (
                    task_id TEXT PRIMARY KEY, state JSONB NOT NULL, created_at TIMESTAMP DEFAULT NOW())""",
                """
                CREATE TABLE IF NOT EXISTS chat_history (
                    id BIGSERIAL PRIMARY KEY, user_id TEXT NOT NULL DEFAULT 'default',
                    session_id TEXT NOT NULL DEFAULT 'default', role TEXT NOT NULL, content TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW())""",
                "ALTER TABLE chat_history ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT 'default'",
                "ALTER TABLE chat_history ADD COLUMN IF NOT EXISTS session_id TEXT NOT NULL DEFAULT 'default'",
                "CREATE INDEX IF NOT EXISTS idx_chat_history_scope ON chat_history(user_id, session_id, id)",
                """
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    user_id TEXT NOT NULL, session_id TEXT NOT NULL, title TEXT NOT NULL DEFAULT '新对话',
                    created_at TIMESTAMP DEFAULT NOW(), updated_at TIMESTAMP DEFAULT NOW(),
                    PRIMARY KEY (user_id, session_id))""",
                """
                CREATE TABLE IF NOT EXISTS session_summaries (
                    user_id TEXT NOT NULL, session_id TEXT NOT NULL, summary JSONB NOT NULL,
                    summarized_through_id BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMP DEFAULT NOW(),
                    PRIMARY KEY (user_id, session_id))""",
                """
                CREATE TABLE IF NOT EXISTS long_term_memory (
                    id SERIAL PRIMARY KEY, content TEXT NOT NULL, importance FLOAT NOT NULL DEFAULT 0.5,
                    embedding JSONB, created_at TIMESTAMP DEFAULT NOW(), last_accessed TIMESTAMP DEFAULT NOW())""",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS last_accessed TIMESTAMP DEFAULT NOW()",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS category TEXT DEFAULT 'general'",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS tags JSONB",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS slot_hint TEXT",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT 'default'",
                "CREATE INDEX IF NOT EXISTS idx_long_term_memory_user ON long_term_memory(user_id, id)",
                """
                CREATE TABLE IF NOT EXISTS rag_parent_chunks (
                    id BIGSERIAL PRIMARY KEY, doc_hash TEXT NOT NULL, parent_idx INT NOT NULL,
                    content TEXT NOT NULL, created_at TIMESTAMP DEFAULT NOW(),
                    UNIQUE(doc_hash, parent_idx))""",
                """
                CREATE TABLE IF NOT EXISTS rag_chunks (
                    id BIGSERIAL PRIMARY KEY, doc_hash TEXT NOT NULL, chunk_idx INT NOT NULL,
                    content TEXT NOT NULL, embedding JSONB, created_at TIMESTAMP DEFAULT NOW(),
                    UNIQUE(doc_hash, chunk_idx))""",
                "ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS parent_id BIGINT",
                "CREATE INDEX IF NOT EXISTS idx_rag_chunks_parent_id ON rag_chunks(parent_id)",
                """
                CREATE TABLE IF NOT EXISTS rag_documents (
                    doc_hash TEXT PRIMARY KEY, owner_key TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW(), updated_at TIMESTAMP DEFAULT NOW())""",
                "CREATE INDEX IF NOT EXISTS idx_rag_documents_owner ON rag_documents(owner_key, doc_hash)",
                """
                CREATE TABLE IF NOT EXISTS rag_eval_datasets (
                    id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL, version TEXT NOT NULL,
                    description TEXT, created_at TIMESTAMP DEFAULT NOW(), updated_at TIMESTAMP DEFAULT NOW(),
                    UNIQUE(name, version))""",
                """
                CREATE TABLE IF NOT EXISTS rag_eval_cases (
                    id BIGSERIAL PRIMARY KEY, dataset_id BIGINT NOT NULL REFERENCES rag_eval_datasets(id) ON DELETE CASCADE,
                    case_id TEXT NOT NULL, case_json JSONB NOT NULL, created_at TIMESTAMP DEFAULT NOW(),
                    UNIQUE(dataset_id, case_id))""",
                """
                CREATE TABLE IF NOT EXISTS rag_eval_runs (
                    id UUID PRIMARY KEY, dataset_id BIGINT REFERENCES rag_eval_datasets(id), status TEXT NOT NULL,
                    config_snapshot JSONB NOT NULL, report_json JSONB NOT NULL,
                    started_at TIMESTAMP DEFAULT NOW(), completed_at TIMESTAMP)""",
                """
                CREATE TABLE IF NOT EXISTS rag_eval_case_results (
                    id BIGSERIAL PRIMARY KEY, run_id UUID NOT NULL REFERENCES rag_eval_runs(id) ON DELETE CASCADE,
                    case_id TEXT NOT NULL, result_json JSONB NOT NULL)""",
                "CREATE INDEX IF NOT EXISTS idx_rag_eval_runs_dataset_id ON rag_eval_runs(dataset_id)"
        };
        try (Statement stmt = conn.createStatement()) {
            for (String ddl : ddls) stmt.execute(ddl);
            log.info("PostgreSQL 表结构已初始化");
        } catch (SQLException e) {
            log.warn("PG 建表失败: {}", e.getMessage());
        }
    }

    /** @return Connection（可能为 null，表示未连接） */
    public Connection connection() { return conn; }

    public boolean available() { return "connected".equals(status) && conn != null; }

    public String status() { return status; }

    @PreDestroy
    public void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }
}

package com.jianjin.assistant.service.graph;

import com.jianjin.assistant.config.AppConfig;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Neo4jStore {

    private static final Logger log = LoggerFactory.getLogger(Neo4jStore.class);

    private final Driver driver;
    private final boolean available;

    public Neo4jStore(AppConfig cfg) {
        AppConfig.Neo4jConfig nc = cfg.getNeo4j();
        if (nc == null || !nc.isEnabled() || nc.getUri() == null || nc.getUri().isEmpty()) {
            log.info("Neo4j 未启用 (enabled={}, uri={})",
                    nc != null && nc.isEnabled(), nc != null ? nc.getUri() : "<null>");
            this.driver = null;
            this.available = false;
            return;
        }

        Driver d = null;
        boolean ok = false;
        try {
            d = GraphDatabase.driver(nc.getUri(), AuthTokens.basic(nc.getUser(), nc.getPassword()));
            d.verifyConnectivity();
            ok = true;
            log.info("Neo4j 已连接: {}", nc.getUri());
        } catch (Exception e) {
            log.warn("Neo4j 连接失败 {}: {} (知识图谱将降级跳过)", nc.getUri(), e.getMessage());
            if (d != null) {
                try { d.close(); } catch (Exception ignored) {}
                d = null;
            }
            ok = false;
        }
        this.driver = d;
        this.available = ok;

        if (this.available) {
            ensureConstraints();
        }
    }

    public boolean available() { return available; }

    public Driver driver() { return driver; }

    /**
     * 返回一个写入 session；调用方需 close。
     */
    public Session session() {
        return driver.session(SessionConfig.builder().build());
    }

    public void close() {
        if (driver != null) {
            try { driver.close(); } catch (Exception ignored) {}
        }
    }

    private void ensureConstraints() {
        String[] queries = {
                "CREATE CONSTRAINT entity_name IF NOT EXISTS FOR (e:Entity) REQUIRE e.name IS UNIQUE",
                "CREATE INDEX entity_type IF NOT EXISTS FOR (e:Entity) ON (e.type)",
                "CREATE CONSTRAINT rag_chunk_id IF NOT EXISTS FOR (c:RagChunk) REQUIRE c.id IS UNIQUE",
                "CREATE INDEX rag_chunk_doc_hash IF NOT EXISTS FOR (c:RagChunk) ON (c.doc_hash)",
                "MATCH (e:Entity) WHERE e.chunk_id IS NOT NULL " +
                        "MERGE (c:RagChunk {id: e.chunk_id}) SET c.doc_hash = e.doc_hash " +
                        "MERGE (e)-[:MENTIONED_IN]->(c) REMOVE e.doc_hash, e.chunk_id",
                "MATCH (m:Memory) WHERE m.user_id IS NULL SET m.user_id = 'default'",
                "CREATE INDEX memory_node_scope IF NOT EXISTS FOR (m:Memory) ON (m.user_id, m.mem_id)"
        };
        try (Session s = session()) {
            for (String q : queries) {
                try {
                    s.run(q);
                } catch (Exception e) {
                    log.info("Neo4j constraint/index: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.info("Neo4j ensureConstraints 失败: {}", e.getMessage());
        }
    }
}

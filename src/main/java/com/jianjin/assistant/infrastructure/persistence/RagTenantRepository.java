package com.jianjin.assistant.infrastructure.persistence;

import com.jianjin.assistant.infrastructure.platform.PostgresConnector;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class RagTenantRepository {
    private final PostgresConnector pg;
    private final Map<String,String> memoryOwners = new ConcurrentHashMap<>();
    public RagTenantRepository(PostgresConnector pg) { this.pg = pg; }

    public void assign(String docHash, String ownerKey) {
        memoryOwners.put(docHash, ownerKey);
        var connection = pg.connection();
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO rag_documents(doc_hash,owner_key) VALUES(?,?) " +
                "ON CONFLICT(doc_hash) DO UPDATE SET owner_key=EXCLUDED.owner_key,updated_at=NOW()")) {
            ps.setString(1,docHash); ps.setString(2,ownerKey); ps.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException("RAG owner persistence failed", e); }
    }

    public boolean owns(String docHash, String ownerKey) {
        String memory = memoryOwners.get(docHash);
        if (memory != null) return memory.equals(ownerKey);
        var connection = pg.connection();
        if (connection == null) return false;
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM rag_documents WHERE doc_hash=? AND owner_key=?")) {
            ps.setString(1,docHash); ps.setString(2,ownerKey); try(ResultSet rs=ps.executeQuery()){return rs.next();}
        } catch (Exception e) { return false; }
    }

    public Set<Long> allowedContexts(String ownerKey, List<Long> contextIds) {
        Set<Long> out = new HashSet<>();
        if (contextIds == null || contextIds.isEmpty() || pg.connection() == null) return out;
        String placeholders = String.join(",", contextIds.stream().map(id -> "?").toList());
        String sql = "SELECT DISTINCT COALESCE(p.id,c.id) AS context_id FROM rag_chunks c " +
                "LEFT JOIN rag_parent_chunks p ON p.id=c.parent_id JOIN rag_documents d ON d.doc_hash=c.doc_hash " +
                "WHERE d.owner_key=? AND COALESCE(p.id,c.id) IN (" + placeholders + ")";
        try (PreparedStatement ps = pg.connection().prepareStatement(sql)) {
            ps.setString(1, ownerKey);
            for(int i=0;i<contextIds.size();i++) ps.setLong(i+2, contextIds.get(i));
            try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(rs.getLong(1));}
        } catch (Exception ignored) {}
        return out;
    }

    public void remove(String docHash) {
        memoryOwners.remove(docHash);
        var connection=pg.connection(); if(connection==null)return;
        try(PreparedStatement ps=connection.prepareStatement("DELETE FROM rag_documents WHERE doc_hash=?")){
            ps.setString(1,docHash);ps.executeUpdate();
        }catch(Exception ignored){}
    }
}

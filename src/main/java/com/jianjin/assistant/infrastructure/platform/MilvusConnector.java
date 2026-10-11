package com.jianjin.assistant.infrastructure.platform;

import com.jianjin.assistant.config.AppConfig;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.ReleaseCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.param.index.DropIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.DescIndexResponseWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.net.InetSocketAddress;
import java.net.Socket;

@Component
public class MilvusConnector {

    private static final Logger log = LoggerFactory.getLogger(MilvusConnector.class);
    public static final String RAG_COLLECTION = "rag_chunks";
    public static final String RAG_INDEX = "rag_embedding_idx";
    public static final MetricType RAG_METRIC = MetricType.COSINE;

    private final AppConfig cfg;
    private volatile MilvusClient client;
    private volatile String status = "disconnected";

    public MilvusConnector(AppConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        try {
            // SDK 的探测请求会在服务离线时长时间重试；先快速检查 TCP，
            // 让不依赖向量库的文档 API 能及时以降级模式启动。
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(cfg.getMilvus().getHost(), cfg.getMilvus().getPort()), 1_000);
            }
            ConnectParam param = ConnectParam.newBuilder()
                    .withHost(cfg.getMilvus().getHost())
                    .withPort(cfg.getMilvus().getPort())
                    .withConnectTimeout(5, TimeUnit.SECONDS)
                    .build();
            MilvusServiceClient c = new MilvusServiceClient(param);
            R<Boolean> probe = c.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName("__probe__").build());
            if (probe.getStatus() != R.Status.Success.getCode()
                    && probe.getStatus() != R.Status.CollectionNotExists.getCode()) {
                // probe 也算可用（部分版本对未存在 collection 返回非 Success）
            }
            client = c;
            status = "connected";
            log.info("Milvus 已连接: {}", cfg.getMilvusAddr());
        } catch (Exception e) {
            log.warn("Milvus 连接失败: {} (将使用内存向量库)", e.getMessage());
            status = "disconnected";
        }
    }

    public MilvusClient client() { return client; }
    public boolean available() { return "connected".equals(status) && client != null; }
    public String status() { return status; }

    /** 确保 RAG collection 存在；维度不匹配时自动重建，度量不匹配时只重建索引。 */
    public boolean ensureRAGCollection(int dim) {
        if (client == null) return false;
        R<Boolean> hasResp = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(RAG_COLLECTION).build());
        boolean has = hasResp.getStatus() == R.Status.Success.getCode() && Boolean.TRUE.equals(hasResp.getData());

        if (has) {
            R<io.milvus.grpc.DescribeCollectionResponse> desc = client.describeCollection(
                    DescribeCollectionParam.newBuilder().withCollectionName(RAG_COLLECTION).build());
            boolean needRecreate = false;
            boolean hasDocHash = false;
            if (desc.getStatus() == R.Status.Success.getCode()) {
                DescCollResponseWrapper w = new DescCollResponseWrapper(desc.getData());
                for (FieldType f : w.getFields()) {
                    if ("doc_hash".equals(f.getName()) && f.getDataType() == DataType.VarChar) {
                        hasDocHash = true;
                    }
                    if ("embedding".equals(f.getName()) && f.getDataType() == DataType.FloatVector) {
                        Map<String, String> tp = f.getTypeParams();
                        String existing = tp == null ? "" : tp.getOrDefault("dim", "");
                        if (!String.valueOf(dim).equals(existing)) {
                            log.warn("Milvus rag_chunks 维度不匹配 (现有={}, 期望={})，重建", existing, dim);
                            needRecreate = true;
                        }
                    }
                    if ("id".equals(f.getName()) && f.isPrimaryKey()) {
                        log.warn("Milvus rag_chunks 主键为 id (应为 pg_id)，重建");
                        needRecreate = true;
                    }
                }
                if (!hasDocHash) {
                    log.warn("Milvus rag_chunks 缺少 doc_hash 租户过滤字段，重建");
                    needRecreate = true;
                }
            }
            if (needRecreate) {
                client.dropCollection(DropCollectionParam.newBuilder()
                        .withCollectionName(RAG_COLLECTION).build());
                has = false;
            }
            if (has) {
                ensureRAGIndex();
                loadRAGCollection();
                return false;
            }
        }

        FieldType pgIdField = FieldType.newBuilder().withName("pg_id")
                .withDataType(DataType.Int64).withPrimaryKey(true).withAutoID(false).build();
        FieldType contentField = FieldType.newBuilder().withName("content")
                .withDataType(DataType.VarChar).withMaxLength(4096).build();
        FieldType docHashField = FieldType.newBuilder().withName("doc_hash")
                .withDataType(DataType.VarChar).withMaxLength(64).build();
        FieldType embField = FieldType.newBuilder().withName("embedding")
                .withDataType(DataType.FloatVector).withDimension(dim).build();
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(RAG_COLLECTION).withShardsNum(1)
                .addFieldType(pgIdField).addFieldType(contentField).addFieldType(docHashField)
                .addFieldType(embField).build();
        R<RpcStatus> r = client.createCollection(createParam);
        if (r.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("create rag_chunks 失败: " + r.getMessage());
        }
        ensureRAGIndex();
        loadRAGCollection();
        log.info("Milvus rag_chunks collection 已就绪，向量度量={}", RAG_METRIC);
        return true;
    }

    private void ensureRAGIndex() {
        try {
            R<io.milvus.grpc.DescribeIndexResponse> response = client.describeIndex(
                    DescribeIndexParam.newBuilder().withCollectionName(RAG_COLLECTION).build());
            if (response.getStatus() == R.Status.Success.getCode() && response.getData() != null) {
                DescIndexResponseWrapper wrapper = new DescIndexResponseWrapper(response.getData());
                DescIndexResponseWrapper.IndexDesc existing = wrapper.getIndexDescByFieldName("embedding");
                if (existing != null && existing.getMetricType() == RAG_METRIC) return;
                if (existing != null) {
                    log.warn("Milvus rag_chunks 度量不匹配 (现有={}, 期望={})，重建向量索引",
                            existing.getMetricType(), RAG_METRIC);
                    try {
                        client.releaseCollection(ReleaseCollectionParam.newBuilder()
                                .withCollectionName(RAG_COLLECTION).build());
                    } catch (Exception ignored) { }
                    DropIndexParam.Builder drop = DropIndexParam.newBuilder().withCollectionName(RAG_COLLECTION);
                    if (existing.getIndexName() != null && !existing.getIndexName().isBlank()) {
                        drop.withIndexName(existing.getIndexName());
                    }
                    R<RpcStatus> dropped = client.dropIndex(drop.build());
                    if (dropped.getStatus() != R.Status.Success.getCode()) {
                        throw new IllegalStateException("drop old Milvus index failed: " + dropped.getMessage());
                    }
                }
            }

            R<RpcStatus> created = client.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(RAG_COLLECTION)
                    .withFieldName("embedding")
                    .withIndexName(RAG_INDEX)
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(RAG_METRIC)
                    .withExtraParam("{\"nlist\":128}")
                    .build());
            if (created.getStatus() != R.Status.Success.getCode()) {
                throw new IllegalStateException("create Milvus index failed: " + created.getMessage());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Milvus rag_chunks 索引初始化失败: " + e.getMessage(), e);
        }
    }

    private void loadRAGCollection() {
        try {
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(RAG_COLLECTION).build());
        } catch (Exception e) {
            log.warn("Milvus rag_chunks 加载失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try { client.close(3); } catch (Exception ignored) {}
        }
    }
}

package com.jianjin.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private LlmConfig llm = new LlmConfig();
    private EmbeddingConfig embedding = new EmbeddingConfig();
    private MilvusConfig milvus = new MilvusConfig();
    private PostgresConfig postgres = new PostgresConfig();
    private ElasticsearchConfig elasticsearch = new ElasticsearchConfig();
    private KafkaConfig kafka = new KafkaConfig();
    private RagConfig rag = new RagConfig();
    private EvaluationConfig evaluation = new EvaluationConfig();
    private MemoryConfig memory = new MemoryConfig();
    private HarnessConfig harness = new HarnessConfig();
    private SearchConfig search = new SearchConfig();
    private Neo4jConfig neo4j = new Neo4jConfig();
    private SandboxConfig sandbox = new SandboxConfig();
    private SecurityConfig security = new SecurityConfig();
    private GraphConfig graph = new GraphConfig();
    private AuthConfig auth = new AuthConfig();

    // ===== Inner Config Classes =====

    public static class LlmConfig {
        private String apiUrl;
        private String apiKey;
        private String model;
        private double temperature = 0.7;

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    public static class EmbeddingConfig {
        private String apiUrl;
        private String apiKey;
        private String model;
        /** Output vector dimension requested from OpenAI-compatible embedding APIs. */
        private int dimensions = 1024;

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    }

    public static class MilvusConfig {
        private String host = "localhost";
        private int port = 19530;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class PostgresConfig {
        private String host = "localhost";
        private int port = 5432;
        private String user = "aiagent";
        private String password = "aiagent123";
        private String database = "aiagent";

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
    }

    public static class ElasticsearchConfig {
        /** comma separated list, e.g. "http://localhost:9200" */
        private String addresses = "http://localhost:9200";
        private String username = "elastic";
        private String password = "changeme";

        public String getAddresses() { return addresses; }
        public void setAddresses(String addresses) { this.addresses = addresses; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public List<String> getAddressList() {
            List<String> result = new ArrayList<>();
            if (addresses == null || addresses.isEmpty()) return result;
            for (String addr : addresses.split(",")) {
                String trimmed = addr.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return result;
        }
    }

    public static class KafkaConfig {
        /** comma-separated list, e.g. "localhost:29092" */
        private String brokers = "localhost:29092";
        private String topic = "agent-events";

        public String getBrokers() { return brokers; }
        public void setBrokers(String brokers) { this.brokers = brokers; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
    }

    public static class RagConfig {
        private int chunkSize = 200;
        private int chunkOverlap = 50;
        /** Parent chunks provide context for retrieved child chunks. Zero derives a sensible value. */
        private int parentChunkSize = 0;
        /** Zero derives twice the child overlap. */
        private int parentChunkOverlap = 0;
        private int topK = 3;
        private int rrfConstantK = 60;
        private double semanticWeight = 0.7;
        private double keywordWeight = 1.0;
        private boolean enableHybridSearch = true;
        private int ragMilvusDim = 1024;

        /** Query Rewrite（history-aware + multi-query） */
        private RewriteConfig rewrite = new RewriteConfig();
        /** Rerank（LLM listwise 精排） */
        private RerankConfig rerank = new RerankConfig();

        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
        public int getParentChunkSize() { return parentChunkSize; }
        public void setParentChunkSize(int parentChunkSize) { this.parentChunkSize = parentChunkSize; }
        public int getParentChunkOverlap() { return parentChunkOverlap; }
        public void setParentChunkOverlap(int parentChunkOverlap) { this.parentChunkOverlap = parentChunkOverlap; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public int getRrfConstantK() { return rrfConstantK; }
        public void setRrfConstantK(int rrfConstantK) { this.rrfConstantK = rrfConstantK; }
        public double getSemanticWeight() { return semanticWeight; }
        public void setSemanticWeight(double semanticWeight) { this.semanticWeight = semanticWeight; }
        public double getKeywordWeight() { return keywordWeight; }
        public void setKeywordWeight(double keywordWeight) { this.keywordWeight = keywordWeight; }
        public boolean isEnableHybridSearch() { return enableHybridSearch; }
        public void setEnableHybridSearch(boolean enableHybridSearch) { this.enableHybridSearch = enableHybridSearch; }
        public int getRagMilvusDim() { return ragMilvusDim; }
        public void setRagMilvusDim(int ragMilvusDim) { this.ragMilvusDim = ragMilvusDim; }
        public RewriteConfig getRewrite() { return rewrite; }
        public void setRewrite(RewriteConfig rewrite) { this.rewrite = rewrite; }
        public RerankConfig getRerank() { return rerank; }
        public void setRerank(RerankConfig rerank) { this.rerank = rerank; }
    }

    public static class RewriteConfig {
        private boolean enabled = true;
        /** Maximum number of queries, including the primary query. */
        private int numQueries = 3;
        private double variantWeight = 0.8;
        private double broadWeight = 0.6;
        /** Number of concurrent expansion-query searches. */
        private int parallelism = 2;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getNumQueries() { return numQueries; }
        public void setNumQueries(int numQueries) { this.numQueries = numQueries; }
        public double getVariantWeight() { return variantWeight; }
        public void setVariantWeight(double variantWeight) { this.variantWeight = variantWeight; }
        public double getBroadWeight() { return broadWeight; }
        public void setBroadWeight(double broadWeight) { this.broadWeight = broadWeight; }
        public int getParallelism() { return parallelism; }
        public void setParallelism(int parallelism) { this.parallelism = parallelism; }
    }

    public static class RerankConfig {
        private boolean enabled = false;
        /** 给 reranker 看的每条候选最大字符数 */
        private int previewLen = 200;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPreviewLen() { return previewLen; }
        public void setPreviewLen(int previewLen) { this.previewLen = previewLen; }
    }

    /** Offline RAG benchmark limits. */
    public static class EvaluationConfig {
        private int maxCases = 200;

        public int getMaxCases() { return maxCases; }
        public void setMaxCases(int maxCases) { this.maxCases = maxCases; }
    }

    public static class MemoryConfig {
        private int shortTermMaxTurns = 10;
        private int longTermTopK = 3;
        private int minRecentTurns = 4;
        private int contextWindowTokens = 32768;
        private int reservedOutputTokens = 4096;
        private int historyMaxTokens = 8000;
        private int summaryMaxTokens = 1200;
        private int summarySoftTurns = 8;
        private int summarySoftTokens = 6000;
        private ConsolidationConfig consolidation = new ConsolidationConfig();

        public int getShortTermMaxTurns() { return shortTermMaxTurns; }
        public void setShortTermMaxTurns(int shortTermMaxTurns) { this.shortTermMaxTurns = shortTermMaxTurns; }
        public int getLongTermTopK() { return longTermTopK; }
        public void setLongTermTopK(int longTermTopK) { this.longTermTopK = longTermTopK; }
        public int getMinRecentTurns() { return minRecentTurns; }
        public void setMinRecentTurns(int minRecentTurns) { this.minRecentTurns = minRecentTurns; }
        public int getContextWindowTokens() { return contextWindowTokens; }
        public void setContextWindowTokens(int contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }
        public int getReservedOutputTokens() { return reservedOutputTokens; }
        public void setReservedOutputTokens(int reservedOutputTokens) { this.reservedOutputTokens = reservedOutputTokens; }
        public int getHistoryMaxTokens() { return historyMaxTokens; }
        public void setHistoryMaxTokens(int historyMaxTokens) { this.historyMaxTokens = historyMaxTokens; }
        public int getSummaryMaxTokens() { return summaryMaxTokens; }
        public void setSummaryMaxTokens(int summaryMaxTokens) { this.summaryMaxTokens = summaryMaxTokens; }
        public int getSummarySoftTurns() { return summarySoftTurns; }
        public void setSummarySoftTurns(int summarySoftTurns) { this.summarySoftTurns = summarySoftTurns; }
        public int getSummarySoftTokens() { return summarySoftTokens; }
        public void setSummarySoftTokens(int summarySoftTokens) { this.summarySoftTokens = summarySoftTokens; }
        public ConsolidationConfig getConsolidation() { return consolidation; }
        public void setConsolidation(ConsolidationConfig consolidation) { this.consolidation = consolidation; }
    }

    public static class ConsolidationConfig {
        private double similarityThreshold = 0.80;
        private double dedupThreshold = 0.95;
        private int ttlDays = 30;
        private double decayRate = 0.995;
        private double minImportance = 0.3;
        private int triggerInterval = 5;

        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        public double getDedupThreshold() { return dedupThreshold; }
        public void setDedupThreshold(double dedupThreshold) { this.dedupThreshold = dedupThreshold; }
        public int getTtlDays() { return ttlDays; }
        public void setTtlDays(int ttlDays) { this.ttlDays = ttlDays; }
        public double getDecayRate() { return decayRate; }
        public void setDecayRate(double decayRate) { this.decayRate = decayRate; }
        public double getMinImportance() { return minImportance; }
        public void setMinImportance(double minImportance) { this.minImportance = minImportance; }
        public int getTriggerInterval() { return triggerInterval; }
        public void setTriggerInterval(int triggerInterval) { this.triggerInterval = triggerInterval; }
    }

    public static class HarnessConfig {
        private int maxRetries = 3;
        private int retryDelayMs = 200;
        private int stepTimeoutMs = 5000;
        private int maxIterations = 5;

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getRetryDelayMs() { return retryDelayMs; }
        public void setRetryDelayMs(int retryDelayMs) { this.retryDelayMs = retryDelayMs; }
        public int getStepTimeoutMs() { return stepTimeoutMs; }
        public void setStepTimeoutMs(int stepTimeoutMs) { this.stepTimeoutMs = stepTimeoutMs; }
        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    }

    public static class SearchConfig {
        private String apiKey;
        private String apiUrl;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    }

    public static class Neo4jConfig {
        private String uri = "bolt://localhost:7687";
        private String user = "neo4j";
        private String password = "password123";
        private int maxHops = 2;
        private double weight = 0.3;
        private boolean enabled = true;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaxHops() { return maxHops; }
        public void setMaxHops(int maxHops) { this.maxHops = maxHops; }
        public double getWeight() { return weight; }
        public void setWeight(double weight) { this.weight = weight; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class SandboxConfig {
        private boolean enabled = true;
        /** "docker" | "local" | "mock" */
        private String backend = "docker";
        private String image = "alpine:3.19";
        private int timeoutMs = 30000;
        private int maxOutputBytes = 65536;
        private int memoryLimitMb = 256;
        private int cpuPercent = 50;
        private int maxPids = 64;
        private boolean networkDisabled = true;
        private boolean readonlyRootfs = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBackend() { return backend; }
        public void setBackend(String backend) { this.backend = backend; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxOutputBytes() { return maxOutputBytes; }
        public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }
        public int getMemoryLimitMb() { return memoryLimitMb; }
        public void setMemoryLimitMb(int memoryLimitMb) { this.memoryLimitMb = memoryLimitMb; }
        public int getCpuPercent() { return cpuPercent; }
        public void setCpuPercent(int cpuPercent) { this.cpuPercent = cpuPercent; }
        public int getMaxPids() { return maxPids; }
        public void setMaxPids(int maxPids) { this.maxPids = maxPids; }
        public boolean isNetworkDisabled() { return networkDisabled; }
        public void setNetworkDisabled(boolean networkDisabled) { this.networkDisabled = networkDisabled; }
        public boolean isReadonlyRootfs() { return readonlyRootfs; }
        public void setReadonlyRootfs(boolean readonlyRootfs) { this.readonlyRootfs = readonlyRootfs; }
    }

    public static class GraphConfig {
        private int maxParallel = 2;
        private int raceTimeoutMs = 30000;
        private boolean enableRacing = true;

        public int getMaxParallel() { return maxParallel; }
        public void setMaxParallel(int maxParallel) { this.maxParallel = maxParallel; }
        public int getRaceTimeoutMs() { return raceTimeoutMs; }
        public void setRaceTimeoutMs(int raceTimeoutMs) { this.raceTimeoutMs = raceTimeoutMs; }
        public boolean isEnableRacing() { return enableRacing; }
        public void setEnableRacing(boolean enableRacing) { this.enableRacing = enableRacing; }
    }

    public static class SecurityConfig {
        private int maxCommandLength = 500;
        private boolean allowlistMode = false;
        private List<String> allowlist = new ArrayList<>();
        private boolean allowPrivateMcpEndpoints = false;

        public int getMaxCommandLength() { return maxCommandLength; }
        public void setMaxCommandLength(int maxCommandLength) { this.maxCommandLength = maxCommandLength; }
        public boolean isAllowlistMode() { return allowlistMode; }
        public void setAllowlistMode(boolean allowlistMode) { this.allowlistMode = allowlistMode; }
        public List<String> getAllowlist() { return allowlist; }
        public void setAllowlist(List<String> allowlist) { this.allowlist = allowlist; }
        public boolean isAllowPrivateMcpEndpoints() { return allowPrivateMcpEndpoints; }
        public void setAllowPrivateMcpEndpoints(boolean allowPrivateMcpEndpoints) { this.allowPrivateMcpEndpoints = allowPrivateMcpEndpoints; }
    }

    public static class AuthConfig {
        private boolean enabled = false;
        private String defaultWorkspace = "default";
        /** token -> userId:workspaceId; use environment-backed configuration in production. */
        private Map<String, String> tokens = new LinkedHashMap<>();
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDefaultWorkspace() { return defaultWorkspace; }
        public void setDefaultWorkspace(String defaultWorkspace) { this.defaultWorkspace = defaultWorkspace; }
        public Map<String, String> getTokens() { return tokens; }
        public void setTokens(Map<String, String> tokens) { this.tokens = tokens == null ? new LinkedHashMap<>() : tokens; }
        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : allowedOrigins; }
    }

    // ===== Main Getters/Setters =====

    public LlmConfig getLlm() { return llm; }
    public void setLlm(LlmConfig llm) { this.llm = llm; }
    public EmbeddingConfig getEmbedding() { return embedding; }
    public void setEmbedding(EmbeddingConfig embedding) { this.embedding = embedding; }
    public MilvusConfig getMilvus() { return milvus; }
    public void setMilvus(MilvusConfig milvus) { this.milvus = milvus; }
    public PostgresConfig getPostgres() { return postgres; }
    public void setPostgres(PostgresConfig postgres) { this.postgres = postgres; }
    public ElasticsearchConfig getElasticsearch() { return elasticsearch; }
    public void setElasticsearch(ElasticsearchConfig elasticsearch) { this.elasticsearch = elasticsearch; }
    public KafkaConfig getKafka() { return kafka; }
    public void setKafka(KafkaConfig kafka) { this.kafka = kafka; }
    public RagConfig getRag() { return rag; }
    public void setRag(RagConfig rag) { this.rag = rag; }
    public EvaluationConfig getEvaluation() { return evaluation; }
    public void setEvaluation(EvaluationConfig evaluation) { this.evaluation = evaluation; }
    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig memory) { this.memory = memory; }
    public HarnessConfig getHarness() { return harness; }
    public void setHarness(HarnessConfig harness) { this.harness = harness; }
    public SearchConfig getSearch() { return search; }
    public void setSearch(SearchConfig search) { this.search = search; }
    public Neo4jConfig getNeo4j() { return neo4j; }
    public void setNeo4j(Neo4jConfig neo4j) { this.neo4j = neo4j; }
    public SandboxConfig getSandbox() { return sandbox; }
    public void setSandbox(SandboxConfig sandbox) { this.sandbox = sandbox; }
    public SecurityConfig getSecurity() { return security; }
    public void setSecurity(SecurityConfig security) { this.security = security; }
    public GraphConfig getGraph() { return graph; }
    public void setGraph(GraphConfig graph) { this.graph = graph; }
    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }

    // ===== Helper Methods =====

    public boolean isRealLLM() {
        return llm.getApiKey() != null && !llm.getApiKey().isEmpty();
    }

    public boolean isRealEmbedding() {
        return embedding.getApiKey() != null && !embedding.getApiKey().isEmpty();
    }

    public String getPgJdbcUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getPort(), postgres.getDatabase());
    }

    public String getMilvusAddr() {
        return String.format("%s:%d", milvus.getHost(), milvus.getPort());
    }
}

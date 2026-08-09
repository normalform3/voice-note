package com.voicenote.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Security security = new Security();
    private final Storage storage = new Storage();
    private final RocketMq rocketmq = new RocketMq();
    private final Dashscope dashscope = new Dashscope();
    private final Workers workers = new Workers();
    private final Knowledge knowledge = new Knowledge();
    private final Agent agent = new Agent();
    private final Mcp mcp = new Mcp();

    public Security getSecurity() { return security; }
    public Storage getStorage() { return storage; }
    public RocketMq getRocketmq() { return rocketmq; }
    public Dashscope getDashscope() { return dashscope; }
    public Workers getWorkers() { return workers; }
    public Knowledge getKnowledge() { return knowledge; }
    public Agent getAgent() { return agent; }
    public Mcp getMcp() { return mcp; }

    public static class Security {
        private String jwtSecret;
        private long tokenTtlHours;
        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public long getTokenTtlHours() { return tokenTtlHours; }
        public void setTokenTtlHours(long tokenTtlHours) { this.tokenTtlHours = tokenTtlHours; }
    }

    public static class Storage {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
    }

    public static class RocketMq {
        private boolean enabled;
        private String transcriptionTopic;
        private String documentTopic;
        private String knowledgeTopic;
        private String analysisTopic;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTranscriptionTopic() { return transcriptionTopic; }
        public void setTranscriptionTopic(String transcriptionTopic) { this.transcriptionTopic = transcriptionTopic; }
        public String getDocumentTopic() { return documentTopic; }
        public void setDocumentTopic(String documentTopic) { this.documentTopic = documentTopic; }
        public String getKnowledgeTopic() { return knowledgeTopic; }
        public void setKnowledgeTopic(String knowledgeTopic) { this.knowledgeTopic = knowledgeTopic; }
        public String getAnalysisTopic() { return analysisTopic; }
        public void setAnalysisTopic(String analysisTopic) { this.analysisTopic = analysisTopic; }
    }

    public static class Dashscope {
        private boolean enabled;
        private String apiKey;
        private String baseUrl;
        private String asrModel;
        private String chatModel;
        private String embeddingModel;
        private int embeddingDimension = 1024;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        /** ASR and embedding use DashScope's native API. */
        public String getApiBaseUrl() { return baseUrlFor("/api/v1"); }
        /** Chat completions use DashScope's OpenAI-compatible API. */
        public String getCompatibleBaseUrl() { return baseUrlFor("/compatible-mode/v1"); }
        private String baseUrlFor(String targetSuffix) {
            if (baseUrl == null || baseUrl.isBlank()) throw new IllegalStateException("DASHSCOPE_BASE_URL must be configured");
            String normalized = baseUrl.trim().replaceAll("/+$", "");
            if (normalized.endsWith("/api/v1")) {
                return normalized.substring(0, normalized.length() - "/api/v1".length()) + targetSuffix;
            }
            if (normalized.endsWith("/compatible-mode/v1")) {
                return normalized.substring(0, normalized.length() - "/compatible-mode/v1".length()) + targetSuffix;
            }
            throw new IllegalStateException("DASHSCOPE_BASE_URL must end with /api/v1 or /compatible-mode/v1");
        }
        public String getAsrModel() { return asrModel; }
        public void setAsrModel(String asrModel) { this.asrModel = asrModel; }
        public String getChatModel() { return chatModel; }
        public void setChatModel(String chatModel) { this.chatModel = chatModel; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public int getEmbeddingDimension() { return embeddingDimension; }
        public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    }

    public static class Workers {
        private boolean enabled;
        private long pollIntervalMs;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    }

    public static class Knowledge {
        private boolean enabled;
        private String qdrantUrl;
        private String qdrantApiKey;
        private String collection = "voicenote_knowledge";
        private int chunkCharacters = 2000;
        private int shortTopicTokens = 200;
        private int chunkTargetTokens = 800;
        private int chunkMaxTokens = 1200;
        private int retrievalPrefetchLimit = 50;
        private int retrievalSeedLimit = 4;
        private int retrievalContextMaxChunks = 12;
        private int retrievalContextMaxTokens = 10_000;
        private boolean rerankEnabled;
        private String rerankModel = "qwen3-rerank";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getQdrantUrl() { return qdrantUrl; }
        public void setQdrantUrl(String qdrantUrl) { this.qdrantUrl = qdrantUrl; }
        public String getQdrantApiKey() { return qdrantApiKey; }
        public void setQdrantApiKey(String qdrantApiKey) { this.qdrantApiKey = qdrantApiKey; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public int getChunkCharacters() { return chunkCharacters; }
        public void setChunkCharacters(int chunkCharacters) { this.chunkCharacters = chunkCharacters; }
        public int getShortTopicTokens() { return shortTopicTokens; }
        public void setShortTopicTokens(int shortTopicTokens) { this.shortTopicTokens = shortTopicTokens; }
        public int getChunkTargetTokens() { return chunkTargetTokens; }
        public void setChunkTargetTokens(int chunkTargetTokens) { this.chunkTargetTokens = chunkTargetTokens; }
        public int getChunkMaxTokens() { return chunkMaxTokens; }
        public void setChunkMaxTokens(int chunkMaxTokens) { this.chunkMaxTokens = chunkMaxTokens; }
        public int getRetrievalPrefetchLimit() { return retrievalPrefetchLimit; }
        public void setRetrievalPrefetchLimit(int retrievalPrefetchLimit) { this.retrievalPrefetchLimit = retrievalPrefetchLimit; }
        public int getRetrievalSeedLimit() { return retrievalSeedLimit; }
        public void setRetrievalSeedLimit(int retrievalSeedLimit) { this.retrievalSeedLimit = retrievalSeedLimit; }
        public int getRetrievalContextMaxChunks() { return retrievalContextMaxChunks; }
        public void setRetrievalContextMaxChunks(int retrievalContextMaxChunks) { this.retrievalContextMaxChunks = retrievalContextMaxChunks; }
        public int getRetrievalContextMaxTokens() { return retrievalContextMaxTokens; }
        public void setRetrievalContextMaxTokens(int retrievalContextMaxTokens) { this.retrievalContextMaxTokens = retrievalContextMaxTokens; }
        public boolean isRerankEnabled() { return rerankEnabled; }
        public void setRerankEnabled(boolean rerankEnabled) { this.rerankEnabled = rerankEnabled; }
        public String getRerankModel() { return rerankModel; }
        public void setRerankModel(String rerankModel) { this.rerankModel = rerankModel; }
    }

    public static class Agent {
        private boolean enabled;
        private int maxScopeDocuments = 50;
        private int maxModelCalls = 7;
        private int maxTurns = 6;
        private int maxToolCalls = 10;
        private int timeoutSeconds = 120;
        private int maxToolOutputBytes = 32_768;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxScopeDocuments() { return maxScopeDocuments; }
        public void setMaxScopeDocuments(int maxScopeDocuments) { this.maxScopeDocuments = maxScopeDocuments; }
        public int getMaxModelCalls() { return maxModelCalls; }
        public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls; }
        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
        public int getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxToolOutputBytes() { return maxToolOutputBytes; }
        public void setMaxToolOutputBytes(int maxToolOutputBytes) { this.maxToolOutputBytes = maxToolOutputBytes; }
    }

    public static class Mcp {
        private boolean enabled;
        private String servers = "[]";
        private int requestTimeoutSeconds = 10;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getServers() { return servers; }
        public void setServers(String servers) { this.servers = servers; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    }
}

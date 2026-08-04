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

    public Security getSecurity() { return security; }
    public Storage getStorage() { return storage; }
    public RocketMq getRocketmq() { return rocketmq; }
    public Dashscope getDashscope() { return dashscope; }
    public Workers getWorkers() { return workers; }
    public Knowledge getKnowledge() { return knowledge; }

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
        private String collection = "voicenote_knowledge";
        private int chunkCharacters = 2000;
        private int chunkTargetTokens = 800;
        private int chunkMaxTokens = 1200;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getQdrantUrl() { return qdrantUrl; }
        public void setQdrantUrl(String qdrantUrl) { this.qdrantUrl = qdrantUrl; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public int getChunkCharacters() { return chunkCharacters; }
        public void setChunkCharacters(int chunkCharacters) { this.chunkCharacters = chunkCharacters; }
        public int getChunkTargetTokens() { return chunkTargetTokens; }
        public void setChunkTargetTokens(int chunkTargetTokens) { this.chunkTargetTokens = chunkTargetTokens; }
        public int getChunkMaxTokens() { return chunkMaxTokens; }
        public void setChunkMaxTokens(int chunkMaxTokens) { this.chunkMaxTokens = chunkMaxTokens; }
    }
}

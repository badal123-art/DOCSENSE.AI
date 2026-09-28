package com.example.chatbot;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

@Service
public class RAGService {

    private static final int MAX_EMBEDDING_BATCH_SIZE = 25;
    private static final long MIN_EMBEDDING_REQUEST_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Object embeddingRequestLock = new Object();
    private long lastEmbeddingRequestNanos;

    public RAGService() {
        String openRouterKey = configuredKey("OPENROUTER_API_KEY");
        if (!openRouterKey.isEmpty()) {
            this.chatModel = dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .apiKey(openRouterKey)
                .baseUrl("https://openrouter.ai/api/v1")
                .modelName("openai/gpt-4o-mini")
                .temperature(0.0)
                .build();
            this.embeddingModel = dev.langchain4j.model.openai.OpenAiEmbeddingModel.builder()
                .apiKey(openRouterKey)
                .baseUrl("https://openrouter.ai/api/v1")
                .modelName("openai/text-embedding-3-small")
                .build();
        } else {
            String googleKey = configuredKey("GOOGLE_API_KEY");
            if (googleKey.isEmpty()) {
                System.err.println("WARNING: Set OPENROUTER_API_KEY or GOOGLE_API_KEY before uploading documents.");
                googleKey = "dummy";
            }
            this.chatModel = dev.langchain4j.model.googleai.GoogleAiGeminiChatModel.builder()
                .apiKey(googleKey)
                .modelName("gemini-3.6-flash")
                .temperature(0.0)
                .build();
            this.embeddingModel = dev.langchain4j.model.googleai.GoogleAiEmbeddingModel.builder()
                .apiKey(googleKey)
                .modelName("gemini-embedding-001")
                .build();
        }

        this.embeddingStore = new InMemoryEmbeddingStore<>();
    }

    private String configuredKey(String name) {
        String key = System.getProperty(name);
        if (key == null || key.isBlank()) {
            key = System.getenv(name);
        }
        return key == null ? "" : key.trim();
    }

    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }

    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    public List<Embedding> embedAll(List<TextSegment> segments) throws InterruptedException {
        List<Embedding> embeddings = new ArrayList<>();
        for (int start = 0; start < segments.size(); start += MAX_EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + MAX_EMBEDDING_BATCH_SIZE, segments.size());
            List<Embedding> batchEmbeddings = embedBatchWithRetry(segments.subList(start, end));
            embeddings.addAll(batchEmbeddings);
        }
        return embeddings;
    }

    public Embedding embedQuery(String query) throws InterruptedException {
        int maxRetries = 4;
        long backoffMillis = 5000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            synchronized (embeddingRequestLock) {
                waitForEmbeddingRequestSlot();
                try {
                    return embeddingModel.embed(query).content();
                } catch (Exception e) {
                    if (isQuotaOrRateLimitException(e) && attempt < maxRetries) {
                        long waitTimeMs = extractRetryDelayMs(e, backoffMillis);
                        System.out.printf("Query embedding quota hit (attempt %d/%d). Waiting %d ms...%n", attempt, maxRetries, waitTimeMs);
                        TimeUnit.MILLISECONDS.sleep(waitTimeMs);
                        backoffMillis = Math.min(backoffMillis * 2, 35000);
                        continue;
                    }
                    throw e;
                }
            }
        }
        throw new RuntimeException("Failed to embed query after " + maxRetries + " attempts.");
    }

    private List<Embedding> embedBatchWithRetry(List<TextSegment> batch) throws InterruptedException {
        int maxRetries = 5;
        long backoffMillis = 15000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            synchronized (embeddingRequestLock) {
                waitForEmbeddingRequestSlot();
                try {
                    return embeddingModel.embedAll(batch).content();
                } catch (Exception e) {
                    if (isQuotaOrRateLimitException(e) && attempt < maxRetries) {
                        long waitTimeMs = extractRetryDelayMs(e, backoffMillis);
                        System.out.printf("Embedding quota reached (attempt %d/%d). Waiting %d ms for quota window to reset...%n",
                                attempt, maxRetries, waitTimeMs);
                        TimeUnit.MILLISECONDS.sleep(waitTimeMs);
                        backoffMillis = Math.min(backoffMillis * 2, 60000);
                        continue;
                    }
                    throw e;
                }
            }
        }
        throw new RuntimeException("Failed to embed document chunks after " + maxRetries + " attempts due to rate limit.");
    }

    private boolean isQuotaOrRateLimitException(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("429") ||
                    lower.contains("resource_exhausted") ||
                    lower.contains("quota") ||
                    lower.contains("rate limit")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private long extractRetryDelayMs(Throwable t, long defaultMs) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("retry\\s+(?:in|after)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*s", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(msg);
                if (m.find()) {
                    try {
                        double seconds = Double.parseDouble(m.group(1));
                        return (long) (Math.ceil(seconds) * 1000) + 2000;
                    } catch (Exception ignored) {}
                }
                m = java.util.regex.Pattern.compile("\"retryDelay\"\\s*:\\s*\"([0-9]+)s\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(msg);
                if (m.find()) {
                    try {
                        long seconds = Long.parseLong(m.group(1));
                        return (seconds * 1000) + 2000;
                    } catch (Exception ignored) {}
                }
            }
            t = t.getCause();
        }
        return defaultMs;
    }

    private void waitForEmbeddingRequestSlot() throws InterruptedException {
        if (lastEmbeddingRequestNanos > 0) {
            long elapsed = System.nanoTime() - lastEmbeddingRequestNanos;
            long remaining = MIN_EMBEDDING_REQUEST_INTERVAL_NANOS - elapsed;
            if (remaining > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
        lastEmbeddingRequestNanos = System.nanoTime();
    }

    public int getChunkCount() {
        return -1; // To be tracked externally if needed
    }

    public String generateAnswer(String query) throws InterruptedException {
        var queryEmbedding = embedQuery(query);

        dev.langchain4j.store.embedding.EmbeddingSearchRequest request = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(6)
            .minScore(0.25)
            .build();
            
        List<EmbeddingMatch<TextSegment>> relevant = embeddingStore.search(request).matches();

        if (relevant.isEmpty()) {
            return "❗ No relevant information found in the indexed documents.";
        }

        String context = relevant.stream()
                .map(match -> "[SOURCE: " + match.embedded().metadata().getString("source") + "]\n" + match.embedded().text())
                .collect(Collectors.joining("\n\n"));

        String prompt = "You are a STRICT PDF-only AI assistant.\n\n" +
                "⚠️ FOLLOW THESE RULES EXACTLY:\n\n" +
                "1. You MUST answer ONLY using the PDF context.\n" +
                "2. If the exact answer is NOT in the PDF, respond EXACTLY:\n" +
                "   \"Not found in PDF.\"\n" +
                "3. You MUST extract mathematical expressions, formulas, and equations\n" +
                "   EXACTLY as they appear in the PDF (including symbols, fractions, etc.).\n" +
                "4. Do NOT skip formulas. Do NOT paraphrase formulas.\n" +
                "5. Do NOT guess or use outside knowledge.\n" +
                "6. Do NOT say things like \"You didn't ask a question.\"\n" +
                "7. If it is helpful, you may mention the SOURCE tag shown before each context block.\n\n" +
                "📄 PDF CONTEXT:\n" + context + "\n\n" +
                "❓ USER QUESTION:\n" + query + "\n\n" +
                "🔎 FINAL ANSWER (PDF-ONLY, INCLUDE FORMULAS IF PRESENT):";

        String response = chatModel.chat(prompt);
        return response + "|||" + relevant.get(0).embedded().metadata().getString("source");
    }
}

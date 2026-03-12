package com.globalpulse.news.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

@Service
public class GroqSummarizer {

    private static final Logger log = Logger.getLogger(GroqSummarizer.class.getName());
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final List<String> VALID_CATEGORIES = List.of(
        "Brasil", "Mundo", "Politica", "Economia", "Negocios",
        "Tecnologia", "Esportes", "Ciencia", "Saude", "Educacao",
        "Entretenimento", "Cotidiano", "Justica", "Cultura", "Geral"
    );

    public record AiResult(String title, String description, String categories) {
        public boolean isValid() {
            return title != null && !title.isBlank()
                && description != null && !description.isBlank()
                && categories != null && !categories.isBlank();
        }
    }

    @Value("${ai.groq.model:llama-3.1-8b-instant}")
    private String model;

    @Value("${ai.groq.timeoutSeconds:60}")
    private int timeoutSeconds;

    @Value("${ai.groq.maxCompletionTokens:900}")
    private int maxCompletionTokens;

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final GroqRateLimiter rateLimiter;

    public GroqSummarizer(ObjectMapper mapper, GroqRateLimiter rateLimiter) {
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    public AiResult generate(String originalTitle, String contentText, String originalCategory) {
        String text = contentText == null ? "" : contentText.trim();
        if (text.isBlank()) return null;

        if (text.length() > 10_000) {
            String start = text.substring(0, 7_000);
            String end = text.substring(text.length() - 2_000);
            text = start + "\n\n[...]\n\n" + end;
            log.info("[GROQ] Artigo longo reduzido para " + text.length() + " chars");
        }

        int estimatedTokens = Math.min(1800, Math.max(900, (text.length() / 5) + 500));

        try {
            String activeKey = rateLimiter.acquire("GroqSummarizer", estimatedTokens);
            int[] usage = new int[]{0, 0};
            try {
                String body = buildRequestBody(originalTitle, text, originalCategory);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + activeKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

                log.info("[GROQ] Processando: \"" + safe(originalTitle) + "\" | chars=" + text.length());
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    rateLimiter.report429(activeKey);
                    log.warning("[GROQ] 429 recebido - key penalizada, proximo ciclo usara outra");
                    return null;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warning("[GROQ] HTTP " + response.statusCode() + " | " + preview(response.body()));
                    return null;
                }

                JsonNode root = mapper.readTree(response.body());
                JsonNode usageNode = root.path("usage");
                usage[0] = usageNode.path("prompt_tokens").asInt(0);
                usage[1] = usageNode.path("completion_tokens").asInt(0);

                String raw = root.path("choices").path(0).path("message").path("content").asText("").trim();
                return parseResult(raw, originalTitle, originalCategory);
            } finally {
                rateLimiter.release(usage[0], usage[1]);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warning("[GROQ] Falhou: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
    }

    private String buildRequestBody(String originalTitle, String content, String originalCategory) throws Exception {
        var messages = mapper.createArrayNode();
        messages.addObject()
            .put("role", "system")
            .put("content", systemPrompt());
        messages.addObject()
            .put("role", "user")
            .put("content", userPrompt(originalTitle, content, originalCategory));

        var payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.set("messages", messages);
        payload.put("temperature", 0.2);
        payload.put("max_tokens", maxCompletionTokens);
        payload.put("stream", false);
        return mapper.writeValueAsString(payload);
    }

    private String systemPrompt() {
        return "Voce e um editor de noticias. Responda apenas com JSON valido. "
            + "Reescreva o titulo sem clickbait, gere uma descricao objetiva de 3 a 5 frases e classifique em 1 a 3 categorias.";
    }

    private String userPrompt(String originalTitle, String content, String originalCategory) {
        return "Titulo original: " + safe(originalTitle) + "\n"
            + "Categoria original: " + (originalCategory == null || originalCategory.isBlank() ? "Geral" : originalCategory) + "\n\n"
            + "Texto base:\n" + content + "\n\n"
            + "Responda somente neste formato JSON: "
            + "{\"title\":\"...\",\"description\":\"...\",\"categories\":[\"...\"]}";
    }

    private AiResult parseResult(String raw, String originalTitle, String originalCategory) {
        if (raw == null || raw.isBlank()) return null;

        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warning("[GROQ] Resposta sem JSON: " + preview(raw));
            return null;
        }

        try {
            ObjectMapper lenient = mapper.copy()
                .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
            JsonNode node = lenient.readTree(raw.substring(start, end + 1));

            String title = node.path("title").asText("").trim();
            String description = node.path("description").asText("").trim();
            String categories = parseCategories(node.path("categories"), originalCategory);

            if (title.length() < 10) title = fallbackTitle(originalTitle);
            if (description.length() < 40) return null;

            log.info("[GROQ] OK -> \"" + safe(title) + "\" | desc=" + description.length() + " | cats=" + categories);
            return new AiResult(title, description, categories);
        } catch (Exception e) {
            log.warning("[GROQ] Parse falhou: " + e.getMessage());
            return null;
        }
    }

    private String parseCategories(JsonNode node, String originalCategory) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode child : node) {
                String candidate = child.asText("").trim();
                for (String valid : VALID_CATEGORIES) {
                    if (valid.equalsIgnoreCase(candidate) && !result.contains(valid)) {
                        result.add(valid);
                        break;
                    }
                }
                if (result.size() >= 3) break;
            }
        }

        if (originalCategory != null) {
            for (String valid : VALID_CATEGORIES) {
                if (valid.equalsIgnoreCase(originalCategory) && !result.contains(valid)) {
                    result.add(0, valid);
                    break;
                }
            }
        }

        if (!result.contains("Geral")) result.add("Geral");
        return String.join(",", result);
    }

    private String fallbackTitle(String originalTitle) {
        if (originalTitle == null || originalTitle.isBlank()) return "Noticia";
        return originalTitle.length() > 90 ? originalTitle.substring(0, 87) + "..." : originalTitle;
    }

    private String safe(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() > 100 ? normalized.substring(0, 100) + "..." : normalized;
    }

    private String preview(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 180 ? compact.substring(0, 180) + "..." : compact;
    }
}
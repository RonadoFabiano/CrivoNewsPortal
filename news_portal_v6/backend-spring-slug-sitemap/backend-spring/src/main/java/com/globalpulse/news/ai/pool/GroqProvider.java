package com.globalpulse.news.ai.pool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * GroqProvider — implementação do LlmProvider para a API Groq.
 *
 * Strategy Pattern: troca por OpenAiProvider ou OllamaProvider
 * sem mudar nada no código que usa LlmProvider.
 */
@Component
public class GroqProvider implements com.globalpulse.news.ai.pool.LlmProvider {

    private static final Logger log = Logger.getLogger(GroqProvider.class.getName());
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    @Value("${ai.groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${ai.groq.timeoutSeconds:90}")
    private int timeoutSeconds;

    private final HttpClient  httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public String providerName() { return "groq"; }

    @Override
    public String complete(String apiKey, String systemPrompt, String userPrompt, int maxTokens)
            throws Exception {

        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.3);

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 429) {
            throw new ThrottledException("429 Groq throttle: " + res.body());
        }
        if (res.statusCode() != 200) {
            throw new RuntimeException("Groq HTTP " + res.statusCode() + ": " + res.body());
        }

        JsonNode root = om.readTree(res.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    /** Sinaliza throttling para o pool penalizar a key */
    public static class ThrottledException extends RuntimeException {
        public ThrottledException(String msg) { super(msg); }
    }
}

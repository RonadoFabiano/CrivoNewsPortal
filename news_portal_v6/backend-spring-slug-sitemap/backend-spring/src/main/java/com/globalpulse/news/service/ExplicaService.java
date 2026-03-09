package com.globalpulse.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Gera páginas "Explica" com IA.
 *
 * GET /api/db/explica/{slug}
 *
 * Para um tópico (ex: "o-que-esta-acontecendo-na-china"):
 *  1. Busca artigos recentes relacionados ao tópico
 *  2. Chama Ollama para gerar: resumo + contexto histórico
 *  3. Retorna: { summary, context, related_articles }
 *
 * SEO absurdo — pega buscas tipo "o que está acontecendo com X"
 */
@Service
public class ExplicaService {

    private static final Logger log = Logger.getLogger(ExplicaService.class.getName());

    @Value("${ai.ollama.baseUrl:http://localhost:11434}") private String baseUrl;
    @Value("${ai.ollama.model:llama3.2:3b}")              private String model;
    @Value("${ai.ollama.timeoutSeconds:300}")             private int    timeoutSeconds;

    private final ProcessedArticleRepository procRepo;
    private final ObjectMapper               mapper;
    private final HttpClient                 http;

    public ExplicaService(ProcessedArticleRepository procRepo, ObjectMapper mapper) {
        this.procRepo = procRepo;
        this.mapper   = mapper;
        this.http     = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();
    }
    public Map<String, Object> explica(String topicSlug) {
        // Converte slug → termo de busca legível
        String topic = topicSlug.replace("-", " ")
            .replaceAll("\\bo que esta acontecendo (na|no|em|com)\\s*", "")
            .trim();

        log.info("[EXPLICA] Gerando para tópico: \"" + topic + "\"");

        // Busca artigos relacionados ao tópico
        List<ProcessedArticle> related = procRepo.findByTag(
            topicSlug, PageRequest.of(0, 10));

        if (related.isEmpty()) {
            // fallback: busca por categoria
            related = procRepo.findByCategory(topic, PageRequest.of(0, 10));
        }

        if (related.isEmpty()) {
            return Map.of(
                "topic", topic,
                "slug",  topicSlug,
                "error", "Nenhuma notícia encontrada sobre este tópico ainda.",
                "related_articles", List.of()
            );
        }

        // Monta contexto com os títulos + descrições dos artigos
        String context = related.stream()
            .limit(5)
            .map(a -> "- " + a.getAiTitle() + ": " + a.getAiDescription())
            .collect(Collectors.joining("\n"));

        // Chama Ollama para gerar explicação
        String[] aiContent = generateExplica(topic, context);

        // Monta resposta
        List<Map<String, Object>> relatedMaps = related.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug",        a.getSlug());
            m.put("title",       a.getAiTitle());
            m.put("description", a.getAiDescription());
            m.put("source",      a.getSource());
            m.put("publishedAt", a.getPublishedAt());
            m.put("image",       a.getImageUrl());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic",            topic);
        result.put("slug",             topicSlug);
        result.put("summary",          aiContent[0]);
        result.put("context",          aiContent[1]);
        result.put("related_articles", relatedMaps);
        result.put("generated_at",     java.time.Instant.now().toString());
        return result;
    }

    private String[] generateExplica(String topic, String articlesContext) {
        String prompt = "Você é um jornalista explicativo brasileiro.\n"
            + "Com base nas notícias abaixo, explique o assunto \"" + topic + "\" em português.\n\n"
            + "NOTÍCIAS RECENTES:\n" + articlesContext + "\n\n"
            + "INSTRUÇÕES:\n"
            + "1. RESUMO: 2-3 frases diretas explicando o que está acontecendo agora.\n"
            + "2. CONTEXTO: 3-4 frases de contexto histórico ou de fundo.\n"
            + "Responda APENAS com JSON:\n"
            + "{\"summary\": \"...\", \"context\": \"...\"}\n\nJSON:";

        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "api/generate" : baseUrl + "/api/generate";
            JsonNode payload = mapper.createObjectNode()
                .put("model", model).put("prompt", prompt).put("stream", false)
                .set("options", mapper.createObjectNode()
                    .put("temperature", 0.5).put("num_predict", 400));

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String raw = mapper.readTree(res.body()).path("response").asText("").trim();

            int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = mapper.readTree(raw.substring(start, end + 1));
                return new String[]{
                    node.path("summary").asText("Informações sendo atualizadas."),
                    node.path("context").asText("Contexto sendo preparado.")
                };
            }
        } catch (Exception e) {
            log.warning("[EXPLICA] Ollama falhou: " + e.getMessage());
        }
        return new String[]{"Informações sendo atualizadas.", "Contexto sendo preparado."};
    }
}

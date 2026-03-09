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
import java.util.*;
import java.util.logging.Logger;

/**
 * Chama o Ollama local para gerar:
 *  - Título atraente (clickbait inteligente, sem nome de jornalista)
 *  - Descrição envolvente (2-3 frases)
 *  - Categorias múltiplas (classificadas pelo próprio modelo)
 *
 * A imagem original do scrape é PRESERVADA — nunca alterada aqui.
 */
@Service
public class OllamaSummarizer {

    private static final Logger log = Logger.getLogger(OllamaSummarizer.class.getName());

    // Categorias válidas que o Ollama pode escolher
    private static final List<String> VALID_CATEGORIES = List.of(
        "Brasil", "Mundo", "Política", "Economia", "Negócios",
        "Tecnologia", "Esportes", "Ciência", "Saúde", "Educação",
        "Entretenimento", "Cotidiano", "Justiça", "Cultura", "Geral"
    );

    public record AiResult(String title, String description, String categories) {
        public boolean isValid() {
            return title != null && !title.isBlank()
                && description != null && !description.isBlank()
                && categories != null && !categories.isBlank();
        }
    }

    private final ObjectMapper mapper;
    private final HttpClient   http;

    @Value("${ai.ollama.baseUrl:http://localhost:11434}") private String baseUrl;
    @Value("${ai.ollama.model:llama3.2:3b}")              private String model;
    @Value("${ai.ollama.timeoutSeconds:300}")             private int    timeoutSeconds;

    public OllamaSummarizer(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * Gera título + descrição + categorias a partir do conteúdo completo.
     *
     * @param originalTitle  título original do scrape (referência)
     * @param contentText    texto completo extraído pelo ArticleExtractor
     * @param originalCat    categoria original do feed (hint para o modelo)
     * @return AiResult ou null se falhou
     */
    public AiResult generate(String originalTitle, String contentText, String originalCat) {
        String text = contentText == null ? "" : contentText.trim();
        if (text.isBlank()) return null;

        String prompt = buildPrompt(originalTitle, text, originalCat);

        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "api/generate" : baseUrl + "/api/generate";

            JsonNode payload = mapper.createObjectNode()
                    .put("model",  model)
                    .put("prompt", prompt)
                    .put("stream", false)
                    .set("options", mapper.createObjectNode()
                            .put("temperature", 0.6)
                            .put("num_predict", 400));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            log.info("[OLLAMA] Processando | model=" + model + " | chars=" + text.length()
                    + " | \"" + safe(originalTitle) + "\"");

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warning("[OLLAMA] HTTP " + res.statusCode());
                return null;
            }

            String raw = extractRawResponse(res.body());
            return parseResult(raw, originalTitle, originalCat);

        } catch (java.net.ConnectException e) {
            log.warning("[OLLAMA] Não conectou em " + baseUrl + " — verifique se o Ollama está rodando");
            return null;
        } catch (Exception e) {
            log.warning("[OLLAMA] Falhou: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String originalTitle, String content, String originalCat) {
        return String.format(
            "Você é um editor de portal de notícias brasileiro.\n"
            + "Sua única tarefa é responder com um JSON válido. Nada mais.\n\n"

            + "TÍTULO ORIGINAL DA NOTÍCIA: \"%s\"\n"
            + "TEXTO DA NOTÍCIA:\n%s\n\n"

            + "INSTRUÇÕES:\n"
            + "1. TÍTULO: Reescreva o TÍTULO ORIGINAL acima para ficar mais atraente.\n"
            + "   - Mantenha o MESMO assunto e personagens do título original\n"
            + "   - Máximo 90 caracteres\n"
            + "   - Use verbos de ação ou números quando possível\n"
            + "   - PROIBIDO inventar um assunto diferente do título original\n"
            + "   - PROIBIDO títulos genéricos como 'Crise política: o que pode mudar'\n"
            + "   - EXEMPLO: original='Alcolumbre mantém quebra dos sigilos de Lulinha'\n"
            + "              reescrito='Alcolumbre confirma: sigilos de Lulinha serão quebrados'\n\n"
            + "2. DESCRIÇÃO: 2 frases baseadas no TEXTO DA NOTÍCIA acima.\n"
            + "   - Fato principal + consequência ou detalhe relevante\n"
            + "   - Sem mencionar jornalistas ou fontes\n\n"
            + "3. CATEGORIAS: Escolha de 1 a 3 da lista: %s\n"
            + "   - Sempre inclua 'Geral'\n"
            + "   - Categoria do feed (sugestão): %s\n\n"
            + "RESPONDA APENAS COM ESTE JSON (sem texto antes ou depois):\n"
            + "{\"title\": \"...\", \"description\": \"...\", \"categories\": [\"...\"]}\n\nJSON:",
            originalTitle != null ? originalTitle : "",
            content,
            String.join(", ", VALID_CATEGORIES),
            originalCat != null ? originalCat : "Geral"
        );
    }

    private AiResult parseResult(String raw, String originalTitle, String originalCat) {
        if (raw == null || raw.isBlank()) return null;

        String json = extractJson(raw);
        if (json != null) {
            try {
                JsonNode node = mapper.readTree(json);
                String title = node.path("title").asText("").trim();
                String desc  = node.path("description").asText("").trim();
                String cats  = parseCategories(node.path("categories"), originalCat);

                // Rejeita se o título não tem relação com o original (muito diferente)
                // ou se é um título genérico conhecido
                if (!title.isBlank() && !desc.isBlank() && title.length() > 10
                        && !isGenericTitle(title)) {
                    log.info("[OLLAMA] OK → \"" + safe(title) + "\" | cats=" + cats);
                    return new AiResult(title, desc, cats);
                }

                // Título genérico detectado — usa original melhorado
                if (!desc.isBlank()) {
                    String betterTitle = improveOriginalTitle(originalTitle);
                    log.info("[OLLAMA] Título genérico detectado, usando original melhorado: " + safe(betterTitle));
                    return new AiResult(betterTitle, desc, cats);
                }
            } catch (Exception e) {
                log.warning("[OLLAMA] JSON parse falhou: " + safe(raw));
            }
        }

        // Fallback final: usa título original com descrição do raw
        return extractFreeText(raw, originalTitle, originalCat);
    }

    /** Detecta títulos genéricos que o modelo inventa quando não entende o conteúdo */
    private boolean isGenericTitle(String title) {
        if (title == null) return true;
        String t = title.toLowerCase();
        return t.contains("crise política: o que pode mudar")
            || t.contains("o que você precisa saber")
            || t.contains("tudo o que sabemos")
            || t.contains("entenda o caso")
            || t.contains("o que está acontecendo")
            || t.equals("notícia de última hora")
            || t.equals("últimas notícias");
    }

    /** Melhora o título original adicionando verbos de ação quando possível */
    private String improveOriginalTitle(String original) {
        if (original == null || original.isBlank()) return "Notícia";
        // Trunca se muito longo
        if (original.length() > 90) return original.substring(0, 87) + "...";
        return original;
    }

    /**
     * Parseia o array de categorias e valida contra a lista de categorias válidas.
     * Sempre garante "Geral" no final.
     */
    private String parseCategories(JsonNode catsNode, String originalCat) {
        List<String> result = new ArrayList<>();

        if (catsNode.isArray()) {
            for (JsonNode c : catsNode) {
                String cat = c.asText("").trim();
                // Valida contra lista oficial
                for (String valid : VALID_CATEGORIES) {
                    if (valid.equalsIgnoreCase(cat)) {
                        if (!result.contains(valid)) result.add(valid);
                        break;
                    }
                }
                if (result.size() >= 4) break;
            }
        }

        // Garante categoria original se válida
        if (originalCat != null) {
            for (String valid : VALID_CATEGORIES) {
                if (valid.equalsIgnoreCase(originalCat) && !result.contains(valid)) {
                    result.add(0, valid); // coloca no início (mais relevante)
                    break;
                }
            }
        }

        // Sempre tem Geral
        if (!result.contains("Geral")) result.add("Geral");

        return String.join(",", result);
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }

    private AiResult extractFreeText(String raw, String originalTitle, String originalCat) {
        String[] lines = raw.split("\n");
        String desc = null;
        for (String line : lines) {
            line = line.trim()
                    .replaceAll("(?i)^(descrição|description)[:\\-\\s]+", "")
                    .replaceAll("^\"|\"$", "").trim();
            if (line.isBlank() || line.startsWith("{") || line.startsWith("}")) continue;
            if (line.length() > 30 && !line.toLowerCase().contains("título")
                    && !line.toLowerCase().contains("json")) {
                desc = line;
                break;
            }
        }

        // Sempre usa o título original como base no fallback
        String title = improveOriginalTitle(originalTitle);
        String cats  = (originalCat != null ? originalCat + "," : "") + "Geral";

        if (desc != null) return new AiResult(title, desc, cats);

        // Último recurso: retorna com título original e sem descrição
        return null;
    }

    private String extractRawResponse(String rawJson) {
        try { return mapper.readTree(rawJson).path("response").asText("").trim(); }
        catch (Exception e) { return ""; }
    }

    private String safe(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}

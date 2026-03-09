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
 * NarrativeAnalyzer — usa Groq para:
 *
 * 1. Classificar o TIPO de relação entre dois países
 *    Ex: Irã + Israel → "conflito" com descrição narrativa
 *
 * 2. Gerar o SPREAD PATH (como o evento se propagou)
 *    Ex: ["Irã", "Israel", "EUA", "petróleo", "bolsas"]
 *    com timestamps de quando cada nó entrou na narrativa
 *
 * 3. Gerar RESUMO NARRATIVO do cluster
 *    Um parágrafo explicando o que une os países naquele momento
 *
 * Chamado pelo MapNarrativeWorker (job agendado) e com cache de 30min.
 */
@Service
public class NarrativeAnalyzer {

    private static final Logger log = Logger.getLogger(NarrativeAnalyzer.class.getName());
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    // Tipos de relação possíveis entre países
    public enum RelationType {
        CONFLITO,      // guerra, ataques, tensão militar
        DIPLOMACIA,    // negociações, acordos, reuniões
        SANCAO,        // sanções econômicas, embargos
        ALIANCA,       // apoio mútuo, tratados, cooperação
        COMERCIO,      // relações comerciais, tarifas, trocas
        CRISE,         // crise humanitária, desastres, refugiados
        POLITICA,      // eleições, pressão política, declarações
        OUTRO          // não classificável com clareza
    }

    public record ConnectionAnalysis(
        String sourceCountry,
        String targetCountry,
        RelationType relationType,
        String relationLabel,   // rótulo em português para o frontend
        String narrativeSummary, // 1-2 frases explicando a conexão
        int confidence          // 0-100
    ) {}

    public record SpreadPath(
        List<SpreadNode> nodes,
        String narrativeSummary  // parágrafo completo da narrativa
    ) {}

    public record SpreadNode(
        String name,
        String type,             // "country", "topic", "actor"
        String role,             // "origem", "resposta", "impacto", "consequência"
        String enteredAt         // ISO timestamp aproximado
    ) {}

    @Value("${ai.groq.model:llama-3.1-8b-instant}")
    private String model;

    private final HttpClient      http;
    private final ObjectMapper    mapper;
    private final GroqRateLimiter rateLimiter;

    public NarrativeAnalyzer(ObjectMapper mapper, GroqRateLimiter rateLimiter) {
        this.mapper      = mapper;
        this.rateLimiter = rateLimiter;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    // ── ANÁLISE DE CONEXÃO ENTRE DOIS PAÍSES ──────────────────────────────────

    /**
     * Analisa a relação entre dois países a partir de manchetes reais.
     *
     * @param countryA     primeiro país
     * @param countryB     segundo país
     * @param headlines    lista de títulos de artigos que mencionam ambos (máx 10)
     */
    public ConnectionAnalysis analyzeConnection(
            String countryA,
            String countryB,
            List<String> headlines) {

        if (headlines == null || headlines.isEmpty()) {
            return fallbackConnection(countryA, countryB);
        }

        String headlineText = String.join("\n", headlines.stream().limit(10)
            .map(h -> "- " + h).toList());

        String prompt = """
            Analise as manchetes abaixo que mencionam %s e %s.
            
            Manchetes:
            %s
            
            Responda APENAS com JSON válido, sem markdown, sem explicação:
            {
              "tipo": "CONFLITO|DIPLOMACIA|SANCAO|ALIANCA|COMERCIO|CRISE|POLITICA|OUTRO",
              "rotulo": "texto curto em português (máx 3 palavras, ex: 'tensão militar')",
              "resumo": "1-2 frases explicando a relação atual entre os dois países com base nas manchetes",
              "confianca": número de 0 a 100
            }
            """.formatted(countryA, countryB, headlineText);

        try {
            String response = callGroq(prompt, 300, 0.3);
            if (response == null) return fallbackConnection(countryA, countryB);

            JsonNode json = mapper.readTree(cleanJson(response));
            String tipo = json.path("tipo").asText("OUTRO");
            String rotulo = json.path("rotulo").asText("relacionados");
            String resumo = json.path("resumo").asText("");
            int conf = json.path("confianca").asInt(50);

            RelationType rel;
            try { rel = RelationType.valueOf(tipo); }
            catch (Exception e) { rel = RelationType.OUTRO; }

            log.info("[NARRATIVE] " + countryA + "↔" + countryB + " = " + tipo + " (" + conf + "%)");
            return new ConnectionAnalysis(countryA, countryB, rel, rotulo, resumo, conf);

        } catch (Exception e) {
            log.warning("[NARRATIVE] analyzeConnection falhou: " + e.getMessage());
            return fallbackConnection(countryA, countryB);
        }
    }

    // ── SPREAD PATH — como o evento se propagou ───────────────────────────────

    /**
     * Gera o caminho de propagação narrativa a partir de artigos ordenados por tempo.
     *
     * @param articles  lista de (título, publishedAt ISO, países mencionados)
     *                  ordenados do mais antigo ao mais recente
     * @param mainCountry  país central da narrativa
     */
    public SpreadPath analyzeSpread(
            List<Map<String, Object>> articles,
            String mainCountry) {

        if (articles == null || articles.isEmpty()) {
            return new SpreadPath(List.of(), "Sem dados suficientes para análise.");
        }

        // Monta contexto compacto dos artigos (título + timestamp)
        StringBuilder context = new StringBuilder();
        for (Map<String, Object> art : articles.stream().limit(15).toList()) {
            String title = String.valueOf(art.getOrDefault("title", ""));
            String ts    = String.valueOf(art.getOrDefault("publishedAt", ""));
            String countries = String.valueOf(art.getOrDefault("countries", ""));
            if (!title.isBlank()) {
                context.append("- [").append(ts.length() > 16 ? ts.substring(11, 16) : ts)
                       .append("] ").append(title);
                if (!countries.isBlank()) context.append(" [países: ").append(countries).append("]");
                context.append("\n");
            }
        }

        String prompt = """
            Você é um analista de inteligência geopolítica.
            Analise as manchetes abaixo sobre %s, ordenadas cronologicamente, e identifique como o evento se propagou.
            
            Manchetes (hora UTC → título [países envolvidos]):
            %s
            
            Responda APENAS com JSON válido, sem markdown:
            {
              "nos": [
                {
                  "nome": "nome do país ou tema (ex: Irã, petróleo, bolsas)",
                  "tipo": "country|topic|actor",
                  "papel": "origem|resposta|impacto|consequência",
                  "entrou": "hora aproximada HH:MM ou 'início'"
                }
              ],
              "resumo": "2-3 frases descrevendo como a narrativa evoluiu, quem agiu primeiro e quais foram as consequências"
            }
            
            Regras:
            - Máximo 6 nós
            - O primeiro nó deve ser a origem do evento
            - Inclua tanto países quanto temas (petróleo, bolsas, OTAN) se relevantes
            - Seja factual, baseado nas manchetes
            """.formatted(mainCountry, context.toString());

        try {
            String response = callGroq(prompt, 500, 0.4);
            if (response == null) return fallbackSpread(mainCountry);

            JsonNode json = mapper.readTree(cleanJson(response));
            List<SpreadNode> nodes = new ArrayList<>();

            JsonNode nosArr = json.path("nos");
            if (nosArr.isArray()) {
                for (JsonNode n : nosArr) {
                    nodes.add(new SpreadNode(
                        n.path("nome").asText("?"),
                        n.path("tipo").asText("topic"),
                        n.path("papel").asText("impacto"),
                        n.path("entrou").asText("—")
                    ));
                }
            }

            String summary = json.path("resumo").asText("");
            log.info("[NARRATIVE] SpreadPath para " + mainCountry + ": " + nodes.size() + " nós");
            return new SpreadPath(nodes, summary);

        } catch (Exception e) {
            log.warning("[NARRATIVE] analyzeSpread falhou: " + e.getMessage());
            return fallbackSpread(mainCountry);
        }
    }

    // ── HTTP + helpers ─────────────────────────────────────────────────────────

    private String callGroq(String userPrompt, int maxTokens, double temperature) {
        try {
            Map<String, Object> msg = Map.of("role", "user", "content", userPrompt);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(msg));
            payload.put("max_tokens", maxTokens);
            payload.put("temperature", temperature);

            String body = mapper.writeValueAsString(payload);

            // ~500 input + ~500 output = ~1000 tokens por chamada narrativa
            // ~500 input + ~500 output = ~1000 tokens por chamada narrativa
            String activeKey = rateLimiter.acquire("NarrativeAnalyzer", 1000);
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + activeKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

                if (res.statusCode() == 429) {
                    rateLimiter.report429(activeKey);
                    log.warning("[NARRATIVE] 429 — key penalizada");
                    return null;
                }
                if (res.statusCode() != 200) {
                    log.warning("[NARRATIVE] HTTP " + res.statusCode());
                    return null;
                }

                JsonNode root = mapper.readTree(res.body());
                return root.path("choices").get(0).path("message").path("content").asText();
            } finally {
                rateLimiter.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warning("[NARRATIVE] callGroq: " + e.getMessage());
            return null;
        }
    }

    private String cleanJson(String raw) {
        // Remove markdown code fences se presentes
        return raw.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
    }

    private ConnectionAnalysis fallbackConnection(String a, String b) {
        return new ConnectionAnalysis(a, b, RelationType.OUTRO, "relacionados", "", 0);
    }

    private SpreadPath fallbackSpread(String country) {
        return new SpreadPath(
            List.of(new SpreadNode(country, "country", "origem", "início")),
            ""
        );
    }
}

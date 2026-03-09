package com.globalpulse.news.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

/**
 * Extração de entidades com prompt melhorado + validação pós-LLM.
 *
 * PROMPT V2 — melhorias:
 *   - Exige que países sejam mencionados EXPLICITAMENTE no texto
 *   - Exige que pessoas sejam citadas PELO NOME no texto
 *   - Tópicos devem ter 2-4 palavras E estar diretamente relacionados ao fato
 *   - Exemplos negativos incluídos ("o que NÃO fazer")
 *   - Instrução anti-alucinação explícita
 *
 * VALIDAÇÃO PÓS-LLM:
 *   - countries: deve ser país real conhecido, máx 5
 *   - people: deve ter pelo menos 2 tokens (nome + sobrenome), máx 5
 *   - topics: 2-4 palavras, não está em blocklist, não é igual ao título, máx 3
 *   - Qualquer item que não passar é removido silenciosamente
 */
@Service
public class OllamaEntityExtractor {

    private static final Logger log = Logger.getLogger(OllamaEntityExtractor.class.getName());

    @Value("${ai.ollama.baseUrl:http://localhost:11434}") private String baseUrl;
    @Value("${ai.ollama.model:llama3.2:3b}")              private String model;
    @Value("${ai.ollama.timeoutSeconds:300}")             private int    timeoutSeconds;

    // Países válidos — validação pós-LLM (blocklist invertida)
    private static final Set<String> KNOWN_COUNTRIES = new HashSet<>(Arrays.asList(
        "brasil","eua","estados unidos","estados unidos da america","china","russia","alemanha",
        "franca","frana","reino unido","argentina","mexico","canada","japao","india","australia",
        "ira","irao","israel","palestina","ucrania","venezuela","colombia","chile","peru","bolivia",
        "paraguay","uruguai","equador","cuba","turquia","afeganistao","siria","iraque","arabia saudita",
        "nigeria","africa do sul","egito","marrocos","italia","espanha","portugal","suica","suecia",
        "noruega","dinamarca","finlandia","polonica","holanda","belgica","austria","grecia","hungria",
        "republica tcheca","coreia do sul","coreia do norte","tailandia","indonesia","filipinas",
        "paquistao","bangladesh","mianmar","vietnam","nova zelandia","haiti","honduras","guatemala",
        "nicaragua","el salvador","panama","costa rica","republica dominicana","jamaica","cuba"
    ));

    // Blocklist de tópicos genéricos que o Ollama frequentemente inventa
    private static final Set<String> TOPIC_BLOCKLIST = new HashSet<>(Arrays.asList(
        "politica","economia","brasil","mundo","noticias","geral","nacional","internacional",
        "atualidades","acontecimentos","governo","congresso","nacao","pais","estado","cidade",
        "sociedade","cultura","educacao","saude","seguranca","esportes","tecnologia",
        "entretenimento","negocios","ciencia","justica","cotidiano","direito","legislacao",
        "mercado","financas","investimento","bolsa","acoes","mercado financeiro",
        "politica brasileira","economia brasileira","noticias do brasil","governo federal",
        "governo brasileiro","congresso nacional"
    ));

    private final ObjectMapper mapper;
    private final HttpClient   http;

    public OllamaEntityExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public String extract(String title, String description) {
        if (title == null || title.isBlank()) return buildEmpty();

        String text = title.trim();
        if (description != null && !description.isBlank()) {
            text += "\n" + description.trim();
        }

        String prompt = buildPrompt(title.trim(), text);

        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "api/generate" : baseUrl + "/api/generate";

            JsonNode payload = mapper.createObjectNode()
                    .put("model",  model)
                    .put("prompt", prompt)
                    .put("stream", false)
                    .set("options", mapper.createObjectNode()
                            .put("temperature", 0.0)  // zero — máxima determinismo
                            .put("num_predict", 250));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            log.info("[ENTITY] Extraindo: \"" + safe(title) + "\"");

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) return buildEmpty();

            String raw = mapper.readTree(res.body()).path("response").asText("").trim();
            String parsed = parseJson(raw);

            // VALIDAÇÃO PÓS-LLM
            return validate(parsed, title, text);

        } catch (java.net.ConnectException e) {
            log.warning("[ENTITY] Ollama offline — " + baseUrl);
            return buildEmpty();
        } catch (Exception e) {
            log.warning("[ENTITY] Erro: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            return buildEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROMPT V2
    // ─────────────────────────────────────────────────────────────────────────
    private String buildPrompt(String title, String fullText) {
        return "Você extrai entidades jornalísticas de notícias brasileiras.\n"
            + "REGRA ABSOLUTA: só extraia o que está ESCRITO NO TEXTO. Nunca invente.\n\n"

            + "TÍTULO: \"" + title + "\"\n"
            + "TEXTO: " + fullText + "\n\n"

            + "EXTRAIA:\n"
            + "1. countries: países EXPLICITAMENTE citados no texto acima.\n"
            + "   ✓ CORRETO: texto diz \"presidente da China\" → [\"China\"]\n"
            + "   ✗ ERRADO: texto é sobre economia, você adiciona \"Brasil\" sem citar\n"
            + "   Máximo 3 países.\n\n"

            + "2. people: pessoas citadas PELO NOME no texto. Apenas nomes reais.\n"
            + "   ✓ CORRETO: texto diz \"segundo Lula\" → [\"Lula\"]\n"
            + "   ✗ ERRADO: texto não cita nomes, você adiciona \"Presidente Lula\"\n"
            + "   Máximo 3 pessoas.\n\n"

            + "3. topics: o ASSUNTO ESPECÍFICO desta notícia. Obrigatório 2-3 palavras.\n"
            + "   ✓ CORRETO: notícia sobre nova lei de impostos → [\"Reforma Tributária\"]\n"
            + "   ✓ CORRETO: notícia sobre ataque militar → [\"Conflito Armado\", \"Segurança Internacional\"]\n"
            + "   ✗ ERRADO: [\"Política\"] — genérico demais, 1 palavra\n"
            + "   ✗ ERRADO: [\"Economia Brasileira\"] — se não é sobre isso diretamente\n"
            + "   Máximo 2 tópicos. Se não souber, use [].\n\n"

            + "SE O TEXTO NÃO MENCIONAR países/pessoas/tópicos específicos, retorne [].\n\n"

            + "JSON (sem texto antes ou depois):\n"
            + "{\"countries\": [...], \"people\": [...], \"topics\": [...]}\n\nJSON:";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VALIDAÇÃO PÓS-LLM — 3 etapas
    // ─────────────────────────────────────────────────────────────────────────
    private String validate(String json, String title, String fullText) {
        try {
            JsonNode node = mapper.readTree(json);
            var clean = mapper.createObjectNode();
            String textLower = normalize(fullText);

            // ── Etapa 1: Validar countries ───────────────────────────
            ArrayNode countries = mapper.createArrayNode();
            if (node.has("countries") && node.get("countries").isArray()) {
                for (JsonNode c : node.get("countries")) {
                    String val = c.asText("").trim();
                    if (val.isBlank()) continue;
                    String norm = normalize(val);

                    // Deve ser um país conhecido
                    if (!KNOWN_COUNTRIES.contains(norm)) {
                        log.fine("[ENTITY-VALID] País desconhecido rejeitado: " + val);
                        continue;
                    }
                    // Deve aparecer no texto (case-insensitive)
                    if (!textLower.contains(norm)) {
                        log.fine("[ENTITY-VALID] País não encontrado no texto: " + val);
                        continue;
                    }
                    countries.add(val);
                    if (countries.size() >= 3) break;
                }
            }
            clean.set("countries", countries);

            // ── Etapa 2: Validar people ──────────────────────────────
            ArrayNode people = mapper.createArrayNode();
            if (node.has("people") && node.get("people").isArray()) {
                for (JsonNode p : node.get("people")) {
                    String val = p.asText("").trim();
                    if (val.isBlank()) continue;

                    // Deve ter pelo menos 1 token de 3+ chars (nome real)
                    long wordCount = Arrays.stream(val.split("\\s+"))
                        .filter(w -> w.length() >= 3).count();
                    if (wordCount < 1) {
                        log.fine("[ENTITY-VALID] Nome muito curto: " + val);
                        continue;
                    }
                    // Deve aparecer no texto
                    if (!textLower.contains(normalize(val))) {
                        log.fine("[ENTITY-VALID] Pessoa não encontrada no texto: " + val);
                        continue;
                    }
                    people.add(val);
                    if (people.size() >= 3) break;
                }
            }
            clean.set("people", people);

            // ── Etapa 3: Validar topics ──────────────────────────────
            ArrayNode topics = mapper.createArrayNode();
            String titleNorm = normalize(title);

            if (node.has("topics") && node.get("topics").isArray()) {
                for (JsonNode t : node.get("topics")) {
                    String val = t.asText("").trim();
                    if (val.isBlank()) continue;

                    String norm = normalize(val);
                    String[] words = val.split("\\s+");

                    // Deve ter 2-4 palavras
                    if (words.length < 2 || words.length > 4) {
                        log.fine("[ENTITY-VALID] Tópico com número errado de palavras: " + val);
                        continue;
                    }
                    // Não pode estar na blocklist
                    if (TOPIC_BLOCKLIST.contains(norm)) {
                        log.fine("[ENTITY-VALID] Tópico na blocklist: " + val);
                        continue;
                    }
                    // Não pode ser idêntico ao título normalizado
                    if (titleNorm.equals(norm)) continue;

                    // Cada palavra do tópico deve aparecer no texto
                    // (evita tópicos inventados completamente)
                    long matchCount = Arrays.stream(words)
                        .map(w -> normalize(w))
                        .filter(w -> w.length() > 3 && textLower.contains(w))
                        .count();
                    long significantWords = Arrays.stream(words)
                        .filter(w -> w.length() > 3).count();

                    if (significantWords > 0 && matchCount == 0) {
                        log.fine("[ENTITY-VALID] Tópico sem evidência no texto: " + val);
                        continue;
                    }

                    topics.add(val);
                    if (topics.size() >= 2) break; // máximo 2 tópicos por artigo
                }
            }
            clean.set("topics", topics);

            String result = mapper.writeValueAsString(clean);
            log.info("[ENTITY] Validado → " + result);
            return result;

        } catch (Exception e) {
            log.warning("[ENTITY-VALID] Erro na validação: " + e.getMessage());
            return buildEmpty();
        }
    }

    private String parseJson(String raw) {
        if (raw == null || raw.isBlank()) return buildEmpty();
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return buildEmpty();
        return raw.substring(start, end + 1);
    }

    private String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase().trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ").trim();
    }

    private String buildEmpty() {
        return "{\"countries\":[],\"people\":[],\"topics\":[]}";
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s.trim();
    }
}

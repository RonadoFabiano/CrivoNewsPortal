package com.globalpulse.news.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * GroqEntityExtractor v2 Ã¢â‚¬â€ extrai estrutura completa de dados de cada notÃƒÂ­cia.
 * Usa GroqRateLimiter para nÃƒÂ£o estoura o limite da API.
 */
@Service
public class GroqEntityExtractor {

    private static final Logger log = Logger.getLogger(GroqEntityExtractor.class.getName());
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    @Value("${ai.groq.model:llama-3.1-8b-instant}")
    private String model;

    @Value("${ai.groq.timeoutSeconds:60}")
    private int timeoutSeconds;

    private static final Set<String> KNOWN_COUNTRIES = new HashSet<>(Arrays.asList(
        "brasil", "eua", "estados unidos", "china", "russia", "alemanha",
        "franca", "reino unido", "argentina", "mexico", "canada", "japao",
        "india", "australia", "ira", "irao", "israel", "palestina", "ucrania",
        "venezuela", "colombia", "chile", "peru", "bolivia", "paraguai",
        "uruguai", "equador", "cuba", "turquia", "afeganistao", "siria",
        "iraque", "arabia saudita", "nigeria", "africa do sul", "egito",
        "marrocos", "italia", "espanha", "portugal", "suica", "suecia",
        "noruega", "dinamarca", "finlandia", "polonia", "holanda", "belgica",
        "austria", "grecia", "hungria", "coreia do sul", "coreia do norte",
        "tailandia", "indonesia", "filipinas", "paquistao", "bangladesh",
        "vietnam", "nova zelandia", "haiti", "panama", "costa rica"
    ));

    private static final Set<String> TOPIC_BLOCKLIST = new HashSet<>(Arrays.asList(
        "politica", "economia", "brasil", "mundo", "noticias", "geral",
        "nacional", "internacional", "atualidades", "governo", "congresso",
        "nacao", "pais", "estado", "cidade", "sociedade", "cultura",
        "educacao", "saude", "seguranca", "esportes", "tecnologia",
        "entretenimento", "negocios", "ciencia", "justica", "cotidiano"
    ));

    private final ObjectMapper    mapper;
    private final HttpClient      http;
    private final GroqRateLimiter rateLimiter;

    public GroqEntityExtractor(ObjectMapper mapper, GroqRateLimiter rateLimiter) {
        this.mapper      = mapper;
        this.rateLimiter = rateLimiter;
        this.http        = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    public String extract(String title, String description) {
        if (title == null || title.isBlank()) return buildEmpty();

        String text = title.trim();
        if (description != null && !description.isBlank())
            text += "\n" + description.trim();
        if (text.length() > 3000) text = text.substring(0, 3000);

        try {
            // ~500 input + ~400 output = ~900 tokens por chamada do EntityExtractor
            String activeKey = rateLimiter.acquire("GroqEntityExtractor", 900);
            int[] usage = new int[]{0, 0};
            try {
                String body = buildRequestBody(title.trim(), text);

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + activeKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

                log.info("[GROQ-ENTITY] Extraindo: \"" + safe(title) + "\"");

                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

                if (res.statusCode() == 429) {
                    rateLimiter.report429(activeKey);
                    log.warning("[GROQ-ENTITY] 429 Ã¢â‚¬â€ key penalizada por 60s");
                    return null;
                }
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    log.warning("[GROQ-ENTITY] HTTP " + res.statusCode());
                    return buildEmpty();
                }

                JsonNode root = mapper.readTree(res.body());
                JsonNode usageNode = root.path("usage");
                usage[0] = usageNode.path("prompt_tokens").asInt(0);
                usage[1] = usageNode.path("completion_tokens").asInt(0);
                String raw = root
                    .path("choices").get(0)
                    .path("message").path("content").asText("").trim();

                int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
                if (start < 0 || end <= start) return buildEmpty();

                return validate(raw.substring(start, end + 1), title, text);

            } finally {
                rateLimiter.release(usage[0], usage[1]);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warning("[GROQ-ENTITY] Erro: " + e.getClass().getSimpleName() + " Ã¢â‚¬â€ " + e.getMessage());
            return buildEmpty();
        }
    }

    private String buildRequestBody(String title, String fullText) throws Exception {
        String systemPrompt =
            "VocÃƒÂª extrai estrutura de dados jornalÃƒÂ­sticos. " +
            "Responda APENAS com JSON vÃƒÂ¡lido. Nunca invente dados.";

        String userPrompt =
            "TÃƒÂTULO: \"" + title + "\"\n" +
            "TEXTO: " + fullText + "\n\n" +
            "Extraia APENAS o que estÃƒÂ¡ escrito. Responda com JSON:\n" +
            "{\n" +
            "  \"countries\": [\"paÃƒÂ­ses citados, mÃƒÂ¡x 5\"],\n" +
            "  \"people\": [\"nomes completos, mÃƒÂ¡x 5\"],\n" +
            "  \"organizations\": [\"empresas/ÃƒÂ³rgÃƒÂ£os/partidos, mÃƒÂ¡x 4\"],\n" +
            "  \"topics\": [\"assunto em 2-4 palavras, mÃƒÂ¡x 3\"],\n" +
            "  \"location\": { \"state\": \"estado ou null\", \"city\": \"cidade ou null\" },\n" +
            "  \"scope\": \"nacional OU internacional OU ambos\",\n" +
            "  \"tone\": \"urgente OU negativo OU positivo OU neutro OU investigativo\",\n" +
            "  \"hasVictims\": true ou false,\n" +
            "  \"victimCount\": nÃƒÂºmero ou -1,\n" +
            "  \"keyFact\": \"frase mÃƒÂ¡x 15 palavras resumindo o fato\"\n" +
            "}\n" +
            "scope: 'nacional'=sÃƒÂ³ Brasil, 'internacional'=outros paÃƒÂ­ses, 'ambos'=os dois.\n" +
            "tone: 'urgente'=mortes/desastres, 'negativo'=crimes/conflitos, 'positivo'=conquistas, " +
            "'investigativo'=denÃƒÂºncias, 'neutro'=resto.\n";

        var messages = mapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        var payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.set("messages", messages);
        payload.put("temperature", 0.0);
        payload.put("max_tokens", 400);
        payload.put("stream", false);

        return mapper.writeValueAsString(payload);
    }

    private String validate(String json, String title, String fullText) {
        try {
            JsonNode node    = mapper.readTree(json);
            ObjectNode clean = mapper.createObjectNode();

            // countries
            ArrayNode countries = mapper.createArrayNode();
            if (node.has("countries") && node.get("countries").isArray()) {
                for (JsonNode c : node.get("countries")) {
                    String val = c.asText("").trim();
                    if (!val.isBlank() && KNOWN_COUNTRIES.contains(normalize(val))) {
                        countries.add(val);
                        if (countries.size() >= 5) break;
                    }
                }
            }
            clean.set("countries", countries);

            // people
            ArrayNode people = mapper.createArrayNode();
            if (node.has("people") && node.get("people").isArray()) {
                for (JsonNode p : node.get("people")) {
                    String val = p.asText("").trim();
                    if (!val.isBlank()) {
                        people.add(val);
                        if (people.size() >= 5) break;
                    }
                }
            }
            clean.set("people", people);

            // organizations
            ArrayNode orgs = mapper.createArrayNode();
            if (node.has("organizations") && node.get("organizations").isArray()) {
                for (JsonNode o : node.get("organizations")) {
                    String val = o.asText("").trim();
                    if (!val.isBlank()) {
                        orgs.add(val);
                        if (orgs.size() >= 4) break;
                    }
                }
            }
            clean.set("organizations", orgs);

            // topics
            ArrayNode topics = mapper.createArrayNode();
            if (node.has("topics") && node.get("topics").isArray()) {
                for (JsonNode t : node.get("topics")) {
                    String val = t.asText("").trim();
                    if (val.isBlank()) continue;
                    String[] words = val.split("\\s+");
                    if (words.length < 2 || words.length > 5) continue;
                    if (TOPIC_BLOCKLIST.contains(normalize(val))) continue;
                    topics.add(val);
                    if (topics.size() >= 3) break;
                }
            }
            clean.set("topics", topics);

            // location
            ObjectNode location = mapper.createObjectNode();
            JsonNode loc = node.path("location");
            String state = loc.path("state").asText("").trim();
            String city  = loc.path("city").asText("").trim();
            location.put("state", (state.isBlank() || state.equals("null")) ? null : state);
            location.put("city",  (city.isBlank()  || city.equals("null"))  ? null : city);
            clean.set("location", location);

            // scope
            String scope = node.path("scope").asText("nacional").toLowerCase().trim();
            if (!List.of("nacional", "internacional", "ambos").contains(scope)) scope = "nacional";
            clean.put("scope", scope);

            // tone
            String tone = node.path("tone").asText("neutro").toLowerCase().trim();
            if (!List.of("urgente", "negativo", "positivo", "neutro", "investigativo").contains(tone))
                tone = "neutro";
            clean.put("tone", tone);

            // hasVictims / victimCount
            clean.put("hasVictims",  node.path("hasVictims").asBoolean(false));
            clean.put("victimCount", node.path("victimCount").asInt(-1));

            // keyFact
            String keyFact = node.path("keyFact").asText("").trim();
            if (keyFact.equals("null")) keyFact = "";
            if (keyFact.length() > 120) keyFact = keyFact.substring(0, 117) + "...";
            clean.put("keyFact", keyFact);

            String result = mapper.writeValueAsString(clean);
            log.info("[GROQ-ENTITY] OK Ã¢â€ â€™ countries=" + countries.size()
                + " people=" + people.size()
                + " orgs=" + orgs.size()
                + " scope=" + scope + " tone=" + tone);
            return result;

        } catch (Exception e) {
            log.warning("[GROQ-ENTITY] ValidaÃƒÂ§ÃƒÂ£o falhou: " + e.getMessage());
            return buildEmpty();
        }
    }

    private String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase().trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private String buildEmpty() {
        return "{\"countries\":[],\"people\":[],\"organizations\":[]," +
               "\"topics\":[],\"location\":{\"state\":null,\"city\":null}," +
               "\"scope\":\"nacional\",\"tone\":\"neutro\"," +
               "\"hasVictims\":false,\"victimCount\":-1,\"keyFact\":\"\"}";
    }

    private String safe(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}

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
 * Cliente Groq — substitui o OllamaSummarizer.
 *
 * Modelo: llama-3.1-8b-instant
 * API: https://api.groq.com/openai/v1/chat/completions (compatível com OpenAI)
 *
 * Prompt estratégico:
 *  - Título: reescrito para ser atraente e fiel ao fato (sem clickbait vazio)
 *  - Descrição: 4-6 frases que explicam o fato, contexto e impacto
 *  - Categorias: classificadas pela IA
 */
@Service
public class GroqSummarizer {

    private static final Logger log = Logger.getLogger(GroqSummarizer.class.getName());

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

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

    @Value("${ai.groq.model:llama-3.1-8b-instant}") private String model;
    @Value("${ai.groq.timeoutSeconds:60}") private int timeoutSeconds;

    private final ObjectMapper    mapper;
    private final HttpClient      http;
    private final GroqRateLimiter rateLimiter;

    public GroqSummarizer(ObjectMapper mapper, GroqRateLimiter rateLimiter) {
        this.mapper      = mapper;
        this.rateLimiter = rateLimiter;
        this.http        = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public AiResult generate(String originalTitle, String contentText, String originalCat) {
        String text = contentText == null ? "" : contentText.trim();
        if (text.isBlank()) return null;

        // Truncamento seletivo — só artigos acima de 15k chars
        // llama-3.3-70b free = 12k TPM; artigos normais (2k-8k) passam inteiros
        // Para outliers longos: preserva início (lead) + fim (conclusão)
        if (text.length() > 15_000) {
            String inicio = text.substring(0, 10_000);
            String fim    = text.substring(text.length() - 3_000);
            text = inicio + "\n\n[...]\n\n" + fim;
            log.info("[GROQ] Artigo longo truncado para " + text.length() + " chars (início+fim preservados)");
        }

        int estimatedTokens = Math.max(4000, (text.length() / 3) + 3000);

        try {
            String activeKey = rateLimiter.acquire("GroqSummarizer", estimatedTokens);
            try {
                String body = buildRequestBody(originalTitle, text, originalCat);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(GROQ_URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + activeKey)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                log.info("[GROQ] Processando: \"" + safe(originalTitle) + "\" | chars=" + text.length());

                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

                if (res.statusCode() == 429) {
                    rateLimiter.report429(activeKey);
                    log.warning("[GROQ] 429 recebido — key penalizada, próximo ciclo usará outra");
                    return null;
                }
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    log.warning("[GROQ] HTTP " + res.statusCode() + " | " + res.body().substring(0, Math.min(200, res.body().length())));
                    return null;
                }

                com.fasterxml.jackson.databind.JsonNode responseNode = mapper.readTree(res.body());

                String raw = responseNode
                        .path("choices").get(0)
                        .path("message").path("content").asText("").trim();

                return parseResult(raw, originalTitle, originalCat);
            } finally {
                rateLimiter.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warning("[GROQ] Falhou: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REQUEST BODY
    // ─────────────────────────────────────────────────────────────────────────
    private String buildRequestBody(String originalTitle, String content, String originalCat)
            throws Exception {

        ContentType type = detectContentType(content);

        String systemPrompt = buildSystemPrompt(type);
        String userPrompt   = buildUserPrompt(originalTitle, content, originalCat, type);

        var messages = mapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        var payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.set("messages", messages);
        payload.put("temperature", type == ContentType.NOTICIA ? 0.4 : 0.3);
        payload.put("max_tokens", 4000); // suficiente para matérias longas com cenários/tópicos
        payload.put("stream", false);

        return mapper.writeValueAsString(payload);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DETECÇÃO DE TIPO
    // ─────────────────────────────────────────────────────────────────────────
    private enum ContentType { RECEITA, TUTORIAL, EXPLICATIVO, NOTICIA }

    private ContentType detectContentType(String content) {
        String low = content.toLowerCase();
        if (low.contains("ingredientes") || low.contains("modo de preparo")
                || low.contains("rendimento") || low.contains("xícara de"))
            return ContentType.RECEITA;
        if (low.contains("passo a passo") || low.contains("como instalar")
                || low.contains("como configurar") || low.contains("como usar")
                || low.contains("tutorial") || low.contains("guia passo"))
            return ContentType.TUTORIAL;
        if (low.contains("o que é") || low.contains("saiba o que")
                || low.contains("entenda") || low.contains("como funciona")
                || low.contains("sintomas") || low.contains("causas")
                || low.contains("dicas") || low.contains("especialistas"))
            return ContentType.EXPLICATIVO;
        return ContentType.NOTICIA;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SYSTEM PROMPTS POR TIPO
    // ─────────────────────────────────────────────────────────────────────────
    private String buildSystemPrompt(ContentType type) {
        // Base editorial comum a todos os tipos
        String base =
            "Você é um editor jornalístico profissional especializado em redação clara, " +
            "estruturada e informativa para portal de notícias brasileiro. " +
            "Regras editoriais obrigatórias para TODOS os tipos de conteúdo: " +
            "(1) Parágrafos com no máximo 3 linhas. " +
            "(2) Linha em branco entre cada parágrafo. " +
            "(3) Linguagem neutra, direta e informativa. " +
            "(4) Destaque datas, instituições e pessoas naturalmente no texto. " +
            "(5) Se houver declaração ou posicionamento, deixe claro quem disse. " +
            "(6) NUNCA invente informações que não estejam no texto original. " +
            "(7) NUNCA inclua CTAs ('compartilhe', 'assine', 'siga-nos', 'leia mais'). " +
            "(8) NUNCA inclua links, redes sociais ou nomes de jornalistas/autores. " +
            "Responda APENAS com JSON válido. Nunca adicione texto fora do JSON.";

        return switch (type) {
            case RECEITA -> base +
                " ESPECIALIDADE ATUAL: conteúdo gastronômico. " +
                "Use APENAS os ingredientes, quantidades e passos EXATOS do texto. " +
                "NUNCA invente receitas, ingredientes ou passos que não estão no original.";

            case TUTORIAL -> base +
                " ESPECIALIDADE ATUAL: guia prático/tutorial. " +
                "Use APENAS as informações do texto. NUNCA invente passos ou requisitos.";

            case EXPLICATIVO -> base +
                " ESPECIALIDADE ATUAL: artigo explicativo/educativo. " +
                "Use APENAS as informações do texto. " +
                "NUNCA invente dados, estatísticas ou informações técnicas/médicas.";

            case NOTICIA -> base +
                " ESPECIALIDADE ATUAL: matéria jornalística. " +
                "Use APENAS os fatos do texto. " +
                "NUNCA invente dados, declarações ou contextos.";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // USER PROMPTS ESPECIALIZADOS POR TIPO
    // ─────────────────────────────────────────────────────────────────────────
    private String buildUserPrompt(String originalTitle, String content,
                                    String originalCat, ContentType type) {
        String blocoTexto =
              "══════════ TEXTO ORIGINAL ══════════\n"
            + content + "\n"
            + "═════════════════════════════════════\n\n";

        String instrucoes = switch (type) {

            case NOTICIA -> """
                TAREFA: Reescreva o texto acima como matéria jornalística profissional.

                ESTRUTURA OBRIGATÓRIA:

                Parágrafo 1 — LEAD:
                Resuma a notícia em 1-2 frases fortes. Responda: Quem? Fez o quê? Quando? Onde?

                Parágrafo 2 — CONTEXTO:
                Por que aconteceu? Qual é o histórico ou cenário?

                Parágrafo 3 — DETALHAMENTO:
                Dados concretos, números, declarações, detalhes relevantes.
                Se houver múltiplos pontos, use marcadores:
                - ponto 1
                - ponto 2

                Parágrafo 4 — SITUAÇÃO ATUAL / PRÓXIMOS PASSOS:
                O que vem a seguir? Alguma decisão, data ou ação pendente?
                (omita se não houver informação no texto)

                REGRAS EXTRAS:
                ✓ Evite repetir frases do texto original — reescreva com suas palavras
                ✓ Preserve nomes, datas, cargos e números exatamente como no texto
                ✗ PROIBIDO inventar declarações ou contextos
                """;

            case EXPLICATIVO -> """
                TAREFA: Reescreva o texto acima como artigo explicativo didático.

                ESTRUTURA OBRIGATÓRIA:

                Parágrafo 1 — LEAD:
                O que é o tema e por que o leitor precisa saber? 1-2 frases.

                Parágrafo 2 — CONTEXTO:
                Origem, causa ou histórico do tema.

                Parágrafo 3 — DESENVOLVIMENTO:
                Para cada ponto principal, use tópicos se forem itens paralelos:
                - Item 1: explicação breve
                - Item 2: explicação breve
                Ou parágrafos curtos se forem blocos narrativos.

                Parágrafo 4 — CONCLUSÃO / O QUE FAZER:
                Mensagem central ou orientação prática para o leitor.

                REGRAS EXTRAS:
                ✓ Use **negrito** para termos-chave importantes
                ✓ Linguagem acessível — evite jargão sem explicação
                ✗ PROIBIDO inventar dados, estatísticas ou informações médicas/técnicas
                """;

            case RECEITA -> """
                TAREFA: Reescreva o texto acima como matéria gastronômica completa.

                ESTRUTURA OBRIGATÓRIA:

                Parágrafo 1 — LEAD:
                Apresente o prato/tema em 1-2 frases. Contexto, ocasião ou apelo.

                Parágrafo 2 — SOBRE O PRATO / AS RECEITAS:
                Descreva brevemente o que o leitor vai encontrar.

                Para CADA receita presente no texto original:

                ## [Nome exato da receita]

                **Ingredientes:**
                - ingrediente 1 com quantidade exata do texto
                - ingrediente 2 com quantidade exata do texto

                **Modo de preparo:**
                1. Passo 1 exato do texto
                2. Passo 2 exato do texto

                Parágrafo final — DICA OU CONCLUSÃO:
                1-2 frases de encerramento com dica prática.

                REGRAS CRÍTICAS:
                ✗ PROIBIDO inventar ingredientes, quantidades ou passos
                ✗ PROIBIDO mencionar receitas que NÃO aparecem no texto original
                ✓ Se o texto tiver 1 receita, apresente 1. Se tiver 3, apresente 3.
                ✓ Copie ingredientes e passos com fidelidade absoluta ao original
                """;

            case TUTORIAL -> """
                TAREFA: Reescreva o texto acima como guia prático estruturado.

                ESTRUTURA OBRIGATÓRIA:

                Parágrafo 1 — LEAD:
                O que este guia ensina e qual o benefício para o leitor? 1-2 frases.

                Parágrafo 2 — O QUE VOCÊ PRECISA:
                Liste requisitos ou materiais se houver no texto:
                - Requisito 1
                - Requisito 2
                (omita se não houver no texto)

                Parágrafo 3 — PASSO A PASSO:
                **Como fazer:**
                1. Passo 1 do texto
                2. Passo 2 do texto
                (numeração obrigatória, um passo por linha)

                Parágrafo 4 — RESULTADO / DICA FINAL:
                O que o leitor consegue ao seguir o guia. 1-2 frases.

                REGRAS EXTRAS:
                ✗ PROIBIDO inventar passos ou requisitos que não estão no texto
                ✓ Numeração para ações sequenciais, marcadores (-) para listas sem ordem
                """;
        };

        return "TÍTULO ORIGINAL: \"" + (originalTitle != null ? originalTitle : "") + "\"\n"
            + "CATEGORIA: " + (originalCat != null ? originalCat : "Geral") + "\n\n"
            + blocoTexto
            + instrucoes + "\n"
            + "TÍTULO REESCRITO:\n"
            + "✓ Claro, informativo, máximo 95 caracteres\n"
            + "✓ Mantenha nomes próprios e o assunto central\n"
            + "✓ Para notícias: título pode ser o próprio lead resumido\n"
            + "✗ PROIBIDO inventar dados ou clickbait sem substância\n\n"
            + "CATEGORIAS: escolha 1-3 de: " + String.join(", ", VALID_CATEGORIES) + "\n"
            + "Sempre inclua 'Geral'. Priorize a mais específica.\n\n"
            + "RESPONDA SOMENTE COM JSON (nenhum texto fora do JSON):\n"
            + "{\"title\": \"...\", \"description\": \"...\", \"categories\": [\"...\"]}\n\nJSON:";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARSE DO RESULTADO
    // ─────────────────────────────────────────────────────────────────────────
    private AiResult parseResult(String raw, String originalTitle, String originalCat) {
        if (raw == null || raw.isBlank()) return null;

        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warning("[GROQ] Resposta sem JSON: " + safe(raw));
            return null;
        }

        try {
            // Remove caracteres de controle DENTRO de strings JSON (causa do "Illegal unquoted character")
            // Preserva \n \t legítimos que já estão escaped, remove os literais
            String jsonStr = raw.substring(start, end + 1);

            // Estratégia: parseia com ObjectMapper configurado para aceitar caracteres de controle
            ObjectMapper lenient = mapper.copy()
                .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);

            JsonNode node = lenient.readTree(jsonStr);
            String title = node.path("title").asText("").trim();
            String desc  = node.path("description").asText("").trim();
            String cats  = parseCategories(node.path("categories"), originalCat);

            if (title.length() < 10) title = improveTitle(originalTitle);
            if (desc.length() < 20)  return null;
            if (isGenericTitle(title)) title = improveTitle(originalTitle);

            log.info("[GROQ] OK → \"" + safe(title) + "\" | " + desc.length() + " chars desc | cats=" + cats);
            return new AiResult(title, desc, cats);

        } catch (Exception e) {
            log.warning("[GROQ] Parse falhou: " + e.getMessage());
            return null;
        }
    }

    private String parseCategories(JsonNode node, String originalCat) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode c : node) {
                String cat = c.asText("").trim();
                for (String valid : VALID_CATEGORIES) {
                    if (valid.equalsIgnoreCase(cat) && !result.contains(valid)) {
                        result.add(valid); break;
                    }
                }
                if (result.size() >= 3) break;
            }
        }
        if (originalCat != null) {
            for (String valid : VALID_CATEGORIES) {
                if (valid.equalsIgnoreCase(originalCat) && !result.contains(valid)) {
                    result.add(0, valid); break;
                }
            }
        }
        if (!result.contains("Geral")) result.add("Geral");
        return String.join(",", result);
    }

    private boolean isGenericTitle(String t) {
        if (t == null) return true;
        String low = t.toLowerCase();
        return low.contains("entenda o caso") || low.contains("o que muda")
            || low.contains("o que você precisa") || low.contains("tudo sobre")
            || low.equals("notícia de última hora") || low.equals("últimas notícias")
            || low.contains("grande novidade");
    }

    private String improveTitle(String original) {
        if (original == null || original.isBlank()) return "Notícia";
        return original.length() > 90 ? original.substring(0, 87) + "..." : original;
    }

    private String safe(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}

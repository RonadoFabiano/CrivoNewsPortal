package com.globalpulse.news.service;

import com.globalpulse.news.config.AppRuntimeProperties;
import com.globalpulse.news.ai.GroqSummarizer;
import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import com.globalpulse.news.db.RawArticle;
import com.globalpulse.news.db.RawArticleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Gera um resumo semanal original toda segunda-feira às 07h.
 *
 * Fluxo:
 *  1. Pega as top 20 notícias mais relevantes da semana anterior
 *  2. Monta um briefing para o Groq
 *  3. Groq gera um artigo de análise original (~400-600 palavras)
 *  4. Salva como processed_article com flag isOriginal implícito no slug
 *
 * SEO: conteúdo 100% original — aumenta autoridade da fonte no Google
 */
@Service
public class WeeklyDigestJob {

    private static final Logger log = Logger.getLogger(WeeklyDigestJob.class.getName());

    private final ProcessedArticleRepository procRepo;
    private final RawArticleRepository       rawRepo;
    private final GroqSummarizer             groq;
    private final AppRuntimeProperties      runtimeProperties;

    public WeeklyDigestJob(
            ProcessedArticleRepository procRepo,
            RawArticleRepository rawRepo,
            GroqSummarizer groq,
            AppRuntimeProperties runtimeProperties) {
        this.procRepo = procRepo;
        this.rawRepo  = rawRepo;
        this.groq     = groq;
        this.runtimeProperties = runtimeProperties;
    }

    // Toda segunda-feira às 7h (horário do servidor)
    @Scheduled(cron = "0 0 7 * * MON")
    public void generateWeeklyDigest() {
        if (runtimeProperties.getLab().isDisableWorkers()) return;
        log.info("[DIGEST] Gerando resumo semanal...");
        try {
            generate();
        } catch (Exception e) {
            log.warning("[DIGEST] Falhou: " + e.getMessage());
        }
    }

    public void generate() {
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<ProcessedArticle> recent = procRepo.findRecentAll(weekAgo);

        if (recent.isEmpty()) {
            log.info("[DIGEST] Sem artigos na última semana — abortando");
            return;
        }

        // Conta frequência por categoria para identificar os temas dominantes
        Map<String, Long> catFreq = recent.stream()
            .flatMap(a -> Arrays.stream(a.categoriesArray()))
            .filter(c -> !c.equals("Geral"))
            .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

        String topCategories = catFreq.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .map(e -> e.getKey() + " (" + e.getValue() + " notícias)")
            .collect(Collectors.joining(", "));

        // Monta briefing com os títulos das top notícias
        String briefing = recent.stream()
            .limit(25)
            .map(a -> "• " + a.getAiTitle())
            .collect(Collectors.joining("\n"));

        // Gera o artigo original via Groq
        String prompt =
            "Você é um jornalista do CRIVO News. Escreva um resumo semanal jornalístico.\n\n" +
            "TEMAS DA SEMANA: " + topCategories + "\n\n" +
            "PRINCIPAIS NOTÍCIAS DA SEMANA:\n" + briefing + "\n\n" +
            "ESCREVA:\n" +
            "1. TÍTULO: Um título para o resumo semanal (ex: 'As 5 notícias mais importantes da semana')\n" +
            "2. DESCRIÇÃO: Um artigo de análise com 6-8 parágrafos cobrindo os principais acontecimentos.\n" +
            "   - Parágrafo 1: Panorama geral da semana\n" +
            "   - Parágrafos 2-6: Um tema por parágrafo, com contexto e impacto\n" +
            "   - Parágrafo final: Perspectivas para a próxima semana\n" +
            "   Use linguagem jornalística clara. Não invente fatos além do que foi listado.\n" +
            "3. CATEGORIAS: ['Brasil', 'Mundo', 'Geral']\n\n" +
            "JSON: {\"title\": \"...\", \"description\": \"...\", \"categories\": [...]}";

        GroqSummarizer.AiResult result = groq.generate(
            "Resumo Semanal CRIVO News",
            prompt,
            "Geral"
        );

        if (result == null || !result.isValid()) {
            log.warning("[DIGEST] Groq não gerou resultado válido");
            return;
        }

        // Verifica se já existe um digest desta semana
        String slugBase = "resumo-semanal-crivo-" + Instant.now().toString().substring(0, 10);
        if (procRepo.findBySlug(slugBase).isPresent()) {
            log.info("[DIGEST] Digest desta semana já existe: " + slugBase);
            return;
        }

        // Salva como processed_article original
        ProcessedArticle digest = new ProcessedArticle();
        digest.setRawArticleId(-(System.currentTimeMillis() / 1000L)); // sentinela negativo = artigo original
        digest.setSlug(slugBase);
        digest.setAiTitle(result.title());
        digest.setAiDescription(result.description());
        digest.setAiCategories(result.categories());
        digest.setAiTags("resumo-semanal,curadoria-crivo,semana-em-revista");
        digest.setSource("CRIVO News");
        digest.setImageUrl("https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=1200&h=630&fit=crop"); // imagem padrão para resumo semanal
        digest.setLink("https://crivo.news/noticia/" + slugBase);
        digest.setPublishedAt(Instant.now());
        digest.setEntityStatus("DONE");
        digest.setEntities("{\"countries\":[],\"people\":[],\"topics\":[\"Resumo Semanal\",\"Curadoria de Notícias\"]}");

        procRepo.save(digest);
        log.info("[DIGEST] ✅ Resumo semanal salvo: " + slugBase + " | " + result.title());
    }
}

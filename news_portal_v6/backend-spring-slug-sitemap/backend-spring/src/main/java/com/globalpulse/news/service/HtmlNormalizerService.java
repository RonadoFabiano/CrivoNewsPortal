package com.globalpulse.news.service;

import com.globalpulse.news.config.AppRuntimeProperties;
import com.globalpulse.news.db.RawArticle;
import com.globalpulse.news.db.RawArticleRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.logging.Logger;

/**
 * HtmlNormalizerService — converte HTML bruto em texto limpo.
 *
 * Fluxo:
 *   1. Busca RawArticles com normalizeStatus = PENDING_NORMALIZE
 *   2. Extrai texto limpo do HTML salvo
 *   3. Salva em rawContentText
 *   4. Apaga htmlContent (libera espaço)
 *   5. Marca normalizeStatus = NORMALIZED
 *   6. Libera artigo para a fila de IA (aiStatus já é PENDING)
 *
 * Separar extração de normalização garante:
 *   - HTML completo capturado sem perdas
 *   - Reprocessamento sem novo scrape
 *   - Banco não estoura (HTML apagado após normalização)
 */
@Service
public class HtmlNormalizerService {

    private static final Logger log = Logger.getLogger(HtmlNormalizerService.class.getName());

    private static final int BATCH_SIZE  = 10;
    private static final int MIN_P_LEN   = 20;
    private static final int MAX_CHARS   = 25_000;

    // ── Seletores por portal ──────────────────────────────────────────────────
    private static final String[][] PORTAL_SELECTORS = {
        { "metropoles.com",  ".post-content, .article-body, .entry-content" },
        { "g1.globo.com",    ".content-text, .mc-article-body, .article__content" },
        { "cnnbrasil",       ".post-content, .single-content" },
        { "folha.uol",       ".c-news__body, .news__content" },
        { "agenciabrasil",   ".content, .field-items" },
        { "poder360",        ".td-post-content, .entry-content" },
        { "oantagonista",    ".entry-content, .post-entry" },
        { "edicase",         ".post-content, .entry-content, .article-content" },
        { "infomoney",       ".article-body, [class*='article-body'], [class*='ArticleBody'], " +
                             "[class*='articleBody'], .im-article-body, [class*='im-article'], " +
                             ".article__content, [class*='post-content'], .news-content" },
        { "uol.com.br",      ".text, .content-text, [class*='article']" },
        { "terra.com.br",    ".article-body, .news-body" },
        { "r7.com",          ".article-body, .news-content" },
        { "estadao.com.br",  ".story-body, .content-body" },
    };

    // ── Seletores de ruído a remover ──────────────────────────────────────────
    private static final String NOISE_SELECTORS =
        "script, style, noscript, iframe, " +
        "nav, header, footer, aside, " +
        ".ad, .ads, .advertisement, .publicidade, " +
        ".related, .recomendados, " +
        ".newsletter, .assine, .subscribe, " +
        ".share, .social-share, " +
        ".comments, .comentarios, " +
        ".author-bio, .autor-bio, " +
        "[class*='cookie'], [class*='gdpr'], " +
        "[class*='banner'], [class*='popup'], " +
        "[class*='sidebar'], [id*='sidebar'], " +
        "[class*='widget'], " +
        "figure > figcaption";

    // ── Padrões de texto lixo — ESPECÍFICOS para não matar conteúdo real ─────
    private static final String[] NOISE_PATTERNS = {
        "assine agora", "assine o ", "assine já",
        "newsletter", "cadastre-se",
        "clique aqui para", "clique aqui e ",
        "siga-nos no", "siga o perfil", "siga nossas",
        "baixe o app", "baixe nosso app",
        "inscreva-se", "receba nossas notícias",
        "publicidade", "anúncio", "conteúdo patrocinado",
        "© ", "todos os direitos reservados", "reprodução proibida",
        "compartilhe no ", "compartilhar no ",
        "foto:", "crédito da imagem:", "reprodução/",
        "atualizado em", "publicado em", "por redação", "da redação",
        "nunca foi tão fácil ficar", "email *", "eu concordo em receber",
        "política de privacidade", "termos de uso",
        "should_not_change", "logo whatsapp",
        "comentários", "deixe seu comentário",
        "avalie este artigo",
        "veja também:", "leia também:", "confira também:",
    };

    private final RawArticleRepository repo;
    private final AppRuntimeProperties runtimeProperties;

    public HtmlNormalizerService(RawArticleRepository repo, AppRuntimeProperties runtimeProperties) {
        this.repo = repo;
        this.runtimeProperties = runtimeProperties;
    }

    // Roda a cada 10 segundos — processa logo após o scraper salvar
    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void processQueue() {
        if (runtimeProperties.getLab().isDisableWorkers()) return;
        List<RawArticle> pending = repo.findPendingNormalize(PageRequest.of(0, BATCH_SIZE));
        if (pending.isEmpty()) return;

        log.info("[NORMALIZER] Processando " + pending.size() + " artigos...");
        int ok = 0, failed = 0;

        for (RawArticle article : pending) {
            try {
                String html = article.getHtmlContent();
                if (html == null || html.isBlank()) {
                    // HTML vazio — marca como normalizado sem conteúdo
                    article.setNormalizeStatus("NORMALIZED");
                    article.setHtmlContent(null);
                    repo.save(article);
                    failed++;
                    continue;
                }

                String text = extractTextFromHtml(html, article.getCanonicalUrl());

                article.setRawContentText(text);
                article.setHtmlContent(null);          // ← libera espaço no banco
                article.setNormalizeStatus("NORMALIZED");
                // aiStatus já é PENDING — entra automaticamente na fila da IA
                repo.save(article);
                ok++;

                log.info("[NORMALIZER] ✓ " + safe(article.getRawTitle())
                    + " → " + text.length() + " chars");

            } catch (Exception e) {
                log.warning("[NORMALIZER] Falhou em '" + safe(article.getRawTitle())
                    + "': " + e.getMessage());
                // Não bloqueia — mantém PENDING_NORMALIZE para tentar de novo
                failed++;
            }
        }

        if (ok > 0 || failed > 0) {
            log.info("[NORMALIZER] Ciclo: ok=" + ok + " falhou=" + failed
                + " | pendentes=" + repo.countByNormalizeStatus("PENDING_NORMALIZE"));
        }
    }

    // ── Extração de texto do HTML ─────────────────────────────────────────────

    public String extractTextFromHtml(String html, String url) {
        Document doc = Jsoup.parse(html, url != null ? url : "");

        // Etapa 1: remove ruído estrutural
        doc.select(NOISE_SELECTORS).remove();

        // Etapa 2: tenta seletor específico do portal
        Element scope = null;
        String urlLower = url != null ? url.toLowerCase() : "";
        for (String[] entry : PORTAL_SELECTORS) {
            if (urlLower.contains(entry[0])) {
                scope = doc.selectFirst(entry[1]);
                if (scope != null) break;
            }
        }

        // Etapa 3: fallbacks genéricos
        if (scope == null) scope = doc.selectFirst("article");
        if (scope == null) scope = doc.selectFirst("[class*='article']");
        if (scope == null) scope = doc.selectFirst("[class*='post-content']");
        if (scope == null) scope = doc.selectFirst("[class*='entry-content']");
        if (scope == null) scope = doc.selectFirst("[class*='content']");
        if (scope == null) scope = doc.selectFirst("main");

        // Etapa 4: fallback inteligente — elemento com mais texto (para portais Tailwind)
        // Portais modernos com classes dinâmicas (ex: InfoMoney) não têm seletores fixos
        if (scope == null || scope.text().length() < 200) {
            Element richest = findRichestElement(doc);
            if (richest != null && richest.text().length() > (scope != null ? scope.text().length() : 0)) {
                scope = richest;
                log.info("[NORMALIZER] Usando elemento mais rico: <" + richest.tagName()
                    + "> class='" + richest.className().substring(0, Math.min(50, richest.className().length()))
                    + "' chars=" + richest.text().length());
            }
        }

        if (scope == null) scope = doc.body();
        if (scope == null) return "";

        // Etapa 4: extrai todos os elementos de texto
        StringBuilder sb = new StringBuilder();
        Elements elements = scope.select("p, h1, h2, h3, h4, h5, li, blockquote");
        int accepted = 0, rejected = 0;

        for (Element el : elements) {
            String t = el.text().trim();

            if (t.length() < MIN_P_LEN)  { rejected++; continue; }
            if (isNoise(t))               { rejected++; continue; }

            accepted++;

            if (el.tagName().matches("h[1-5]")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(t).append(":\n");
            } else if (el.tagName().equals("li")) {
                sb.append("- ").append(t).append("\n");
            } else if (el.tagName().equals("blockquote")) {
                sb.append("\"").append(t).append("\"\n");
            } else {
                sb.append(t).append("\n");
            }

            if (sb.length() > MAX_CHARS) break;
        }

        String result = sb.toString().trim();
        log.fine("[NORMALIZER] Extraído: aceitos=" + accepted
            + " rejeitados=" + rejected + " chars=" + result.length());
        return result;
    }

    /**
     * Encontra o elemento div/section/article com mais texto corrido.
     * Estratégia para portais Tailwind/CSS-in-JS que não têm classes fixas.
     * Ignora elementos de navegação, rodapé e sidebar pelo tamanho e posição.
     */
    private Element findRichestElement(Document doc) {
        Element best = null;
        int bestLen = 200; // mínimo para ser considerado

        for (Element el : doc.select("div, section, article")) {
            // Ignora elementos muito aninhados (provavelmente wrappers)
            if (el.parents().size() > 15) continue;

            // Conta só o texto dos parágrafos filhos diretos — evita pegar todo o body
            int pTextLen = el.select("> p, > div > p").stream()
                .mapToInt(p -> p.text().length())
                .sum();

            if (pTextLen > bestLen) {
                bestLen = pTextLen;
                best = el;
            }
        }
        return best;
    }

    private boolean isNoise(String text) {
        String lower = text.toLowerCase();
        for (String p : NOISE_PATTERNS) {
            if (lower.contains(p)) return true;
        }
        // Linhas com menos de 50% de letras = menus, botões, lixo de UI
        long letters = text.chars().filter(Character::isLetter).count();
        return letters < text.length() * 0.4;
    }

    private String safe(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 70 ? s.substring(0, 70) + "..." : s;
    }
}

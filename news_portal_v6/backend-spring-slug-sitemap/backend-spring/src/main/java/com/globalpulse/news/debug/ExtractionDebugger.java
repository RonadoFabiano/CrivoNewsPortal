package com.globalpulse.news.debug;

import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;

/**
 * DEBUG MULTI-PORTAL — testa extração de todas as matérias de múltiplos portais.
 * Fase 1: entra na home, coleta links
 * Fase 2: entra em cada matéria e extrai texto completo
 */
public class ExtractionDebugger {

    // 0 = todas as matérias de cada portal
    private static final int MAX_ARTICLES_PER_PORTAL = 5;

    private static final int    TIMEOUT_MS = 15_000;
    private static final int    MIN_P_LEN  = 20;
    private static final int    MAX_CHARS  = 25_000;
    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
    };
    private static int uaIndex = 0;
    private static String nextUA() { return USER_AGENTS[uaIndex++ % USER_AGENTS.length]; }

    // =========================================================================
    //  CONFIGURAÇÃO DOS PORTAIS
    // =========================================================================
    record Portal(
        String name,
        String homeUrl,
        String host,           // host principal das matérias
        String extraHost,      // host alternativo (pode ser null)
        Set<String> sections,  // whitelist de seções (vazio = aceita tudo)
        List<String> blocked,  // paths bloqueados
        String contentSelector // seletor CSS do corpo do artigo
    ) {}

    private static final List<Portal> PORTALS = List.of(

        new Portal("CNN Brasil",
            "https://www.cnnbrasil.com.br/",
            "www.cnnbrasil.com.br", null,
            Set.of("nacional","internacional","economia","tecnologia","esportes",
                   "entretenimento","saude","politica","business"),
            List.of("/author/","/tag/","/page/","/newsletter/","/assine/","/videos/"),
            ".post-content, .single-content, .article-body"
        ),

        new Portal("G1 Globo",
            "https://g1.globo.com/",
            "g1.globo.com", null,
            Set.of("politica","economia","mundo","tecnologia","saude","educacao",
                   "ciencia","agropecuaria","turismo-e-viagem","natureza","pop-arte",
                   "sp","rj","mg","rs","ba","ce","pe","df","go","pr","sc"),
            List.of("/autor/","/tag/","/videos/","/ao-vivo/","/newsletter/"),
            ".content-text__body, .mc-article-body, .article__content, " +
            ".body-content, [class*='article-body'], .content-text"
        ),

        new Portal("Metrópoles",
            "https://www.metropoles.com/",
            "www.metropoles.com", null,
            Set.of("brasil","mundo","esportes","entretenimento","saude",
                   "tecnologia","economia","cultura","cidades"),
            List.of("/author/","/tag/","/page/","/assine/","/podcast/","/videos/","/colunas/"),
            ".post-content, .article-body, .entry-content"
        ),

        new Portal("InfoMoney",
            "https://www.infomoney.com.br/",
            "www.infomoney.com.br", null,
            Set.of("mercados","onde-investir","politica","economia","mundo","business",
                   "advisor","trader","minhas-financas","brasil","consumo","esportes",
                   "carreira","saude","cripto","investimentos","global"),
            List.of("/cotacoes/","/ferramentas/","/cursos/","/planilhas/","/ebooks/",
                    "/guias/","/relatorios/","/podcasts/","/videos/","/newsletter/","/assine/"),
            ".article-body, [class*='im-article'], [class*='article-body'], .article__content"
        ),

        new Portal("Claudio Dantas",
            "https://claudiodantas.com.br/ultimas-noticias/",
            "claudiodantas.com.br", null,
            Collections.emptySet(),
            List.of("/author/","/tag/","/page/","/category/","/feed/"),
            ".entry-content, .post-content, .td-post-content, .tdb-block-inner, .tdb_single_content"
        ),

        new Portal("UOL",
            "https://noticias.uol.com.br/",
            "noticias.uol.com.br", null,
            // UOL notícias tem seções fixas com artigos reais
            Set.of("politica","economia","internacional","saude","tecnologia",
                   "cotidiano","educacao","meio-ambiente","ciencia","ultimas-noticias"),
            List.of("/album/","/videos/","/ao-vivo/","/autor/","/tag/","/rss/",
                    "/central-de-jogos/","/campeonatos/","/podcast/"),
            ".text, .content-text, [class*='article-text'], .news-text, " +
            ".hippo-content, [class*='news__'], .article-content"
        ),

        new Portal("Veja",
            "https://veja.abril.com.br/",
            "veja.abril.com.br", null,
            Set.of("brasil","mundo","economia","saude","ciencia","educacao",
                   "tecnologia","cultura","entretenimento","comportamento"),
            List.of("/author/","/tag/","/page/","/newsletter/","/assine/"),
            ".article-body, .content-article, [class*='article-body'], .veja-content"
        ),


        new Portal("Exame",
            "https://exame.com/",
            "exame.com", null,
            Set.of("brasil","negocios","economia","tecnologia","carreira","invest",
                   "mundo","ciencia","saude","esg","bússola","flash"),
            List.of("/author/","/tag/","/page/","/newsletter/","/assine/","/videos/"),
            ".article-body, .content-article, [class*='article'], .post-content"
        ),

        new Portal("BBC Brasil",
            "https://www.bbc.com/portuguese",
            "www.bbc.com", null,
            Collections.emptySet(),
            List.of("/sport/","/sounds/","/iplayer/","/weather/","/live/",
                    "/programmes/","/schedules/","/food/","/institutional-"),
            ".article__body, [class*='article__body'], .story-body__inner, " +
            "[data-component='text-block']"
        ),

        new Portal("Jovem Pan",
            "https://jovempan.com.br/",
            "jovempan.com.br", null,
            // Só seções de notícias reais (ignora /programas/, /esportes/, /opiniao-jovem-pan etc)
            Set.of("noticias"),
            List.of("/author/","/tag/","/page/","/videos/","/radio/","/podcasts/",
                    "/ao-vivo/","/playlist/","/programas/","/opiniao-","/comentaristas/",
                    "/esportes/","/entretenimento/","/jovem-pan-"),
            ".post__content, .jp-post-content, [class*='post__content'], " +
            ".article__text, .content__article, .post-body, .news__content"
        ),

        new Portal("Forbes Brasil",
            "https://forbes.com.br/ultimas-noticias/",
            "forbes.com.br", null,
            Collections.emptySet(),
            List.of("/author/","/tag/","/page/","/newsletter/","/listas/",
                    "/anuncie","/sobre-a-forbes","/fale-conosco","/politica-",
                    "/termos-","/trabalhe-"),
            ".article-body, .post-content, .entry-content, [class*='article-body']"
        )
    );

    // =========================================================================
    //  PATHS GLOBALMENTE BLOQUEADOS (valem para TODOS os portais)
    // =========================================================================
    private static final List<String> GLOBAL_BLOCKED = List.of(
        "/anuncie", "/sobre-", "/about", "/contato", "/contact",
        "/termos", "/terms", "/privacidade", "/privacy",
        "/institucional", "/institutional",
        "/fale-conosco", "/trabalhe-conosco", "/careers",
        "/newsletter", "/assine", "/subscribe", "/login", "/cadastro",
        "/politica-de-", "/cookie", "/ajuda", "/help",
        "/copyright", "/expediente", "/quem-somos"
    );

    // =========================================================================
    //  RUÍDO
    // =========================================================================
    private static final String NOISE_SELECTORS =
        "script, style, noscript, iframe, nav, header, footer, aside, " +
        ".ad, .ads, .advertisement, .publicidade, .related, .recomendados, " +
        ".newsletter, .assine, .share, .social-share, .comments, .comentarios, " +
        "[class*='cookie'], [class*='banner'], [class*='popup'], " +
        "[class*='sidebar'], [id*='sidebar'], [class*='widget']";

    private static final String[] NOISE_PATTERNS = {
        "assine agora", "assine o ", "newsletter", "cadastre-se",
        "clique aqui para", "siga-nos no", "baixe o app",
        "inscreva-se", "publicidade", "© ", "todos os direitos reservados",
        "compartilhe no ", "por redação", "da redação",
        "política de privacidade", "termos de uso",
        "deixe seu comentário", "veja também:", "leia também:",
    };

    // =========================================================================
    //  MAIN
    // =========================================================================
    public static void main(String[] args) throws Exception {
        Map<String, int[]> summary = new LinkedHashMap<>(); // nome → [ok, curto, bloqueado, total]

        for (Portal portal : PORTALS) {
            System.out.println("\n" + "█".repeat(60));
            System.out.println("█ PORTAL: " + portal.name() + " — " + portal.homeUrl());
            System.out.println("█".repeat(60));

            List<String[]> links = extractLinks(portal);

            if (links.isEmpty()) {
                System.out.println("  ❌ Nenhuma matéria encontrada na home.");
                summary.put(portal.name(), new int[]{0, 0, 0, 0});
                continue;
            }

            int total = (MAX_ARTICLES_PER_PORTAL <= 0)
                ? links.size()
                : Math.min(links.size(), MAX_ARTICLES_PER_PORTAL);

            System.out.println("\n→ Extraindo " + total + " de " + links.size() + " matérias...\n");

            int ok = 0, curto = 0, bloqueado = 0;

            for (int i = 0; i < total; i++) {
                String url   = links.get(i)[0];
                String title = links.get(i)[1];

                System.out.println("  ── [" + (i+1) + "/" + total + "] " + title.substring(0, Math.min(70, title.length())));
                System.out.println("     " + url);

                ArticleResult r = extractArticle(url, portal);

                if (r.blocked) {
                    System.out.println("     ❌ BLOQUEADO (HTTP " + r.status + ")");
                    bloqueado++;
                } else if (r.text.length() < 200) {
                    System.out.println("     ⚠ CURTO (" + r.text.length() + " chars) seletor: " + r.selectorUsed);
                    if (!r.text.isBlank()) System.out.println("     Texto: " + r.text.replace("\n", " "));
                    curto++;
                } else {
                    System.out.println("     ✅ " + r.text.length() + " chars | seletor: " + r.selectorUsed);
                    System.out.println("     ┌─ TEXTO ─────────────────────────────────────────────");
                    for (String line : r.text.split("\n")) {
                        if (!line.isBlank()) System.out.println("     │ " + line);
                    }
                    System.out.println("     └────────────────────────────────────────────────────");
                    ok++;
                }

                if (i < total - 1) Thread.sleep(800);
            }

            summary.put(portal.name(), new int[]{ok, curto, bloqueado, total});
        }

        // ── RESUMO GERAL ──────────────────────────────────────────────────────
        System.out.println("\n\n" + "=".repeat(60));
        System.out.println("RESUMO GERAL — TODOS OS PORTAIS");
        System.out.println("=".repeat(60));
        System.out.printf("  %-20s %6s %6s %6s %6s %8s%n",
            "Portal", "Total", "✅ OK", "⚠ Curto", "❌ Bloq", "Taxa");
        System.out.println("  " + "-".repeat(58));
        for (Map.Entry<String, int[]> e : summary.entrySet()) {
            int[] v = e.getValue();
            String taxa = v[3] == 0 ? "—" : (v[0] * 100 / v[3]) + "%";
            System.out.printf("  %-20s %6d %6d %6d %6d %8s%n",
                e.getKey(), v[3], v[0], v[1], v[2], taxa);
        }
        System.out.println("=".repeat(60));
    }

    // =========================================================================
    //  EXTRAI LINKS DA HOME
    // =========================================================================
    private static List<String[]> extractLinks(Portal portal) throws Exception {
        try {
            Connection.Response res = Jsoup.connect(portal.homeUrl())
                .userAgent(nextUA()).timeout(TIMEOUT_MS)
                .header("Accept-Language", "pt-BR,pt;q=0.9")
                .followRedirects(true).ignoreHttpErrors(true).execute();

            System.out.println("  Home HTTP: " + res.statusCode() + " | HTML: " + res.body().length() + " bytes");
            if (res.statusCode() != 200) return Collections.emptyList();

            Document doc = res.parse();
            Elements allAs = doc.select("a[href]");

            LinkedHashMap<String, String> unique = new LinkedHashMap<>();
            for (Element a : allAs) {
                String url   = a.absUrl("href").split("\\?")[0].split("#")[0];
                String title = a.text().trim();
                if (url.isBlank() || title.isBlank()) continue;

                // Aceita host principal OU extraHost
                boolean validHost = url.contains(portal.host())
                    || (portal.extraHost() != null && url.contains(portal.extraHost()));
                if (!validHost) continue;

                boolean blocked = portal.blocked().stream().anyMatch(url::contains)
                    || GLOBAL_BLOCKED.stream().anyMatch(url.toLowerCase()::contains);
                if (blocked) continue;

                String path = url.replaceFirst("https?://[^/]+", "");
                String[] parts = path.split("/");

                // Portais sem whitelist aceitam URLs com 1 segmento (ex: claudiodantas.com.br/nome-materia)
                int minParts = portal.sections().isEmpty() ? 2 : 3;
                if (parts.length < minParts) continue;

                String section = parts.length >= 3 ? parts[1] : "";
                String slug    = parts[parts.length - 1];

                // Whitelist de seções (se configurada)
                if (!portal.sections().isEmpty() && !portal.sections().contains(section)) continue;

                // Slug deve parecer artigo
                if (!slug.contains("-") || slug.length() < 8) continue;
                String[] words = slug.split("-");
                boolean hasDigits = slug.matches(".*\\d.*");
                // Slug sem número precisa ter 4+ palavras (artigos reais vs campanhas/seções)
                if (!hasDigits && words.length < 4) continue;

                unique.putIfAbsent(url, title);
            }

            System.out.println("  Matérias encontradas: " + unique.size());
            List<String[]> list = new ArrayList<>();
            unique.forEach((u, t) -> list.add(new String[]{u, t}));
            return list;

        } catch (Exception e) {
            System.out.println("  ❌ Erro na home: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // =========================================================================
    //  EXTRAI TEXTO DE UMA MATÉRIA
    // =========================================================================
    private static ArticleResult extractArticle(String url, Portal portal) {
        ArticleResult r = new ArticleResult();
        try {
            Connection.Response res = Jsoup.connect(url)
                .userAgent(nextUA()).timeout(TIMEOUT_MS)
                .followRedirects(true).ignoreHttpErrors(true)
                .referrer(portal.homeUrl())
                .header("Accept-Language", "pt-BR,pt;q=0.9")
                .execute();

            r.status = res.statusCode();
            if (res.statusCode() == 403 || res.statusCode() == 429) { r.blocked = true; return r; }
            if (res.statusCode() < 200 || res.statusCode() >= 300) { r.blocked = true; return r; }

            Document doc = res.parse();
            doc.select(NOISE_SELECTORS).remove();

            // Tenta seletores do portal
            Element scope = null;
            for (String sel : portal.contentSelector().split(",")) {
                sel = sel.trim();
                scope = doc.selectFirst(sel);
                if (scope != null) { r.selectorUsed = sel; break; }
            }

            // Fallbacks genéricos
            if (scope == null) { scope = doc.selectFirst("article");                  r.selectorUsed = "article"; }
            if (scope == null) { scope = doc.selectFirst("[class*='article']");        r.selectorUsed = "[class*='article']"; }
            if (scope == null) { scope = doc.selectFirst("[class*='post-content']");   r.selectorUsed = "[class*='post-content']"; }
            if (scope == null) { scope = doc.selectFirst("[class*='entry-content']");  r.selectorUsed = "[class*='entry-content']"; }
            if (scope == null) { scope = doc.selectFirst("main");                      r.selectorUsed = "main"; }

            // Fallback inteligente — elemento com mais parágrafos
            if (scope == null || scope.text().length() < 300) {
                Element richest = findRichest(doc);
                if (richest != null && richest.text().length() > (scope != null ? scope.text().length() : 0)) {
                    scope = richest;
                    r.selectorUsed = "richest <" + richest.tagName() + "> class='" +
                        richest.className().substring(0, Math.min(40, richest.className().length())) + "'";
                }
            }
            if (scope == null) { scope = doc.body(); r.selectorUsed = "body"; }

            // Extrai texto
            StringBuilder sb = new StringBuilder();
            for (Element el : scope.select("p, h2, h3, h4, li, blockquote")) {
                String t = el.text().trim();
                if (t.length() < MIN_P_LEN || isNoise(t)) continue;
                if (el.tagName().matches("h[2-4]")) sb.append("\n").append(t).append(":\n");
                else if (el.tagName().equals("li"))  sb.append("- ").append(t).append("\n");
                else sb.append(t).append("\n");
                if (sb.length() > MAX_CHARS) break;
            }
            r.text = sb.toString().trim();

        } catch (Exception e) {
            r.selectorUsed = "ERRO: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return r;
    }

    private static Element findRichest(Document doc) {
        Element best = null; int bestLen = 300;
        for (Element el : doc.select("div, section, article")) {
            if (el.parents().size() > 15) continue;
            int len = el.select("> p, > div > p").stream().mapToInt(p -> p.text().length()).sum();
            if (len > bestLen) { bestLen = len; best = el; }
        }
        return best;
    }

    private static boolean isNoise(String text) {
        String lower = text.toLowerCase();
        for (String p : NOISE_PATTERNS) { if (lower.contains(p)) return true; }
        long letters = text.chars().filter(Character::isLetter).count();
        return letters < text.length() * 0.4;
    }

    static class ArticleResult {
        int status = 200; boolean blocked = false;
        String selectorUsed = "nenhum"; String text = "";
    }
}

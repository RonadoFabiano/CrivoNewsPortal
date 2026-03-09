package com.globalpulse.news.service;

import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

/**
 * ArticleExtractor — busca o HTML bruto de uma URL.
 *
 * NÃO faz mais extração/limpeza de texto aqui.
 * Apenas faz o HTTP request e retorna o HTML completo.
 * A limpeza é responsabilidade do HtmlNormalizerService.
 *
 * Isso garante que nenhum conteúdo seja perdido na captura.
 */
@Service
public class ArticleExtractor {

    private static final Logger log    = Logger.getLogger(ArticleExtractor.class.getName());
    private static final int    TIMEOUT_MS = 12_000;
    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * Faz o scrape e retorna o HTML bruto completo da URL.
     * Retorna string vazia se falhar ou for bloqueado (403/429).
     */
    public String fetchHtml(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            Connection.Response res = Jsoup.connect(url)
                .userAgent(UA)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .referrer("https://news.google.com/")
                .header("Accept-Language", "pt-BR,pt;q=0.9")
                .execute();

            if (res.statusCode() == 403 || res.statusCode() == 429) {
                log.warning("[EXTRACTOR] Bloqueado HTTP " + res.statusCode() + ": "
                    + url.substring(0, Math.min(80, url.length())));
                return "";
            }

            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warning("[EXTRACTOR] HTTP " + res.statusCode() + ": "
                    + url.substring(0, Math.min(80, url.length())));
                return "";
            }

            String html = res.body();
            log.info("[EXTRACTOR] ✓ " + url.substring(0, Math.min(70, url.length()))
                + " → " + html.length() + " bytes HTML");
            return html;

        } catch (Exception e) {
            log.warning("[EXTRACTOR] Falhou (" + e.getClass().getSimpleName() + "): "
                + url.substring(0, Math.min(80, url.length())));
            return "";
        }
    }

    /**
     * Mantido para compatibilidade com código legado que ainda chama extractText().
     * Redireciona para fetchHtml() — o HtmlNormalizerService fará a limpeza depois.
     * @deprecated Use fetchHtml() + HtmlNormalizerService
     */
    @Deprecated
    public String extractText(String url) {
        return fetchHtml(url);
    }
}

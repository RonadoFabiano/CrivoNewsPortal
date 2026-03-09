package com.globalpulse.news.api;

import jakarta.annotation.PreDestroy;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Playwright NÃO é thread-safe quando múltiplas threads compartilham um Browser.
 * Solução: cada thread tem seu próprio par (Playwright + Browser) via ThreadLocal.
 * Todos os pares são registrados para shutdown correto ao encerrar a aplicação.
 */
@Service
public class PublisherPreviewService {

    private static final Logger log = Logger.getLogger(PublisherPreviewService.class.getName());

    // Guarda todos os pares criados para poder fechar no shutdown
    private final CopyOnWriteArrayList<PlaywrightBrowser> allInstances = new CopyOnWriteArrayList<>();

    private final ThreadLocal<PlaywrightBrowser> threadLocal = ThreadLocal.withInitial(() -> {
        log.info("[PW] Criando Playwright+Browser para thread: " + Thread.currentThread().getName());
        Playwright pw = Playwright.create();
        Browser br = pw.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage"))
        );
        PlaywrightBrowser pb = new PlaywrightBrowser(pw, br);
        allInstances.add(pb);
        return pb;
    });

    private record PlaywrightBrowser(Playwright playwright, Browser browser) {}

    @PreDestroy
    public void shutdown() {
        log.info("[PW] Encerrando " + allInstances.size() + " instâncias do Playwright...");
        for (PlaywrightBrowser pb : allInstances) {
            try { pb.browser().close(); } catch (Exception ignored) {}
            try { pb.playwright().close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Expõe o Browser da thread atual para uso externo (ex: ScraperOrchestrator).
     * O Browser retornado é thread-local — cada thread tem o seu próprio.
     */
    public Browser getBrowser() {
        return threadLocal.get().browser();
    }

    /**
     * e extrai a og:image — tudo em uma sessão Playwright.
     *
     * @return String[2]: [finalUrl, imageUrl] — ambos podem ser null
     */
    public String[] resolveUrlAndImage(String url) {
        log.info("[PW] resolveUrlAndImage() -> " + url);
        if (url == null || url.isBlank()) return new String[]{null, null};

        Browser browser = threadLocal.get().browser();
        BrowserContext ctx = null;
        Page page = null;

        try {
            ctx = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(ua())
                    .setLocale("pt-BR")
            );
            page = ctx.newPage();
            // Bloqueia recursos pesados desnecessários para extração de URL/imagem
            page.route("**/*.{woff,woff2,ttf,otf,eot,mp4,mp3,webm,ogg,wav,pdf,zip}", route -> route.abort());
            page.route("**/{ads,analytics,tracking,gtm,facebook,doubleclick}**", route -> route.abort());
            page.setDefaultTimeout(10000);

            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(10000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            );

            String currentUrl = page.url();
            log.info("[PW] URL após navigate: " + currentUrl);

            // Aguarda o redirect JS do Google completar
            if (isGoogleNewsUrl(currentUrl)) {
                log.info("[PW] Ainda no Google, aguardando redirect JS...");
                try {
                    page.waitForURL(u -> !isGoogleNewsUrl(u),
                            new Page.WaitForURLOptions().setTimeout(4000));
                    currentUrl = page.url();
                    log.info("[PW] URL após redirect: " + currentUrl);
                } catch (Exception e) {
                    log.warning("[PW] Redirect JS não ocorreu. Tentando extrair link do HTML...");
                    Optional<String> extracted = extractLinkFromGooglePage(page);
                    if (extracted.isPresent()) {
                        log.info("[PW] Link extraído do HTML: " + extracted.get());
                        page.navigate(extracted.get(), new Page.NavigateOptions()
                                .setTimeout(8000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        );
                        currentUrl = page.url();
                        log.info("[PW] URL após navegar ao publisher: " + currentUrl);
                    }
                }
            }

            String finalUrl = currentUrl;
            String imageUrl = null;

            if (!isGoogleNewsUrl(finalUrl)) {
                imageUrl = extractBestImage(page, finalUrl);
            } else {
                log.warning("[PW] Ainda no Google após todas as tentativas.");
            }

            log.info("[PW] RESULTADO -> url=" + finalUrl + " | img=" + imageUrl);
            return new String[]{finalUrl, imageUrl};

        } catch (Exception e) {
            log.severe("[PW] ERRO em resolveUrlAndImage: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return new String[]{null, null};
        } finally {
            try { if (page != null) page.close(); } catch (Exception ignored) {}
            try { if (ctx != null) ctx.close(); } catch (Exception ignored) {}
        }
    }

    public Optional<String> fetchBestImage(String url) {
        if (url == null || url.isBlank() || isGoogleNewsUrl(url)) return Optional.empty();

        Browser browser = threadLocal.get().browser();
        BrowserContext ctx = null;
        Page page = null;

        try {
            ctx = browser.newContext(new Browser.NewContextOptions().setUserAgent(ua()).setLocale("pt-BR"));
            page = ctx.newPage();
            // Bloqueia recursos pesados desnecessários para extração de URL/imagem
            page.route("**/*.{woff,woff2,ttf,otf,eot,mp4,mp3,webm,ogg,wav,pdf,zip}", route -> route.abort());
            page.route("**/{ads,analytics,tracking,gtm,facebook,doubleclick}**", route -> route.abort());
            page.setDefaultTimeout(6000);
            page.navigate(url, new Page.NavigateOptions().setTimeout(6000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            String img = extractBestImage(page, url);
            return img != null ? Optional.of(img) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        } finally {
            try { if (page != null) page.close(); } catch (Exception ignored) {}
            try { if (ctx != null) ctx.close(); } catch (Exception ignored) {}
        }
    }

    // ===== Extrai a melhor imagem da página =====
    private String extractBestImage(Page page, String baseUrl) {
        String og = normalize(contentOf(page, "meta[property='og:image']"), baseUrl);
        log.info("[PW]   og:image = " + og);
        if (isRealImageUrl(og)) return og;

        String tw = normalize(contentOf(page, "meta[name='twitter:image']"), baseUrl);
        log.info("[PW]   twitter:image = " + tw);
        if (isRealImageUrl(tw)) return tw;

        String item = normalize(contentOf(page, "meta[itemprop='image']"), baseUrl);
        if (isRealImageUrl(item)) return item;

        String preload = normalize(attrOf(page, "link[rel='preload'][as='image']", "href"), baseUrl);
        if (isRealImageUrl(preload)) return preload;

        try {
            Object result = page.evalOnSelectorAll(
                    "article img, [class*='article'] img, main img, img",
                    "imgs => {\n" +
                            "  const c = imgs\n" +
                            "    .map(i => ({ src: i.currentSrc || i.src, w: i.naturalWidth||i.width||0, h: i.naturalHeight||i.height||0 }))\n" +
                            "    .filter(x => x.src && !x.src.startsWith('data:') && x.w >= 200 && x.h >= 150)\n" +
                            "    .sort((a,b)=>(b.w*b.h)-(a.w*a.h));\n" +
                            "  return c.length ? c[0].src : null;\n" +
                            "}"
            );
            String biggest = normalize(result != null ? result.toString() : null, baseUrl);
            log.info("[PW]   biggest img = " + biggest);
            if (isRealImageUrl(biggest)) return biggest;
        } catch (Exception e) {
            log.warning("[PW]   evalOnSelectorAll erro: " + e.getMessage());
        }

        return null;
    }

    private Optional<String> extractLinkFromGooglePage(Page page) {
        try {
            Object result = page.evaluate(
                    "() => {\n" +
                            "  const links = Array.from(document.querySelectorAll('a[href]'))\n" +
                            "    .map(a => a.href)\n" +
                            "    .filter(h => h && h.startsWith('http') && !h.includes('google.com'));\n" +
                            "  return links[0] || null;\n" +
                            "}"
            );
            if (result != null && !result.toString().isBlank() && !isGoogleNewsUrl(result.toString()))
                return Optional.of(result.toString());
        } catch (Exception e) {
            log.warning("[PW] extractLinkFromGooglePage erro: " + e.getMessage());
        }
        return Optional.empty();
    }

    private static String contentOf(Page page, String selector) {
        try {
            Locator loc = page.locator(selector).first();
            if (loc.count() == 0) return null;
            return loc.getAttribute("content");
        } catch (Exception e) { return null; }
    }

    private static String attrOf(Page page, String selector, String attr) {
        try {
            Locator loc = page.locator(selector).first();
            if (loc.count() == 0) return null;
            return loc.getAttribute(attr);
        } catch (Exception e) { return null; }
    }

    private static String normalize(String url, String baseUrl) {
        try {
            if (url == null || url.isBlank()) return url;
            if (url.startsWith("http://") || url.startsWith("https://")) return url;
            if (url.startsWith("//")) return "https:" + url;
            return URI.create(baseUrl).resolve(url).toString();
        } catch (Exception e) { return url; }
    }

    private static boolean isGoogleNewsUrl(String u) {
        if (u == null) return false;
        String s = u.toLowerCase(Locale.ROOT);
        return s.contains("news.google.com") || s.contains("google.com/news");
    }

    private static boolean isRealImageUrl(String u) {
        if (u == null || u.isBlank()) return false;
        String s = u.toLowerCase(Locale.ROOT);
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return false;
        if (s.startsWith("data:")) return false;
        if (s.contains("google.com") || s.contains("gstatic.com") || s.contains("googleusercontent.com")) return false;
        return true;
    }

    private static String ua() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    }
}

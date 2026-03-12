package com.globalpulse.news.api;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ScraperPortalSupport {

    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0"
    };

    private static final List<String> GLOBAL_BLOCKED = List.of(
            "/anuncie", "/sobre-", "/about", "/contato", "/contact",
            "/termos", "/terms", "/privacidade", "/privacy",
            "/institucional", "/institutional", "/quem-somos",
            "/fale-conosco", "/trabalhe-conosco", "/careers",
            "/newsletter", "/assine", "/subscribe", "/login", "/cadastro",
            "/politica-de-", "/cookie", "/ajuda", "/help"
    );

    private static final String NOISE_SELECTORS =
            "script, style, noscript, iframe, nav, header, footer, aside, " +
            ".ad, .ads, .advertisement, .publicidade, .related, .recomendados, " +
            ".newsletter, .assine, .share, .social-share, .comments, .comentarios, " +
            "[class*='cookie'], [class*='banner'], [class*='popup'], " +
            "[class*='sidebar'], [id*='sidebar'], [class*='widget']";

    private static final String[] NOISE_PATTERNS = {
            "assine agora", "assine o ", "newsletter", "cadastre-se",
            "clique aqui para", "siga-nos no", "baixe o app",
            "inscreva-se", "publicidade", "todos os direitos reservados",
            "compartilhe no ", "politica de privacidade", "termos de uso",
            "deixe seu comentario", "veja tambem:", "leia tambem:"
    };

    private static final Map<String, String> CONTENT_SELECTORS = Map.ofEntries(
            Map.entry("CNN Brasil", ".post-content, .single-content, .article-body"),
            Map.entry("G1 Globo", ".content-text__body, .mc-article-body, .article__content, .body-content, [class*='article-body'], .content-text"),
            Map.entry("Metropoles", ".post-content, .article-body, .entry-content"),
            Map.entry("InfoMoney", ".article-body, [class*='im-article'], [class*='article-body'], .article__content"),
            Map.entry("Claudio Dantas", ".entry-content, .post-content, .td-post-content, .tdb-block-inner, .tdb_single_content"),
            Map.entry("UOL", ".text, .content-text, [class*='article-text'], .news-text, .hippo-content, [class*='news__'], .article-content"),
            Map.entry("Veja", ".article-body, .content-article, [class*='article-body'], .veja-content"),
            Map.entry("Exame", ".article-body, .content-article, [class*='article'], .post-content"),
            Map.entry("BBC Brasil", ".article__body, [class*='article__body'], .story-body__inner, [data-component='text-block']"),
            Map.entry("Jovem Pan", ".post__content, .jp-post-content, [class*='post__content'], .article__text, .content__article, .post-body, .news__content"),
            Map.entry("Forbes Brasil", ".article-body, .post-content, .entry-content, [class*='article-body']"),
            Map.entry("Poder360", ".entry-content, .post-content, .td-post-content, .content, article")
    );

    private static int userAgentIndex = 0;

    private ScraperPortalSupport() {
    }

    static List<String> extractLinks(Document doc, PortalScrapeRequest portal, int maxItems) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        boolean unlimited = maxItems <= 0;
        if (doc == null || portal == null) {
            return new ArrayList<>();
        }

        for (Element anchor : doc.select("a[href]")) {
            String rawUrl = safe(anchor.absUrl("href"));
            String url = normalizeArticleUrl(rawUrl, null);
            String title = safe(anchor.text()).trim();
            if (title.isBlank() || !isLikelyArticleUrl(url, portal)) {
                continue;
            }
            if (!looksLikeArticleSlug(url, portal)) {
                continue;
            }
            links.add(url);
            if (!unlimited && links.size() >= maxItems) {
                break;
            }
        }
        return new ArrayList<>(links);
    }

    static NewsItem scrapeArticle(String url, PortalScrapeRequest portal, int fetchTimeoutMs, PublisherPreviewService previewService) {
        if (url == null || url.isBlank() || portal == null) {
            return null;
        }
        try {
            Connection.Response response = Jsoup.connect(url)
                    .userAgent(nextUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .referrer(portal.homeUrl())
                    .timeout(fetchTimeoutMs)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .maxBodySize(3_000_000)
                    .execute();

            int statusCode = response.statusCode();
            if (statusCode == 403 || statusCode == 429) {
                return null;
            }
            if (statusCode < 200 || statusCode >= 300) {
                return null;
            }

            Document doc = response.parse();
            if (doc == null) {
                return null;
            }

            doc.select(NOISE_SELECTORS).remove();

            String canonicalUrl = extractCanonicalUrl(doc, url);
            if (!isLikelyArticleUrl(canonicalUrl, portal)) {
                canonicalUrl = normalizeArticleUrl(url, url);
            }

            String title = cleanTitle(extractTitle(doc), portal.name());
            if (title == null || title.length() < 8) {
                title = fallbackTitleFromUrl(canonicalUrl);
            }

            String articleText = extractArticleText(doc, portal);
            String description = firstNonBlank(summarizeDescription(articleText), extractDescription(doc), title, "");
            String image = extractImage(doc, canonicalUrl, previewService);
            Instant publishedAt = extractPublishedAt(doc);
            String source = canonicalPortalName(portal.name(), canonicalUrl);

            boolean hasUsefulText = articleText != null && articleText.length() >= 200;
            boolean hasUsefulTitle = title != null && title.length() >= 8;
            if (!hasUsefulText && !hasUsefulTitle) {
                return null;
            }

            return new NewsItem(
                    generateSlug(title, canonicalUrl),
                    title,
                    description,
                    canonicalUrl,
                    source,
                    isGoodImage(image) ? image : "/placeholder.svg",
                    publishedAt != null ? publishedAt : Instant.now(),
                    portal.category(),
                    response.body()
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isLikelyArticleUrl(String url, PortalScrapeRequest portal) {
        if (url == null || url.isBlank() || portal == null) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String host = safe(uri.getHost()).toLowerCase(Locale.ROOT);
            String expectedHost = safe(portal.host()).toLowerCase(Locale.ROOT);
            if (!hostMatches(host, expectedHost)) {
                return false;
            }

            String path = safe(uri.getPath()).toLowerCase(Locale.ROOT);
            if (path.isBlank() || "/".equals(path) || path.contains("/ao-vivo/")) {
                return false;
            }
            if (containsBlocked(path, portal.blockedPaths()) || containsBlocked(path, GLOBAL_BLOCKED)) {
                return false;
            }

            List<String> segments = pathSegments(path);
            if (segments.size() < 2) {
                return false;
            }

            if (!portal.allowedSections().isEmpty()) {
                String section = segments.get(0);
                if (!portal.allowedSections().contains(section)) {
                    return false;
                }
            }
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    static String extractTitle(Document doc) {
        return firstNonBlank(
                selectText(doc, "h1"),
                selectAttr(doc, "meta[property='og:title']", "content"),
                selectAttr(doc, "meta[name='twitter:title']", "content"),
                doc != null ? doc.title() : null
        );
    }

    static String extractDescription(Document doc) {
        return firstNonBlank(
                selectAttr(doc, "meta[name='description']", "content"),
                selectAttr(doc, "meta[property='og:description']", "content"),
                selectText(doc, "article p"),
                selectText(doc, "main p")
        );
    }

    static String extractImage(Document doc, String pageUrl, PublisherPreviewService previewService) {
        String image = firstNonBlank(
                selectAttr(doc, "meta[property='og:image']", "content"),
                selectAttr(doc, "meta[name='twitter:image']", "content"),
                selectAttr(doc, "meta[name='twitter:image:src']", "content"),
                selectAttr(doc, "link[rel=image_src]", "href"),
                selectAttr(doc, "meta[itemprop='image']", "content")
        );

        String normalized = normalizeImageUrl(image, pageUrl);
        if (isGoodImage(normalized)) {
            return normalized;
        }

        String bodyImage = extractBodyImage(doc, pageUrl);
        if (isGoodImage(bodyImage)) {
            return bodyImage;
        }

        if (previewService != null) {
            return previewService.fetchBestImage(pageUrl).filter(ScraperPortalSupport::isGoodImage).orElse(null);
        }
        return null;
    }

    private static String extractBodyImage(Document doc, String pageUrl) {
        for (Element image : doc.select("article img[src], main img[src], [class*=article] img[src], .entry-content img[src], .post-content img[src], img[src]")) {
            String candidate = normalizeImageUrl(firstNonBlank(image.absUrl("src"), image.attr("src")), pageUrl);
            if (!isGoodImage(candidate)) {
                continue;
            }
            int width = parseDimension(firstNonBlank(image.attr("width"), image.attr("data-width")));
            int height = parseDimension(firstNonBlank(image.attr("height"), image.attr("data-height")));
            if ((width > 0 && width < 180) || (height > 0 && height < 120)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static String normalizeImageUrl(String candidate, String pageUrl) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String value = candidate.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        try {
            return URI.create(pageUrl).resolve(value).toString();
        } catch (Exception ignored) {
            return value;
        }
    }

    private static int parseDimension(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }

    static Instant extractPublishedAt(Document doc) {
        return parseDate(
                selectAttr(doc, "meta[property='article:published_time']", "content"),
                selectAttr(doc, "meta[name='date']", "content"),
                selectAttr(doc, "time[datetime]", "datetime")
        );
    }

    static String extractCanonicalUrl(Document doc, String fallbackUrl) {
        return normalizeArticleUrl(firstNonBlank(
                selectAttr(doc, "link[rel=canonical]", "href"),
                selectAttr(doc, "meta[property='og:url']", "content"),
                fallbackUrl
        ), fallbackUrl);
    }

    static String cleanTitle(String title, String portalName) {
        String value = safe(title).trim();
        if (value.isBlank()) {
            return value;
        }
        String cleanPortal = safe(portalName).trim();
        if (!cleanPortal.isBlank()) {
            value = value.replace(" | " + cleanPortal, "");
            value = value.replace(" - " + cleanPortal, "");
            value = value.replace(" Ãƒâ€šÃ‚Â· " + cleanPortal, "");
        }
        return value.trim();
    }

    static String normalizeArticleUrl(String candidate, String fallback) {
        String value = firstNonBlank(candidate, fallback);
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        int hashIndex = normalized.indexOf('#');
        if (hashIndex >= 0) {
            normalized = normalized.substring(0, hashIndex);
        }
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        return normalized;
    }

    static String generateSlug(String title, String url) {
        String value = safe(title).isBlank() ? safe(url) : title;
        String slug = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", " ")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? Integer.toHexString(value.hashCode()) : slug;
    }

    static boolean isGoodImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return false;
        }
        String value = imageUrl.toLowerCase(Locale.ROOT);
        if (!value.startsWith("http")) {
            return false;
        }
        return !value.endsWith(".svg") && !value.contains("placeholder") && !value.contains("logo");
    }

    static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String fallbackTitleFromUrl(String url) {
        try {
            URI uri = new URI(url);
            List<String> segments = pathSegments(uri.getPath());
            if (segments.isEmpty()) {
                return null;
            }
            String slug = segments.get(segments.size() - 1).replace('-', ' ').trim();
            if (slug.isBlank()) {
                return null;
            }
            return Character.toUpperCase(slug.charAt(0)) + slug.substring(1);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractArticleText(Document doc, PortalScrapeRequest portal) {
        Element scope = null;
        String selectors = CONTENT_SELECTORS.getOrDefault(canonicalPortalName(portal.name(), portal.homeUrl()), "");
        for (String selector : selectors.split(",")) {
            String trimmed = selector.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            scope = doc.selectFirst(trimmed);
            if (scope != null && scope.text().length() >= 300) {
                break;
            }
        }

        if (scope == null) {
            scope = doc.selectFirst("article");
        }
        if (scope == null) {
            scope = doc.selectFirst("[class*='article']");
        }
        if (scope == null) {
            scope = doc.selectFirst("[class*='post-content']");
        }
        if (scope == null) {
            scope = doc.selectFirst("[class*='entry-content']");
        }
        if (scope == null) {
            scope = doc.selectFirst("main");
        }

        Element richest = findRichest(doc);
        if (richest != null && (scope == null || richest.text().length() > scope.text().length())) {
            scope = richest;
        }
        if (scope == null) {
            scope = doc.body();
        }

        StringBuilder text = new StringBuilder();
        for (Element element : scope.select("p, h2, h3, h4, li, blockquote")) {
            String value = normalizePlainText(element.text());
            if (value.length() < 20 || isNoise(value)) {
                continue;
            }
            if (element.tagName().matches("h[2-4]")) {
                text.append('\n').append(value).append(":\n");
            } else if ("li".equals(element.tagName())) {
                text.append("- ").append(value).append('\n');
            } else {
                text.append(value).append('\n');
            }
            if (text.length() > 25_000) {
                break;
            }
        }
        return text.toString().trim();
    }

    private static Element findRichest(Document doc) {
        Element best = null;
        int bestLength = 300;
        for (Element element : doc.select("div, section, article")) {
            if (element.parents().size() > 15) {
                continue;
            }
            int length = element.select("> p, > div > p").stream()
                    .mapToInt(p -> normalizePlainText(p.text()).length())
                    .sum();
            if (length > bestLength) {
                best = element;
                bestLength = length;
            }
        }
        return best;
    }

    private static boolean isNoise(String text) {
        String normalized = normalizePlainText(text).toLowerCase(Locale.ROOT);
        for (String pattern : NOISE_PATTERNS) {
            if (normalized.contains(pattern)) {
                return true;
            }
        }
        long letters = normalized.chars().filter(Character::isLetter).count();
        return letters < normalized.length() * 0.4;
    }

    private static String summarizeDescription(String articleText) {
        String normalized = normalizePlainText(articleText);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() <= 260) {
            return normalized;
        }
        int cutoff = normalized.lastIndexOf(' ', 260);
        if (cutoff < 120) {
            cutoff = 260;
        }
        return normalized.substring(0, cutoff).trim();
    }

    private static String normalizePlainText(String value) {
        return safe(value)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String canonicalPortalName(String rawPortalName, String url) {
        String normalizedName = normalizePlainText(rawPortalName);
        if (normalizedName.toLowerCase(Locale.ROOT).contains("metr")) {
            return "Metropoles";
        }
        if (!normalizedName.isBlank()) {
            return normalizedName;
        }
        String lowerUrl = safe(url).toLowerCase(Locale.ROOT);
        if (lowerUrl.contains("metropoles.com")) return "Metropoles";
        if (lowerUrl.contains("infomoney.com.br")) return "InfoMoney";
        if (lowerUrl.contains("forbes.com.br")) return "Forbes Brasil";
        if (lowerUrl.contains("cnnbrasil.com.br")) return "CNN Brasil";
        if (lowerUrl.contains("g1.globo.com")) return "G1 Globo";
        if (lowerUrl.contains("jovempan.com.br")) return "Jovem Pan";
        if (lowerUrl.contains("bbc.com")) return "BBC Brasil";
        if (lowerUrl.contains("veja.abril.com.br")) return "Veja";
        if (lowerUrl.contains("exame.com")) return "Exame";
        if (lowerUrl.contains("claudiodantas.com.br")) return "Claudio Dantas";
        if (lowerUrl.contains("uol.com.br")) return "UOL";
        if (lowerUrl.contains("poder360.com.br")) return "Poder360";
        return normalizedName;
    }

    private static String nextUserAgent() {
        String userAgent = USER_AGENTS[userAgentIndex % USER_AGENTS.length];
        userAgentIndex++;
        return userAgent;
    }

    private static boolean hostMatches(String actualHost, String expectedHost) {
        if (actualHost.isBlank() || expectedHost.isBlank()) {
            return false;
        }
        if (actualHost.equals(expectedHost)) {
            return true;
        }
        String expectedWithoutWww = expectedHost.replace("www.", "");
        String actualWithoutWww = actualHost.replace("www.", "");
        return actualWithoutWww.equals(expectedWithoutWww);
    }

    private static boolean containsBlocked(String path, List<String> blockedValues) {
        String lowerPath = safe(path).toLowerCase(Locale.ROOT);
        for (String blocked : blockedValues) {
            if (lowerPath.contains(safe(blocked).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeArticleSlug(String url, PortalScrapeRequest portal) {
        try {
            URI uri = new URI(url);
            String path = safe(uri.getPath());
            List<String> segments = pathSegments(path);
            if (segments.isEmpty()) {
                return false;
            }
            String slug = segments.get(segments.size() - 1).toLowerCase(Locale.ROOT);
            if (slug.length() < 8 || !slug.contains("-")) {
                return false;
            }
            boolean hasDigits = slug.matches(".*\\d.*");
            int wordCount = slug.split("-").length;
            if (!hasDigits && wordCount < 4) {
                return false;
            }
            int minSegments = portal.allowedSections().isEmpty() ? 1 : 2;
            return segments.size() >= minSegments;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String selectText(Document doc, String selector) {
        if (doc == null) {
            return null;
        }
        Element element = doc.selectFirst(selector);
        return element != null ? safe(element.text()) : null;
    }

    private static String selectAttr(Document doc, String selector, String attribute) {
        if (doc == null) {
            return null;
        }
        Element element = doc.selectFirst(selector);
        return element != null ? safe(element.attr(attribute)) : null;
    }

    private static Instant parseDate(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                return Instant.parse(candidate);
            } catch (Exception ignored) {
            }
            try {
                return OffsetDateTime.parse(candidate).toInstant();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<String> pathSegments(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : safe(path).split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment.toLowerCase(Locale.ROOT));
            }
        }
        return segments;
    }
}

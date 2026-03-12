package com.globalpulse.news.api;

import org.jsoup.nodes.Document;

import java.util.List;
import java.util.Locale;

public class ConfigurablePortalScraper implements NewsPortalScraper {

    private final String portalName;
    private final String host;

    public ConfigurablePortalScraper(String portalName, String host) {
        this.portalName = portalName;
        this.host = host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    @Override
    public String getPortalName() {
        return portalName;
    }

    @Override
    public boolean supports(PortalScrapeRequest portal) {
        if (portal == null) return false;
        String portalHost = portal.host() == null ? "" : portal.host().toLowerCase(Locale.ROOT);
        if (!host.isBlank() && host.equals(portalHost)) {
            return true;
        }
        return portalName.equalsIgnoreCase(portal.name());
    }

    @Override
    public List<String> scrapeHome(Document homeDocument, PortalScrapeRequest portal, int maxItems) {
        return ScraperPortalSupport.extractLinks(homeDocument, portal, maxItems);
    }

    @Override
    public NewsItem scrapeArticle(String url, PortalScrapeRequest portal, int fetchTimeoutMs, PublisherPreviewService previewService) {
        return ScraperPortalSupport.scrapeArticle(url, portal, fetchTimeoutMs, previewService);
    }
}

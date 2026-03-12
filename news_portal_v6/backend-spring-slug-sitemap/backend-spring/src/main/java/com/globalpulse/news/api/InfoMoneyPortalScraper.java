package com.globalpulse.news.api;

import org.jsoup.nodes.Document;

import java.util.List;

public class InfoMoneyPortalScraper implements NewsPortalScraper {

    @Override
    public String getPortalName() {
        return "InfoMoney";
    }

    @Override
    public boolean supports(PortalScrapeRequest portal) {
        return portal != null && getPortalName().equalsIgnoreCase(portal.name());
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

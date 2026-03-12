package com.globalpulse.news.api;

import org.jsoup.nodes.Document;

import java.util.List;

public interface NewsPortalScraper {

    String getPortalName();

    boolean supports(PortalScrapeRequest portal);

    List<String> scrapeHome(Document homeDocument, PortalScrapeRequest portal, int maxItems);

    NewsItem scrapeArticle(String url, PortalScrapeRequest portal, int fetchTimeoutMs, PublisherPreviewService previewService);
}

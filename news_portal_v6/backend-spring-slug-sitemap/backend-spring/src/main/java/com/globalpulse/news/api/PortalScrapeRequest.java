package com.globalpulse.news.api;

import java.util.List;
import java.util.Set;

public record PortalScrapeRequest(
        String name,
        String homeUrl,
        String category,
        String host,
        Set<String> allowedSections,
        List<String> blockedPaths
) {
}
package com.globalpulse.news.api;

import java.time.Instant;

public record NewsItem(
    String slug,
    String title,
    String description,
    String link,
    String source,
    String image,
    Instant publishedAt,
    String category,
    String fullHtml      // HTML completo do artigo — null para itens de RSS
) {
    // Construtor sem fullHtml para compatibilidade com RSS (não tem HTML bruto)
    public NewsItem(String slug, String title, String description,
                    String link, String source, String image,
                    Instant publishedAt, String category) {
        this(slug, title, description, link, source, image, publishedAt, category, null);
    }
}

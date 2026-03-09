import { useEffect, useState } from "react";
import { useLocation, useParams } from "wouter";
import Header from "@/components/Header";
import NewsCard from "@/components/NewsCard";
import { categories, NewsArticle } from "@/lib/newsData";
import { fetchExplica, ExplicaResult } from "@/lib/api";

function categorySlug(cat: string) {
  return cat.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "-");
}

function toArticle(item: any): NewsArticle {
  return {
    id: item.slug, slug: item.slug, title: item.title || item.aiTitle,
    summary: item.description || item.aiDescription || "",
    category: item.category || "Geral", categories: item.categories || ["Geral"],
    date: item.publishedAt ? new Date(item.publishedAt).toLocaleDateString("pt-BR") : "",
    image: item.image || item.imageUrl || "https://images.unsplash.com/photo-1557821552-17105176677c?w=800&h=500&fit=crop",
    link: item.link || "", source: item.source || "",
  };
}

export default function ExplicaPage() {
  const [, navigate] = useLocation();
  const params = useParams<{ slug: string }>();
  const slug = params.slug || "";

  const [data, setData] = useState<ExplicaResult | null>(null);
  const [loading, setLoading] = useState(true);

  const topicLabel = slug.replace(/-/g, " ").replace(/\b\w/g, c => c.toUpperCase());

  useEffect(() => {
    document.title = `${topicLabel} — O que está acontecendo | CRIVO News`;
    const setMeta = (sel: string, val: string) => {
      let el = document.querySelector(sel) as HTMLMetaElement | null;
      if (!el) { el = document.createElement("meta"); const m = sel.match(/\[([^=\]]+)="([^"]+)"\]/); if (m) el.setAttribute(m[1], m[2]); document.head.appendChild(el); }
      el.setAttribute("content", val);
    };
    setMeta('meta[name="description"]', `Entenda tudo sobre ${topicLabel}. Resumo, contexto histórico e últimas notícias com curadoria de IA.`);
    setMeta('meta[property="og:title"]', `${topicLabel} — O que está acontecendo | CRIVO News`);
    setMeta('meta[property="og:url"]', `https://crivo.news/explica/${slug}`);
    let link = document.querySelector('link[rel="canonical"]') as HTMLLinkElement | null;
    if (!link) { link = document.createElement("link"); link.rel = "canonical"; document.head.appendChild(link); }
    link.href = `https://crivo.news/explica/${slug}`;

    // Schema.org Article para a página Explica
    const existing = document.getElementById("schema-explica");
    if (existing) existing.remove();
    const s = document.createElement("script");
    s.id = "schema-explica"; s.type = "application/ld+json";
    s.textContent = JSON.stringify({
      "@context": "https://schema.org", "@type": "Article",
      "headline": `O que está acontecendo: ${topicLabel}`,
      "description": `Entenda tudo sobre ${topicLabel}`,
      "url": `https://crivo.news/explica/${slug}`,
      "publisher": { "@type": "Organization", "name": "CRIVO News", "url": "https://crivo.news" },
      "inLanguage": "pt-BR"
    });
    document.head.appendChild(s);
    return () => { document.title = "CRIVO News"; document.getElementById("schema-explica")?.remove(); };
  }, [slug]);

  useEffect(() => {
    setLoading(true);
    fetchExplica(slug).then(setData).finally(() => setLoading(false));
  }, [slug]);

  return (
    <div className="min-h-screen bg-background">
      <Header
        categories={categories}
        onCategorySelect={(cat) => navigate(cat === "Todos" ? "/" : "/" + categorySlug(cat))}
        activeCategory=""
      />

      <main className="container section-spacing" style={{ maxWidth: "860px" }}>
        {/* Breadcrumb */}
        <div style={{ display: "flex", alignItems: "center", gap: "8px", marginBottom: "32px", fontSize: "13px", color: "#999" }}>
          <span style={{ cursor: "pointer", color: "#b84400" }} onClick={() => navigate("/")}>Home</span>
          <span>›</span>
          <span>Explica</span>
          <span>›</span>
          <span style={{ color: "#333" }}>{topicLabel}</span>
        </div>

        {loading ? (
          <div>
            <div style={{ height: "12px", background: "#e8e8ee", borderRadius: "4px", width: "30%", marginBottom: "16px", animation: "pulse 1.5s infinite" }} />
            <div style={{ height: "36px", background: "#e8e8ee", borderRadius: "4px", width: "80%", marginBottom: "32px", animation: "pulse 1.5s infinite" }} />
            <div style={{ height: "120px", background: "#e8e8ee", borderRadius: "12px", marginBottom: "24px", animation: "pulse 1.5s infinite" }} />
            <div style={{ height: "100px", background: "#e8e8ee", borderRadius: "12px", animation: "pulse 1.5s infinite" }} />
          </div>
        ) : !data || data.error ? (
          <div style={{ textAlign: "center", padding: "80px 0" }}>
            <p style={{ fontSize: "18px", color: "#666" }}>Nenhuma informação encontrada sobre <strong>{topicLabel}</strong> ainda.</p>
            <p style={{ fontSize: "14px", color: "#999", marginTop: "8px" }}>Esta página é gerada automaticamente conforme notícias chegam.</p>
          </div>
        ) : (
          <>
            {/* Cabeçalho */}
            <div style={{ marginBottom: "40px" }}>
              <div style={{ display: "inline-flex", alignItems: "center", gap: "6px", background: "#fff3ec", border: "1px solid #b8440030", borderRadius: "20px", padding: "4px 14px", marginBottom: "16px" }}>
                <span style={{ fontSize: "12px" }}>🧠</span>
                <span style={{ fontSize: "11px", fontWeight: 700, letterSpacing: "1.5px", textTransform: "uppercase", color: "#b84400" }}>CRIVO Explica</span>
              </div>
              <h1 style={{ fontFamily: "'Playfair Display', serif", fontWeight: 900, fontSize: "clamp(28px, 4vw, 42px)", color: "#111122", lineHeight: 1.2, margin: "0 0 12px" }}>
                O que está acontecendo:<br />
                <span style={{ color: "#b84400" }}>{data.topic}</span>
              </h1>
              <p style={{ fontSize: "13px", color: "#999" }}>
                Gerado automaticamente por IA · {new Date(data.generated_at).toLocaleDateString("pt-BR", { day: "2-digit", month: "long", year: "numeric" })}
              </p>
            </div>

            {/* Resumo */}
            <div style={{ background: "#f8f8fc", borderLeft: "4px solid #b84400", borderRadius: "0 12px 12px 0", padding: "24px 28px", marginBottom: "32px" }}>
              <h2 style={{ fontFamily: "'DM Sans', sans-serif", fontWeight: 700, fontSize: "13px", letterSpacing: "2px", textTransform: "uppercase", color: "#b84400", marginBottom: "12px" }}>
                📋 Resumo
              </h2>
              <p style={{ fontFamily: "'DM Sans', sans-serif", fontSize: "17px", lineHeight: 1.7, color: "#222233", margin: 0, fontWeight: 500 }}>
                {data.summary}
              </p>
            </div>

            {/* Contexto */}
            <div style={{ background: "#fff", border: "1px solid #e0e0e8", borderRadius: "12px", padding: "24px 28px", marginBottom: "48px" }}>
              <h2 style={{ fontFamily: "'DM Sans', sans-serif", fontWeight: 700, fontSize: "13px", letterSpacing: "2px", textTransform: "uppercase", color: "#555", marginBottom: "12px" }}>
                📖 Contexto histórico
              </h2>
              <p style={{ fontFamily: "'DM Sans', sans-serif", fontSize: "16px", lineHeight: 1.8, color: "#444", margin: 0 }}>
                {data.context}
              </p>
            </div>

            {/* Notícias relacionadas */}
            {data.related_articles?.length > 0 && (
              <div>
                <div style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "24px" }}>
                  <div style={{ width: "3px", height: "24px", background: "linear-gradient(180deg, #b84400, #e07020)", borderRadius: "2px" }} />
                  <h2 style={{ fontFamily: "'Playfair Display', serif", fontWeight: 700, fontSize: "22px", color: "#111122", margin: 0 }}>
                    Últimas notícias relacionadas
                  </h2>
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                  {data.related_articles.map((art: any, i: number) => (
                    <NewsCard key={i} article={toArticle(art)} />
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </main>

      <footer className="bg-secondary border-t border-border py-12">
        <div className="container text-center">
          <p className="text-sm text-muted-foreground">© 2026 CRIVO News.</p>
        </div>
      </footer>
    </div>
  );
}

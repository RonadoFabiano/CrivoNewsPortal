import { useEffect, useState } from "react";
import { useParams, useLocation } from "wouter";
import { ArrowLeft, ExternalLink, Calendar, Tag } from "lucide-react";
import { fetchNewsBySlug, NewsArticle, fetchRecommendations } from "@/lib/api";
import Header from "@/components/Header";
import BotoesCompartilhar from "@/components/BotoesCompartilhar";
import { categories } from "@/lib/newsData";

const BASE_URL = "https://crivo.news";

// Atualiza meta tags + schema.org NewsArticle
function updateMetaTags(article: NewsArticle) {
  const canonicalUrl = `${BASE_URL}/noticia/${article.slug}`;
  const desc  = article.summary?.slice(0, 160) || article.title;
  const img   = article.image  || "";

  document.title = `${article.title} | CRIVO News`;

  const setMeta = (selector: string, attr: string, value: string) => {
    let el = document.querySelector(selector) as HTMLMetaElement | null;
    if (!el) {
      el = document.createElement("meta");
      const m = selector.match(/\[([^=\]]+)="([^"]+)"\]/);
      if (m) el.setAttribute(m[1], m[2]);
      document.head.appendChild(el);
    }
    el.setAttribute(attr, value);
  };

  // ── Meta básicos ─────────────────────────────────────────────
  setMeta('meta[name="description"]',           "content", desc);
  setMeta('meta[name="robots"]',                "content", "index, follow");

  // ── Open Graph (Facebook, WhatsApp, LinkedIn) ────────────────
  setMeta('meta[property="og:type"]',           "content", "article");
  setMeta('meta[property="og:site_name"]',      "content", "CRIVO News");
  setMeta('meta[property="og:title"]',          "content", article.title);
  setMeta('meta[property="og:description"]',    "content", desc);
  setMeta('meta[property="og:image"]',          "content", img);
  setMeta('meta[property="og:image:width"]',    "content", "1200");
  setMeta('meta[property="og:image:height"]',   "content", "630");
  setMeta('meta[property="og:url"]',            "content", canonicalUrl);
  setMeta('meta[property="og:locale"]',         "content", "pt_BR");
  if (article.source)
    setMeta('meta[property="article:publisher"]', "content", article.source);
  if (article.category)
    setMeta('meta[property="article:section"]',   "content", article.category);

  // ── Twitter Card ─────────────────────────────────────────────
  setMeta('meta[name="twitter:card"]',          "content", "summary_large_image");
  setMeta('meta[name="twitter:site"]',          "content", "@CrivoNews");
  setMeta('meta[name="twitter:title"]',         "content", article.title);
  setMeta('meta[name="twitter:description"]',   "content", desc);
  setMeta('meta[name="twitter:image"]',         "content", img);

  // ── Canonical ────────────────────────────────────────────────
  let canonical = document.querySelector('link[rel="canonical"]') as HTMLLinkElement | null;
  if (!canonical) {
    canonical = document.createElement("link");
    canonical.rel = "canonical";
    document.head.appendChild(canonical);
  }
  canonical.href = canonicalUrl;

  // ── Schema.org NewsArticle (JSON-LD) ─────────────────────────
  const existingLd = document.getElementById("schema-newsarticle");
  if (existingLd) existingLd.remove();

  const schema = {
    "@context": "https://schema.org",
    "@type": "NewsArticle",
    "headline": article.title,
    "description": desc,
    "image": img ? [img] : [],
    "url": canonicalUrl,
    "datePublished": article.date || new Date().toISOString(),
    "dateModified":  article.date || new Date().toISOString(),
    "author": {
      "@type": "Organization",
      "name":  article.source || "CRIVO News",
      "url":   article.link   || BASE_URL
    },
    "publisher": {
      "@type": "Organization",
      "name":  "CRIVO News",
      "url":   BASE_URL,
      "logo": {
        "@type":  "ImageObject",
        "url":    `${BASE_URL}/logo.png`,
        "width":  200,
        "height": 60
      }
    },
    "mainEntityOfPage": {
      "@type": "WebPage",
      "@id":   canonicalUrl
    },
    "articleSection": article.category || "Geral",
    "inLanguage": "pt-BR",
    "isAccessibleForFree": true
  };

  const script = document.createElement("script");
  script.id   = "schema-newsarticle";
  script.type = "application/ld+json";
  script.textContent = JSON.stringify(schema, null, 2);
  document.head.appendChild(script);
}

function restoreDefaultTitle() {
  document.title = "CRIVO News";
  document.getElementById("schema-newsarticle")?.remove();
}

export default function NewsPage() {
  const { slug } = useParams<{ slug: string }>();
  const [, navigate] = useLocation();
  const [article, setArticle] = useState<NewsArticle | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [recommendations, setRecommendations] = useState<NewsArticle[]>([]);

  useEffect(() => {
    if (!slug) return;
    setLoading(true);
    setError(null);

    fetchNewsBySlug(slug)
      .then((data) => {
        setArticle(data);
        updateMetaTags(data);
      })
      .catch(() => {
        setError("Notícia não encontrada ou indisponível.");
      })
      .finally(() => setLoading(false));

    return () => { restoreDefaultTitle(); };
  }, [slug]);

  // Recomendações carregam APÓS o artigo — não bloqueiam a renderização
  useEffect(() => {
    if (!slug) return;
    const timer = setTimeout(() => {
      fetchRecommendations(slug).then(setRecommendations).catch(() => {});
    }, 800); // aguarda 800ms após montar
    return () => clearTimeout(timer);
  }, [slug]);

  // Scroll to top on mount
  useEffect(() => { window.scrollTo(0, 0); }, [slug]);

  if (loading) {
    return (
      <div className="min-h-screen bg-background">
        <Header
          categories={categories}
          onCategorySelect={(cat) => { if (cat === "Todos") { navigate("/"); return; } const slug = cat.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "-"); navigate("/" + slug); }}
          activeCategory=""
        />
        <div className="container py-10">
          <div className="max-w-3xl mx-auto animate-pulse space-y-6">
            <div className="h-5 w-24 bg-secondary rounded" />
            <div className="h-10 bg-secondary rounded w-3/4" />
            <div className="h-10 bg-secondary rounded w-1/2" />
            <div className="h-72 bg-secondary rounded-2xl" />
            <div className="space-y-3">
              {[...Array(5)].map((_, i) => (
                <div key={i} className="h-4 bg-secondary rounded" style={{ width: `${85 + (i % 3) * 5}%` }} />
              ))}
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (error || !article) {
    return (
      <div className="min-h-screen bg-background">
        <Header
          categories={categories}
          onCategorySelect={(cat) => { if (cat === "Todos") { navigate("/"); return; } const slug = cat.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "-"); navigate("/" + slug); }}
          activeCategory=""
        />
        <div className="flex items-center justify-center" style={{ minHeight: "60vh" }}>
        <div className="text-center max-w-md p-8">
          <div className="text-6xl mb-4">📰</div>
          <h1 className="text-2xl font-bold text-foreground mb-2">Notícia não encontrada</h1>
          <p className="text-muted-foreground mb-6">
            Esta notícia pode ter expirado ou o link está incorreto.
          </p>
          <button
            onClick={() => navigate("/")}
            className="inline-flex items-center gap-2 px-5 py-2.5 bg-accent text-white rounded-lg font-semibold hover:opacity-90 transition-opacity"
          >
            <ArrowLeft className="w-4 h-4" /> Voltar ao início
          </button>
        </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background">
      {/* Banner completo — igual à home */}
      <Header
        categories={categories}
        onCategorySelect={(cat) => {
          if (cat === "Todos") { navigate("/"); return; }
          const slug = cat.toLowerCase().normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "-");
          navigate("/" + slug);
        }}
        activeCategory={article.category || ""}
      />

      {/* Barra de navegação da notícia — Voltar + link Original */}
      <div style={{
        position: "sticky", top: 0, zIndex: 40,
        background: "rgba(255,255,255,0.97)",
        backdropFilter: "blur(8px)",
        borderBottom: "1px solid #e0e0e8",
        padding: "10px 0",
      }}>
        <div className="container" style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <button
            onClick={() => navigate("/")}
            style={{
              display: "flex", alignItems: "center", gap: "6px",
              fontSize: "13px", fontWeight: 600, color: "#555",
              background: "none", border: "none", cursor: "pointer",
              padding: "4px 0", transition: "color 0.15s",
            }}
            onMouseEnter={e => (e.currentTarget as HTMLElement).style.color = "#b84400"}
            onMouseLeave={e => (e.currentTarget as HTMLElement).style.color = "#555"}
          >
            <ArrowLeft className="w-4 h-4" />
            Voltar
          </button>
          <a
            href={article.link}
            target="_blank"
            rel="noopener noreferrer"
            style={{
              display: "flex", alignItems: "center", gap: "6px",
              fontSize: "13px", fontWeight: 600, color: "#b84400",
              textDecoration: "none", transition: "opacity 0.15s",
            }}
            onMouseEnter={e => (e.currentTarget as HTMLElement).style.opacity = "0.7"}
            onMouseLeave={e => (e.currentTarget as HTMLElement).style.opacity = "1"}
          >
            <ExternalLink className="w-4 h-4" />
            Ver original
          </a>
        </div>
      </div>

      <main className="container py-10">
        <article className="max-w-3xl mx-auto">

          {/* Category + date */}
          <div className="flex flex-wrap items-center gap-3 mb-6">
            <span className="category-badge flex items-center gap-1">
              <Tag className="w-3 h-3" />
              {article.category}
            </span>
            {article.date && (
              <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Calendar className="w-3.5 h-3.5" />
                {article.date}
              </span>
            )}
            {article.source && (
              <span className="text-sm text-muted-foreground">
                Fonte: <span className="font-semibold text-foreground">{article.source}</span>
              </span>
            )}
          </div>

          {/* Compartilhar */}
          <div className="mb-6 mt-2">
            <BotoesCompartilhar
              title={article.title}
              url={`https://crivo.news/noticia/${article.slug}`}
            />
          </div>

          {/* Title */}
          <h1 className="text-3xl md:text-4xl lg:text-5xl font-bold text-foreground leading-tight mb-6">
            {article.title}
          </h1>

          {/* Accent line */}
          <div className="h-1 w-20 bg-accent rounded-full mb-8" />

          {/* Hero image */}
          <figure className="mb-8 rounded-2xl overflow-hidden border border-border">
            <img
              src={article.image}
              alt={article.title}
              className="w-full object-cover max-h-[480px]"
              onError={(e) => {
                e.currentTarget.src =
                  "https://images.unsplash.com/photo-1557821552-17105176677c?w=1200&h=600&fit=crop";
              }}
            />
          </figure>

          {/* Summary / lead */}
          <p className="text-xl text-foreground leading-relaxed mb-8 font-medium">
            {article.summary}
          </p>

          {/* CTA — link para original */}
          <div className="rounded-xl border border-accent/30 bg-accent/5 p-6 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 mb-10">
            <div>
              <p className="font-semibold text-foreground">Leia a matéria completa</p>
              <p className="text-sm text-muted-foreground">
                Continue lendo no veículo original: <strong>{article.source}</strong>
              </p>
            </div>
            <a
              href={article.link}
              target="_blank"
              rel="noopener noreferrer"
              className="flex-shrink-0 inline-flex items-center gap-2 px-5 py-2.5 bg-accent text-white rounded-lg font-semibold text-sm hover:opacity-90 transition-opacity"
            >
              <ExternalLink className="w-4 h-4" />
              Abrir notícia original
            </a>
          </div>

          {/* Você também pode gostar */}
          {recommendations.length > 0 && (
            <div className="border-t border-border pt-8 mb-8">
              <h3 style={{
                fontFamily: "'Playfair Display', serif",
                fontWeight: 700, fontSize: "22px",
                color: "#111122", marginBottom: "20px"
              }}>
                Você também pode gostar
              </h3>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: "16px" }}>
                {recommendations.map(rec => (
                  <div
                    key={rec.slug}
                    onClick={() => navigate(`/noticia/${rec.slug}`)}
                    style={{
                      cursor: "pointer", borderRadius: "12px",
                      overflow: "hidden", border: "1px solid #e0e0e8",
                      transition: "box-shadow 0.2s",
                    }}
                    onMouseEnter={e => (e.currentTarget as HTMLElement).style.boxShadow = "0 4px 16px rgba(0,0,0,0.10)"}
                    onMouseLeave={e => (e.currentTarget as HTMLElement).style.boxShadow = "none"}
                  >
                    <img src={rec.image} alt={rec.title}
                      style={{ width:"100%", height:"120px", objectFit:"cover" }}
                      onError={e => { e.currentTarget.src = "https://images.unsplash.com/photo-1557821552-17105176677c?w=400&h=200&fit=crop"; }}
                    />
                    <div style={{ padding: "12px" }}>
                      <span style={{
                        fontSize:"10px", fontWeight:700, letterSpacing:"1.5px",
                        textTransform:"uppercase", color:"#b84400"
                      }}>{rec.category}</span>
                      <p style={{
                        fontSize:"14px", fontWeight:600, color:"#111122",
                        margin:"6px 0 0", lineHeight:1.4,
                        display:"-webkit-box", WebkitLineClamp:3,
                        WebkitBoxOrient:"vertical", overflow:"hidden"
                      }}>{rec.title}</p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Compartilhar */}
          <div className="border-t border-border pt-8">
            <p className="text-sm font-semibold text-foreground mb-4">Compartilhar</p>
            <div className="flex flex-wrap gap-3">
              <a
                href={`https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(window.location.href)}`}
                target="_blank" rel="noopener noreferrer"
                className="px-4 py-2 bg-[#1877F2] text-white rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity"
              >Facebook</a>
              <a
                href={`https://twitter.com/intent/tweet?url=${encodeURIComponent(window.location.href)}&text=${encodeURIComponent(article.title)}`}
                target="_blank" rel="noopener noreferrer"
                className="px-4 py-2 bg-[#1DA1F2] text-white rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity"
              >Twitter / X</a>
              <a
                href={`https://wa.me/?text=${encodeURIComponent(article.title + " " + window.location.href)}`}
                target="_blank" rel="noopener noreferrer"
                className="px-4 py-2 bg-[#25D366] text-white rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity"
              >WhatsApp</a>
              <button
                onClick={() => navigator.clipboard?.writeText(window.location.href)}
                className="px-4 py-2 bg-secondary text-foreground rounded-lg text-sm font-semibold hover:bg-muted transition-colors"
              >Copiar link</button>
            </div>
          </div>
        </article>
      </main>
    </div>
  );
}

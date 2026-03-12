import { useEffect, useRef, useState } from "react";
import { useLocation } from "wouter";
import Header from "@/components/Header";
import NewsCard from "@/components/NewsCard";
import Hero from "@/components/Hero";
import { categories, NewsArticle } from "@/lib/newsData";
import { fetchByTag, fetchByEntity } from "@/lib/api";
import { applySeo, resetSeo } from "@/lib/seo";

type EntityType = "tag" | "country" | "person" | "topic";

const TYPE_META: Record<EntityType, { prefix: string; label: string; icon: string }> = {
  tag:     { prefix: "noticias",  label: "Notícias sobre",   icon: "🏷️" },
  country: { prefix: "pais",      label: "Notícias sobre",   icon: "🌍" },
  person:  { prefix: "pessoa",    label: "Notícias sobre",   icon: "👤" },
  topic:   { prefix: "topico",    label: "Notícias de",      icon: "📌" },
};

function unslugify(slug: string): string {
  return slug.replace(/-/g, " ")
    .replace(/\b\w/g, c => c.toUpperCase());
}

interface Props { type: EntityType; value: string; }

function SkeletonGrid({ count = 6 }: { count?: number }) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="rounded-2xl border border-border overflow-hidden animate-pulse">
          <div className="bg-secondary h-48 w-full" />
          <div className="p-5 space-y-3">
            <div className="h-3 bg-secondary rounded w-1/3" />
            <div className="h-5 bg-secondary rounded w-full" />
            <div className="h-5 bg-secondary rounded w-4/5" />
          </div>
        </div>
      ))}
    </div>
  );
}

export default function EntityPage({ type, value }: Props) {
  const [, navigate]   = useLocation();
  const [articles, setArticles] = useState<NewsArticle[]>([]);
  const [loading, setLoading]   = useState(true);
  const [error, setError]       = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const meta       = TYPE_META[type];
  const humanLabel = unslugify(value.replace(/^pais-/, ""));
  const pageTitle  = `${meta.label} ${humanLabel}`;

  // SEO
  useEffect(() => {
    const desc = `Ultimas noticias sobre ${humanLabel} com curadoria de IA. Informacao filtrada.`;
    applySeo({
      title: `${pageTitle} - CRIVO News`,
      description: desc,
      path: `/${meta.prefix}/${value}`,
    });
    return () => resetSeo();
  }, [type, value, pageTitle, humanLabel, meta.prefix]);

  // Fetch
  useEffect(() => {
    if (abortRef.current) abortRef.current.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true); setError(null);

    const load = type === "tag"
      ? fetchByTag(value)
      : fetchByEntity(
          type as "country" | "person" | "topic",
          // country: remove prefixo "pais-" e converte para label legível
          // person/topic: manda o slug — backend faz OR entre slug e label
          type === "country" ? unslugify(value.replace(/^pais-/, ""))
          : type === "person"  ? unslugify(value)
          : value  // topic: manda o slug, backend já faz OR
        );

    load
      .then(data => { if (!ctrl.signal.aborted) setArticles(data); })
      .catch(e  => { if (!ctrl.signal.aborted) setError(e?.message || "Erro"); })
      .finally(()=> { if (!ctrl.signal.aborted) setLoading(false); });

    return () => ctrl.abort();
  }, [type, value]);

  const featured  = articles[0];
  const remaining = articles.slice(1);

  return (
    <div className="min-h-screen bg-background">
      <Header
        categories={categories}
        onCategorySelect={(cat) => {
          if (cat === "Todos") { navigate("/"); return; }
          const slug = cat.toLowerCase().normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "-");
          navigate("/" + slug);
        }}
        activeCategory=""
      />

      <main className="container section-spacing">
        {/* Título da página */}
        <div style={{ marginBottom: "40px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "8px" }}>
            <span style={{ fontSize: "28px" }}>{meta.icon}</span>
            <h1 style={{
              fontFamily: "'Playfair Display', serif",
              fontWeight: 700, fontSize: "clamp(24px, 3vw, 36px)",
              color: "#111122", margin: 0,
            }}>
              {pageTitle}
            </h1>
          </div>
          <p style={{ color: "#666", fontSize: "15px", marginLeft: "44px" }}>
            {loading ? "Carregando..." : `${articles.length} artigos encontrados`}
            {" "}&middot;{" "}
            <span style={{ color: "#b84400" }}>crivo.news/{meta.prefix}/{value}</span>
          </p>
        </div>

        {error && (
          <div className="mb-8 rounded-xl border border-destructive/30 bg-destructive/10 p-4 text-sm">
            {error}
          </div>
        )}

        {/* Hero */}
        {!loading && featured && (
          <div style={{ marginBottom: "48px" }}>
            <Hero article={featured} />
          </div>
        )}

        {/* Grid */}
        {loading ? <SkeletonGrid /> : remaining.length > 0 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            {remaining.map((article, i) => (
              <div key={article.id} className="animate-fade-in-up" style={{ animationDelay: `${i * 30}ms` }}>
                <NewsCard article={article} />
              </div>
            ))}
          </div>
        ) : !loading && (
          <div className="text-center py-16">
            <p className="text-lg text-muted-foreground">
              Nenhuma notícia encontrada para <strong>{humanLabel}</strong>.
            </p>
            <p className="text-sm text-muted-foreground mt-2">
              Esta página será populada automaticamente conforme novas notícias chegarem.
            </p>
          </div>
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

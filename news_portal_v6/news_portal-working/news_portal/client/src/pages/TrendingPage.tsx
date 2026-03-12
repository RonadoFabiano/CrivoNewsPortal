import { useEffect, useState } from "react";
import { useLocation } from "wouter";
import Header from "@/components/Header";
import NewsCard from "@/components/NewsCard";
import Hero from "@/components/Hero";
import { categories, NewsArticle } from "@/lib/newsData";
import { fetchRanked } from "@/lib/api";
import { applySeo, resetSeo } from "@/lib/seo";

function categorySlug(cat: string) {
  return cat.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "-");
}

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

export default function TrendingPage() {
  const [, navigate] = useLocation();
  const [articles, setArticles] = useState<NewsArticle[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    applySeo({
      title: "Trending - Noticias em Alta | CRIVO News",
      description: "As noticias mais relevantes do momento, rankeadas por recencia e cobertura com curadoria automatica por IA.",
      path: "/trending",
    });
    return () => resetSeo();
  }, []);

  useEffect(() => {
    fetchRanked(60).then(setArticles).finally(() => setLoading(false));
  }, []);

  const featured  = articles[0];
  const remaining = articles.slice(1);

  return (
    <div className="min-h-screen bg-background">
      <Header
        categories={categories}
        onCategorySelect={(cat) => navigate(cat === "Todos" ? "/" : "/" + categorySlug(cat))}
        activeCategory=""
      />
      <main className="container section-spacing">
        {/* Cabeçalho */}
        <div style={{ marginBottom: "40px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "8px" }}>
            <span style={{ fontSize: "28px" }}>🔥</span>
            <h1 style={{ fontFamily: "'Playfair Display', serif", fontWeight: 700, fontSize: "clamp(24px, 3vw, 36px)", color: "#111122", margin: 0 }}>
              Notícias em Alta
            </h1>
          </div>
          <p style={{ color: "#666", fontSize: "15px", marginLeft: "44px" }}>
            Rankeadas por recência, cobertura e relevância — atualizado automaticamente
          </p>
        </div>

        {/* Legenda do score */}
        <div style={{ display: "flex", gap: "20px", marginBottom: "32px", flexWrap: "wrap" }}>
          {[
            { color: "#22c55e", label: "Score 80-100 — Destaque" },
            { color: "#f59e0b", label: "Score 50-79 — Relevante" },
            { color: "#94a3b8", label: "Score abaixo de 50 — Normal" },
          ].map(({ color, label }) => (
            <div key={label} style={{ display: "flex", alignItems: "center", gap: "6px", fontSize: "12px", color: "#666" }}>
              <span style={{ width: "10px", height: "10px", borderRadius: "50%", background: color, display: "inline-block" }} />
              {label}
            </div>
          ))}
        </div>

        {/* Hero */}
        {!loading && featured && <div style={{ marginBottom: "48px" }}><Hero article={featured} /></div>}

        {/* Grid */}
        {loading ? <SkeletonGrid /> : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            {remaining.map((article, i) => (
              <div key={article.id} className="animate-fade-in-up" style={{ animationDelay: `${i * 30}ms` }}>
                <NewsCard article={article} />
              </div>
            ))}
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

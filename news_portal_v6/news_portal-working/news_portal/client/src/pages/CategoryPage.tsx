import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation } from "wouter";
import Header from "@/components/Header";
import NewsCard from "@/components/NewsCard";
import Hero from "@/components/Hero";
import { categories, NewsArticle } from "@/lib/newsData";
import { fetchNews } from "@/lib/api";
import { applySeo, resetSeo } from "@/lib/seo";

// Slug da categoria para URL canônica
function categorySlug(cat: string): string {
  return cat.toLowerCase()
    .normalize("NFD").replace(/[\u0300-\u036f]/g, "")  // remove acentos
    .replace(/\s+/g, "-");
}

interface Props { category: string; }

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
            <div className="h-3 bg-secondary rounded w-2/3" />
          </div>
        </div>
      ))}
    </div>
  );
}

export default function CategoryPage({ category }: Props) {
  const [, navigate]   = useLocation();
  const [articles, setArticles] = useState<NewsArticle[]>([]);
  const [loading, setLoading]   = useState(true);
  const [error, setError]       = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // SEO — atualiza title + meta tags da categoria
  useEffect(() => {
    const slug = categorySlug(category);
    applySeo({
      title: `${category} - CRIVO News`,
      description: `Ultimas noticias de ${category} com curadoria de IA. Fatos filtrados sem ruido.`,
      path: `/${slug}`,
    });
    return () => resetSeo();
  }, [category]);

  // Fetch artigos da categoria
  useEffect(() => {
    if (abortRef.current) abortRef.current.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;

    setLoading(true);
    setError(null);

    fetchNews({ category, limit: 80, signal: ctrl.signal })
      .then((data) => {
        if (ctrl.signal.aborted) return;
        setArticles(data);
      })
      .catch((e) => {
        if (ctrl.signal.aborted) return;
        setError(e?.message || "Erro ao carregar notícias");
      })
      .finally(() => {
        if (ctrl.signal.aborted) return;
        setLoading(false);
      });

    return () => ctrl.abort();
  }, [category]);

  const featured   = articles[0];
  const remaining  = articles.slice(1);

  return (
    <div className="min-h-screen bg-background">
      <Header
        categories={categories}
        onCategorySelect={(cat) => {
          if (cat === "Todos") { navigate("/"); return; }
          navigate("/" + categorySlug(cat));
        }}
        activeCategory={category}
      />

      <main className="container section-spacing">
        {error && (
          <div className="mb-10 rounded-xl border border-destructive/30 bg-destructive/10 p-4 text-sm">
            {error}
          </div>
        )}

        {/* Hero */}
        <div className="mb-16 md:mb-24">
          {loading ? (
            <div className="rounded-2xl border border-border bg-secondary animate-pulse h-80" />
          ) : featured ? (
            <Hero article={featured} />
          ) : null}
        </div>

        {/* Título da seção */}
        <div className="mb-12">
          <div className="flex items-center gap-4 mb-4">
            <div className="accent-line" />
            <h1 className="text-3xl md:text-4xl font-bold text-foreground">
              Notícias de {category}
            </h1>
          </div>
          <p className="text-muted-foreground text-lg">
            {loading ? "Carregando..." : `${remaining.length} artigos encontrados`}
          </p>
        </div>

        {/* Grid */}
        {loading ? (
          <SkeletonGrid count={6} />
        ) : remaining.length > 0 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            {remaining.map((article, index) => (
              <div
                key={article.id}
                className="animate-fade-in-up"
                style={{ animationDelay: `${index * 30}ms` }}
              >
                <NewsCard article={article} />
              </div>
            ))}
          </div>
        ) : (
          <div className="text-center py-16">
            <p className="text-lg text-muted-foreground">
              Nenhuma notícia encontrada em {category}.
            </p>
          </div>
        )}
      </main>

      <footer className="bg-secondary border-t border-border py-12">
        <div className="container text-center">
          <p className="text-sm text-muted-foreground">
            © 2026 CRIVO News. Todos os direitos reservados.
          </p>
        </div>
      </footer>
    </div>
  );
}

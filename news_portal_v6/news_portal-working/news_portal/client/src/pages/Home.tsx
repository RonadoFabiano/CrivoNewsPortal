import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation } from "wouter";
import Header from "@/components/Header";
import Hero from "@/components/Hero";
import NewsCard from "@/components/NewsCard";
import { categories, NewsArticle } from "@/lib/newsData";
import TrendingSection from "@/components/TrendingSection";
import TopicosDestaque from "@/components/TopicosDestaque";
import { fetchNews } from "@/lib/api";
import NewsCardCompact from "@/components/NewsCardCompact";
import BarraAgora from "@/components/BarraAgora";
import MaisLidas from "@/components/MaisLidas";

const CACHE_KEY = (cat: string) => `news_cache_${cat}`;
const CACHE_TTL_MS = 5 * 60 * 1000;

function readSessionCache(cat: string): NewsArticle[] | null {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY(cat));
    if (!raw) return null;
    const { data, ts } = JSON.parse(raw);
    if (Date.now() - ts > CACHE_TTL_MS) return null;
    return data as NewsArticle[];
  } catch { return null; }
}

function writeSessionCache(cat: string, data: NewsArticle[]) {
  try { sessionStorage.setItem(CACHE_KEY(cat), JSON.stringify({ data, ts: Date.now() })); }
  catch { /* storage cheio */ }
}

function SkeletonCard() {
  return (
    <div className="rounded-2xl border border-border overflow-hidden animate-pulse">
      <div className="bg-secondary h-48 w-full" />
      <div className="p-5 space-y-3">
        <div className="h-3 bg-secondary rounded w-1/3" />
        <div className="h-5 bg-secondary rounded w-full" />
        <div className="h-5 bg-secondary rounded w-4/5" />
        <div className="h-3 bg-secondary rounded w-2/3" />
      </div>
    </div>
  );
}

function SidebarSkeleton() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} style={{ display: "flex", gap: "12px", alignItems: "center" }}>
          <div style={{ width: "28px", height: "28px", borderRadius: "4px", background: "#f0f0f6" }} />
          <div style={{ flex: 1 }}>
            <div style={{ height: "12px", background: "#f0f0f6", borderRadius: "4px", marginBottom: "6px", width: "70%" }} />
            <div style={{ height: "4px", background: "#f0f0f6", borderRadius: "2px", width: "90%" }} />
          </div>
          <div style={{ width: "24px", height: "12px", background: "#f0f0f6", borderRadius: "4px" }} />
        </div>
      ))}
    </div>
  );
}

export default function Home() {
  const [activeCategory, setActiveCategory] = useState("Todos");
  const [articles, setArticles] = useState<NewsArticle[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const [, navigate] = useLocation();

  useEffect(() => {
    document.title = "CRIVO News — A inteligência que separa o fato do ruído";
    const setMeta = (sel: string, val: string) => {
      let el = document.querySelector(sel) as HTMLMetaElement | null;
      if (!el) { el = document.createElement("meta"); const m = sel.match(/\[([^=\]]+)="([^"]+)"\]/); if (m) el.setAttribute(m[1], m[2]); document.head.appendChild(el); }
      el.setAttribute("content", val);
    };
    setMeta('meta[name="description"]', "Portal de notícias com curadoria de IA. Informação filtrada, sem ruído, sem exagero.");
    setMeta('meta[property="og:title"]', "CRIVO News — A inteligência que separa o fato do ruído");
    setMeta('meta[property="og:type"]', "website");
    setMeta('meta[property="og:url"]', "https://crivo.news");
  }, []);

  useEffect(() => {
    if (abortRef.current) abortRef.current.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    const cached = readSessionCache(activeCategory);
    if (cached) { setArticles(cached); setLoading(false); setRefreshing(true); }
    else { setLoading(true); setError(null); }
    fetchNews({ category: activeCategory === "Todos" ? undefined : activeCategory, limit: activeCategory === "Todos" ? 60 : 50 })
      .then((data) => { if (ctrl.signal.aborted) return; writeSessionCache(activeCategory, data); setArticles(data); setError(null); })
      .catch((e) => { if (ctrl.signal.aborted) return; if (!cached) { setError(e?.message || "Erro ao carregar notícias"); setArticles([]); } })
      .finally(() => { if (ctrl.signal.aborted) return; setLoading(false); setRefreshing(false); });
    return () => ctrl.abort();
  }, [activeCategory]);

  const filteredArticles = useMemo(() => {
    if (activeCategory === "Todos") return articles;
    return articles.filter((a) => {
      if (a.categories?.length) return a.categories.some((c) => c.toLowerCase() === activeCategory.toLowerCase());
      return a.category?.toLowerCase() === activeCategory.toLowerCase();
    });
  }, [activeCategory, articles]);

  const featuredArticle = filteredArticles[0];
  const remainingArticles = filteredArticles.slice(1);

  return (
    <div className="min-h-screen bg-background">
      <style dangerouslySetInnerHTML={{ __html: `
        @media (max-width: 900px) {
          .layout-two-col { grid-template-columns: 1fr !important; }
          .layout-sidebar  { display: none !important; }
        }
        @media (max-width: 640px) {
          .news-grid-2col  { grid-template-columns: 1fr !important; }
        }
      ` }} />
      <Header categories={categories} onCategorySelect={setActiveCategory} activeCategory={activeCategory} articles={articles} />

      <main style={{ maxWidth: "1280px", margin: "0 auto", padding: "0 20px" }}>

        {/* Trending pills — largura total */}
        <div style={{ paddingTop: "32px", paddingBottom: "8px", overflow: "hidden" }}>
          <TrendingSection />
        </div>

        {/* Layout 2 colunas: feed (esquerda) + sidebar (direita) */}
        <div style={{
          display: "grid",
          gridTemplateColumns: "1fr 300px",
          gap: "40px",
          alignItems: "start",
        }}
          className="layout-two-col"
          // Responsivo via CSS global — ver abaixo
        >

          {/* ── COLUNA PRINCIPAL ────────────────────────────────────────── */}
          <div>
            {refreshing && (
              <div style={{ marginBottom: "16px", display: "flex", alignItems: "center", gap: "8px", fontSize: "12px", color: "#888" }}>
                <span style={{ width: "8px", height: "8px", borderRadius: "50%", background: "#f97316", display: "inline-block", animation: "pulse 1.5s infinite" }} />
                Atualizando notícias...
              </div>
            )}
            {error && (
              <div style={{ marginBottom: "24px", borderRadius: "12px", border: "1px solid #fca5a5", background: "#fef2f2", padding: "16px", fontSize: "14px" }}>
                {error}
              </div>
            )}

            {/* Hero */}
            <div style={{ marginBottom: "40px" }}>
              {loading ? (
                <div style={{ borderRadius: "16px", border: "1px solid #e8e8f0", background: "#f8f8fc", height: "340px", animation: "pulse 1.5s infinite" }} />
              ) : featuredArticle ? (
                <Hero article={featuredArticle} />
              ) : (
                <div style={{ borderRadius: "16px", border: "1px solid #e8e8f0", background: "#f8f8fc", padding: "40px", textAlign: "center" }}>
                  <p style={{ color: "#888", fontSize: "15px" }}>⏳ Processando notícias com IA...</p>
                </div>
              )}
            </div>

            {/* Cabeçalho da seção */}
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "24px", paddingBottom: "12px", borderBottom: "2px solid #111122" }}>
              <h2 style={{ fontFamily: "'Playfair Display', serif", fontSize: "22px", fontWeight: 700, color: "#111122", margin: 0 }}>
                {activeCategory === "Todos" ? "Últimas Notícias" : `Notícias de ${activeCategory}`}
              </h2>
              {!loading && (
                <span style={{ fontSize: "12px", color: "#888", fontWeight: 600, letterSpacing: "0.5px" }}>
                  {remainingArticles.length} ARTIGOS
                </span>
              )}
            </div>

            {/* Grid de cards */}
            {loading ? (
              <div className="news-grid-2col" style={{ display: "grid", gridTemplateColumns: "repeat(2, 1fr)", gap: "12px" }}>
                {Array.from({ length: 6 }).map((_, i) => <SkeletonCard key={i} />)}
              </div>
            ) : remainingArticles.length > 0 ? (
              <div className="news-grid-2col" style={{ display: "grid", gridTemplateColumns: "repeat(2, 1fr)", gap: "12px" }}>
                {remainingArticles.map((article, index) => (
                  <div key={article.id} className="animate-fade-in-up" style={{ animationDelay: `${index * 30}ms` }}>
                    <NewsCardCompact article={article} />
                  </div>
                ))}
              </div>
            ) : (
              <div style={{ textAlign: "center", padding: "64px 0" }}>
                <p style={{ color: "#888", fontSize: "16px" }}>Nenhuma notícia encontrada.</p>
              </div>
            )}
          </div>

          {/* ── SIDEBAR DIREITA ─────────────────────────────────────────── */}
          <aside className="layout-sidebar" style={{ position: "sticky", top: "24px" }}>
            <TopicosDestaque />

            <MaisLidas articles={articles} />

            {/* Separador */}
            <div style={{ height: "1px", background: "#e8e8f0", margin: "24px 0" }} />

            {/* Mini-rodapé da sidebar */}
            <div style={{ fontSize: "11px", color: "#bbb" }}>
              <p style={{ fontWeight: 700, color: "#999", margin: "0 0 6px", textTransform: "uppercase", letterSpacing: "0.5px", fontSize: "10px" }}>CRIVO NEWS</p>
              <div style={{ display: "flex", flexDirection: "column", gap: "3px" }}>
                {[
                  { label: "Sobre", path: "/sobre" },
                  { label: "Contato", path: "/contato" },
                  { label: "Política Editorial", path: "/politica-editorial" },
                ].map(l => (
                  <span
                    key={l.path}
                    onClick={() => navigate(l.path)}
                    style={{ cursor: "pointer", color: "#aaa", lineHeight: "1.4",
                      padding: "2px 0",
                    }}
                    onMouseEnter={e => (e.currentTarget.style.color = "#b84400")}
                    onMouseLeave={e => (e.currentTarget.style.color = "#aaa")}
                  >
                    {l.label}
                  </span>
                ))}
              </div>
              <p style={{ margin: "10px 0 0", opacity: 0.6, fontSize: "10px" }}>© 2026 CRIVO News</p>
            </div>
          </aside>

        </div>
      </main>

      {/* CSS responsivo — colapsa para 1 coluna no mobile */}
      <style>{`
        @media (max-width: 900px) {
          .layout-two-col {
            grid-template-columns: 1fr !important;
          }
        }
        @media (max-width: 640px) {
          .layout-two-col > div > div[style*="grid-template-columns: repeat(2"] {
            grid-template-columns: 1fr !important;
          }
        }
      `}</style>

    </div>
  );
}

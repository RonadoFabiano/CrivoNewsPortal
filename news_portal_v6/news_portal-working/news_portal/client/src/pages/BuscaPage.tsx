import { useEffect, useState } from "react";
import { useLocation } from "wouter";
import { fetchSearch, NewsArticle } from "@/lib/api";
import Header from "@/components/Header";
import NewsCardCompact from "@/components/NewsCardCompact";
import { categories } from "@/lib/newsData";

function SkeletonCard() {
  return (
    <div style={{ display: "flex", gap: "14px", padding: "16px 18px", borderRadius: "10px", border: "1.5px solid #f0f0f8", background: "#fff" }}>
      <div style={{ width: "92px", height: "92px", borderRadius: "8px", background: "#f0f0f6", flexShrink: 0 }} />
      <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: "8px" }}>
        <div style={{ height: "12px", borderRadius: "4px", background: "#f0f0f6", width: "60%" }} />
        <div style={{ height: "14px", borderRadius: "4px", background: "#f0f0f6", width: "90%" }} />
        <div style={{ height: "14px", borderRadius: "4px", background: "#f0f0f6", width: "75%" }} />
      </div>
    </div>
  );
}


// Campo controlado que sincroniza com a query da URL
function SearchInput({ query, navigate }: { query: string; navigate: (path: string) => void }) {
  const [value, setValue] = useState(query);

  // Sincroniza quando a URL muda (nova busca vinda do dropdown)
  useEffect(() => { setValue(query); }, [query]);

  return (
    <input
      value={value}
      onChange={e => setValue(e.target.value)}
      onKeyDown={e => {
        if (e.key === "Enter") {
          const v = value.trim();
          if (v.length >= 2) navigate(`/busca?q=${encodeURIComponent(v)}`);
        }
      }}
      placeholder="Nova busca..."
      autoFocus
      style={{
        flex: 1, background: "none", border: "none", outline: "none",
        fontSize: "15px", color: "#111122",
        fontFamily: "'DM Sans', sans-serif",
      }}
    />
  );
}

export default function BuscaPage() {
  const [, navigate] = useLocation();
  const [articles, setArticles] = useState<NewsArticle[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);

  // Lê query diretamente da URL — escuta popstate para reagir a mudanças
  const getQ = () => new URLSearchParams(window.location.search).get("q") || "";
  const [query, setQuery] = useState(getQ);

  useEffect(() => {
    // Escuta pushState/replaceState (wouter usa history.pushState)
    const sync = () => setQuery(getQ());
    window.addEventListener("popstate", sync);
    // MutationObserver não funciona para URL — usamos polling leve só nesta página
    const interval = setInterval(() => {
      const q = getQ();
      setQuery(prev => prev !== q ? q : prev);
    }, 150);
    return () => {
      window.removeEventListener("popstate", sync);
      clearInterval(interval);
    };
  }, []);

  useEffect(() => {
    if (!query || query.trim().length < 2) return;
    setLoading(true);
    setSearched(false);
    setArticles([]);
    document.title = `Busca: ${query} — CRIVO News`;
    fetchSearch(query, 60)
      .then(data => { setArticles(data); setSearched(true); })
      .catch(() => { setArticles([]); setSearched(true); })
      .finally(() => setLoading(false));
  }, [query]); // query é state — re-executa quando muda

  return (
    <div className="min-h-screen bg-background">
      <Header
        categories={categories}
        onCategorySelect={cat => {
          if (cat === "Todos") { navigate("/"); return; }
          const slug = cat.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "-");
          navigate("/" + slug);
        }}
        activeCategory="Todos"
      />

      <div className="container" style={{ maxWidth: "900px", margin: "0 auto", padding: "40px 16px" }}>

        {/* Cabeçalho */}
        <div style={{ marginBottom: "32px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "8px" }}>
            <button
              onClick={() => navigate("/")}
              style={{ background: "none", border: "none", cursor: "pointer", color: "#aaa", fontSize: "13px", display: "flex", alignItems: "center", gap: "4px", padding: 0 }}
            >
              ← Voltar
            </button>
          </div>

          <h1 style={{
            fontFamily: "'Playfair Display', serif",
            fontSize: "28px", fontWeight: 800,
            color: "#111122", margin: "0 0 6px",
          }}>
            {loading ? "Buscando..." : searched ? (
              articles.length > 0
                ? <>Resultados para <span style={{ color: "#b84400" }}>"{query}"</span></>
                : <>Nenhum resultado para <span style={{ color: "#b84400" }}>"{query}"</span></>
            ) : `Busca: "${query}"`}
          </h1>

          {!loading && searched && articles.length > 0 && (
            <p style={{ fontSize: "14px", color: "#888", margin: 0 }}>
              {articles.length} artigo{articles.length !== 1 ? "s" : ""} encontrado{articles.length !== 1 ? "s" : ""}
            </p>
          )}
        </div>

        {/* Nova busca */}
        <div style={{
          display: "flex", gap: "10px", marginBottom: "36px",
          background: "#f8f8fc", borderRadius: "10px", padding: "12px 16px",
          border: "1.5px solid #e8e8f0",
        }}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#aaa" strokeWidth="2.5" style={{ flexShrink: 0, marginTop: "2px" }}>
            <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <input
            defaultValue={query}
            placeholder="Nova busca..."
            onKeyDown={e => {
              if (e.key === "Enter") {
                const v = (e.currentTarget.value || "").trim();
                if (v.length >= 2) navigate(`/busca?q=${encodeURIComponent(v)}`);
              }
            }}
            autoFocus
            style={{
              flex: 1, background: "none", border: "none", outline: "none",
              fontSize: "15px", color: "#111122",
              fontFamily: "'DM Sans', sans-serif",
            }}
          />
          <span style={{ fontSize: "11px", color: "#ccc", alignSelf: "center" }}>Enter para buscar</span>
        </div>

        {/* Resultados */}
        {loading ? (
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px" }}>
            {Array.from({ length: 8 }).map((_, i) => <SkeletonCard key={i} />)}
          </div>
        ) : searched && articles.length === 0 ? (
          <div style={{ textAlign: "center", padding: "60px 0" }}>
            <div style={{ fontSize: "48px", marginBottom: "16px" }}>🔍</div>
            <p style={{ fontSize: "16px", color: "#888", marginBottom: "8px" }}>
              Nenhuma notícia encontrada para <strong>"{query}"</strong>
            </p>
            <p style={{ fontSize: "13px", color: "#bbb" }}>
              Tente termos diferentes ou verifique a ortografia
            </p>
          </div>
        ) : (
          <div style={{ display: "grid", gridTemplateColumns: "repeat(2, 1fr)", gap: "12px" }}>
            {articles.map(a => (
              <NewsCardCompact key={a.slug} article={a} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

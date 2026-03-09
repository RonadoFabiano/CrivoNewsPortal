import { useState, useRef, useEffect } from "react";
import { useLocation } from "wouter";
import { fetchSearch, NewsArticle } from "@/lib/api";

export default function SearchBar() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<NewsArticle[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();
  const [, navigate] = useLocation();

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, []);

  const search = (q: string) => {
    if (q.trim().length < 2) { setResults([]); setTotal(0); return; }
    setLoading(true);
    // Busca 30 resultados — mostra 6 no dropdown, resto ao pressionar Enter
    fetchSearch(q, 30).then(data => {
      setResults(data.slice(0, 6));
      setTotal(data.length);
      setLoading(false);
    }).catch(() => setLoading(false));
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const v = e.target.value;
    setQuery(v);
    setOpen(true);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => search(v), 350);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && query.trim().length >= 2) {
      setOpen(false);
      navigate(`/busca?q=${encodeURIComponent(query.trim())}`);
    }
    if (e.key === "Escape") setOpen(false);
  };

  return (
    <div ref={containerRef} style={{ position: "relative" }}>
      <div style={{
        display: "flex", alignItems: "center",
        background: "#f5f5f8",
        border: "1.5px solid #e0e0ea",
        borderRadius: "8px",
        padding: "5px 12px",
        gap: "8px",
        width: "220px",
      }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#aaa" strokeWidth="2.5">
          <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <input
          value={query}
          onChange={handleChange}
          onFocus={() => { setOpen(true); if (query.length >= 2) search(query); }}
          onKeyDown={handleKeyDown}
          placeholder="Buscar notícias..."
          style={{
            background: "none", border: "none", outline: "none",
            fontSize: "13px", color: "#111122",
            fontFamily: "'DM Sans', sans-serif",
            width: "100%",
          }}
        />
        {query && (
          <button onClick={() => { setQuery(""); setResults([]); setOpen(false); }}
            style={{ background: "none", border: "none", cursor: "pointer", color: "#aaa", fontSize: "16px", lineHeight: 1, padding: 0 }}>
            ×
          </button>
        )}
      </div>

      {/* Dropdown */}
      {open && query.length >= 2 && (
        <div style={{
          position: "absolute", top: "calc(100% + 6px)", right: 0,
          width: "400px", maxHeight: "440px", overflowY: "auto",
          background: "#fff",
          border: "1.5px solid #e0e0ea",
          borderRadius: "10px",
          boxShadow: "0 8px 24px rgba(0,0,0,0.1)",
          zIndex: 200,
        }}>
          {loading ? (
            <div style={{ padding: "20px", textAlign: "center", color: "#aaa", fontSize: "13px" }}>
              <span style={{ display: "inline-block", animation: "spin 0.8s linear infinite" }}>⏳</span>
              {" "}Buscando em todas as notícias...
            </div>
          ) : results.length === 0 ? (
            <div style={{ padding: "20px", textAlign: "center" }}>
              <div style={{ fontSize: "24px", marginBottom: "8px" }}>🔍</div>
              <p style={{ color: "#888", fontSize: "13px", margin: 0 }}>
                Nenhum resultado para <strong>"{query}"</strong>
              </p>
            </div>
          ) : (
            <>
              {/* Header do dropdown */}
              <div style={{
                padding: "10px 14px 6px",
                borderBottom: "1px solid #f5f5f8",
                display: "flex", justifyContent: "space-between", alignItems: "center"
              }}>
                <span style={{ fontSize: "11px", color: "#aaa", fontWeight: 700, letterSpacing: "0.5px" }}>
                  {total} RESULTADO{total !== 1 ? "S" : ""} PARA "{query.toUpperCase()}"
                </span>
                {total > 6 && (
                  <span
                    onClick={() => { setOpen(false); navigate(`/busca?q=${encodeURIComponent(query.trim())}`); }}
                    style={{ fontSize: "11px", color: "#b84400", fontWeight: 700, cursor: "pointer" }}
                  >
                    Ver todos →
                  </span>
                )}
              </div>

              {/* Resultados */}
              {results.map((a, i) => (
                <div
                  key={a.slug}
                  onClick={() => { navigate(`/noticia/${a.slug}`); setOpen(false); setQuery(""); }}
                  style={{
                    display: "flex", gap: "12px", alignItems: "flex-start",
                    padding: "10px 14px",
                    cursor: "pointer",
                    borderBottom: i < results.length - 1 ? "1px solid #f8f8fc" : "none",
                    transition: "background 0.1s",
                  }}
                  onMouseEnter={e => (e.currentTarget.style.background = "#fafafa")}
                  onMouseLeave={e => (e.currentTarget.style.background = "transparent")}
                >
                  {/* Thumbnail */}
                  <div style={{ width: "52px", height: "52px", flexShrink: 0, borderRadius: "7px", overflow: "hidden", background: "#f0f0f6" }}>
                    <img
                      src={a.image}
                      alt=""
                      style={{ width: "100%", height: "100%", objectFit: "cover" }}
                      onError={e => ((e.currentTarget as HTMLImageElement).style.display = "none")}
                    />
                  </div>

                  {/* Texto */}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    {/* Categoria + tempo */}
                    <div style={{ display: "flex", gap: "6px", alignItems: "center", marginBottom: "4px" }}>
                      <span style={{
                        fontSize: "9px", fontWeight: 800, textTransform: "uppercase",
                        letterSpacing: "0.4px", color: "#b84400",
                        background: "#fff5f0", borderRadius: "4px", padding: "1px 5px",
                      }}>
                        {a.category}
                      </span>
                      <span style={{ fontSize: "10px", color: "#ccc" }}>
                        {(() => {
                          const d = new Date((a as any).publishedAt || "");
                          const diff = Math.floor((Date.now() - d.getTime()) / 60000);
                          if (isNaN(diff)) return "";
                          if (diff < 60) return `há ${diff} min`;
                          if (diff < 1440) return `há ${Math.floor(diff/60)}h`;
                          return `há ${Math.floor(diff/1440)}d`;
                        })()}
                      </span>
                    </div>
                    {/* Título com termo destacado */}
                    <p style={{
                      fontSize: "12px", fontWeight: 700, color: "#111122", margin: "0 0 3px",
                      lineHeight: "1.4",
                      display: "-webkit-box", WebkitLineClamp: 2,
                      WebkitBoxOrient: "vertical", overflow: "hidden",
                    }}>
                      {a.title}
                    </p>
                    <span style={{ fontSize: "10px", color: "#bbb" }}>{a.source}</span>
                  </div>
                </div>
              ))}

              {/* Footer — Ver todos */}
              {total > 6 && (
                <div
                  onClick={() => { setOpen(false); navigate(`/busca?q=${encodeURIComponent(query.trim())}`); }}
                  style={{
                    padding: "10px 14px",
                    borderTop: "1px solid #f5f5f8",
                    textAlign: "center",
                    cursor: "pointer",
                    fontSize: "12px", fontWeight: 700, color: "#b84400",
                    transition: "background 0.1s",
                  }}
                  onMouseEnter={e => (e.currentTarget.style.background = "#fff5f0")}
                  onMouseLeave={e => (e.currentTarget.style.background = "transparent")}
                >
                  Ver todos os {total} resultados para "{query}" →
                </div>
              )}
            </>
          )}
        </div>
      )}

      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

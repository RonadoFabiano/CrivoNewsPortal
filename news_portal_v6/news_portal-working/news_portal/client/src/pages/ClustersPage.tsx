import { useEffect, useState } from "react";
import { useLocation } from "wouter";
import Header from "@/components/Header";
import { categories } from "@/lib/newsData";
import { fetchClusters, Cluster } from "@/lib/api";

function categorySlug(cat: string) {
  return cat.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "-");
}

function formatDate(iso: string) {
  try { return new Date(iso).toLocaleDateString("pt-BR", { day: "2-digit", month: "short" }); }
  catch { return ""; }
}

export default function ClustersPage() {
  const [, navigate] = useLocation();
  const [clusters, setClusters] = useState<Cluster[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    document.title = "Clusters — Notícias Agrupadas | CRIVO News";
    const setMeta = (sel: string, val: string) => {
      let el = document.querySelector(sel) as HTMLMetaElement | null;
      if (!el) { el = document.createElement("meta"); const m = sel.match(/\[([^=\]]+)="([^"]+)"\]/); if (m) el.setAttribute(m[1], m[2]); document.head.appendChild(el); }
      el.setAttribute("content", val);
    };
    setMeta('meta[name="description"]', "Notícias agrupadas por assunto. Veja todos os ângulos de uma mesma história.");
    setMeta('meta[property="og:title"]', "Clusters — Notícias Agrupadas | CRIVO News");
    let link = document.querySelector('link[rel="canonical"]') as HTMLLinkElement | null;
    if (!link) { link = document.createElement("link"); link.rel = "canonical"; document.head.appendChild(link); }
    link.href = "https://crivo.news/clusters";
    return () => { document.title = "CRIVO News"; };
  }, []);

  useEffect(() => {
    fetchClusters().then(setClusters).finally(() => setLoading(false));
  }, []);

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
            <span style={{ fontSize: "28px" }}>🗂️</span>
            <h1 style={{ fontFamily: "'Playfair Display', serif", fontWeight: 700, fontSize: "clamp(24px, 3vw, 36px)", color: "#111122", margin: 0 }}>
              Notícias Agrupadas
            </h1>
          </div>
          <p style={{ color: "#666", fontSize: "15px", marginLeft: "44px" }}>
            {loading ? "Calculando clusters..." : `${clusters.length} grupos de notícias sobre o mesmo assunto`}
          </p>
        </div>

        {loading ? (
          <div style={{ display: "grid", gap: "24px" }}>
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} style={{ borderRadius: "16px", border: "1px solid #e0e0e8", padding: "24px", animation: "pulse 1.5s infinite" }}>
                <div style={{ height: "20px", background: "#e8e8ee", borderRadius: "4px", width: "60%", marginBottom: "12px" }} />
                <div style={{ height: "14px", background: "#e8e8ee", borderRadius: "4px", width: "40%" }} />
              </div>
            ))}
          </div>
        ) : clusters.length === 0 ? (
          <div style={{ textAlign: "center", padding: "80px 0", color: "#666" }}>
            <p style={{ fontSize: "18px" }}>Nenhum cluster encontrado ainda.</p>
            <p style={{ fontSize: "14px", marginTop: "8px" }}>Os clusters são gerados automaticamente conforme mais notícias chegam.</p>
          </div>
        ) : (
          <div style={{ display: "grid", gap: "24px" }}>
            {clusters.map((cluster, i) => (
              <div key={i} style={{
                borderRadius: "16px", border: "1px solid #e0e0e8",
                overflow: "hidden", background: "#fff",
                transition: "box-shadow 0.2s",
              }}
                onMouseEnter={e => (e.currentTarget as HTMLElement).style.boxShadow = "0 4px 20px rgba(0,0,0,0.08)"}
                onMouseLeave={e => (e.currentTarget as HTMLElement).style.boxShadow = "none"}
              >
                {/* Header do cluster */}
                <div style={{ padding: "20px 24px 16px", borderBottom: "1px solid #f0f0f4", display: "flex", alignItems: "flex-start", gap: "16px" }}>
                  {cluster.image && (
                    <img src={cluster.image} alt="" style={{ width: "72px", height: "72px", objectFit: "cover", borderRadius: "8px", flexShrink: 0 }}
                      onError={e => { e.currentTarget.style.display = "none"; }} />
                  )}
                  <div style={{ flex: 1 }}>
                    <div style={{ display: "flex", alignItems: "center", gap: "10px", marginBottom: "6px" }}>
                      <span style={{
                        background: "#b84400", color: "#fff", borderRadius: "12px",
                        padding: "2px 10px", fontSize: "12px", fontWeight: 700
                      }}>
                        {cluster.size} notícias
                      </span>
                      <span style={{ fontSize: "12px", color: "#999" }}>{formatDate(cluster.publishedAt)}</span>
                    </div>
                    <h2 style={{ fontFamily: "'DM Sans', sans-serif", fontWeight: 700, fontSize: "18px", color: "#111122", margin: 0, lineHeight: 1.3 }}>
                      {cluster.label}
                    </h2>
                  </div>
                </div>

                {/* Artigos do cluster */}
                <div style={{ padding: "16px 24px 20px" }}>
                  <div style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
                    {cluster.articles.map((art, j) => (
                      <div key={j}
                        onClick={() => navigate(`/noticia/${art.slug}`)}
                        style={{ display: "flex", alignItems: "flex-start", gap: "10px", cursor: "pointer", padding: "8px", borderRadius: "8px", transition: "background 0.15s" }}
                        onMouseEnter={e => (e.currentTarget as HTMLElement).style.background = "#f8f8fc"}
                        onMouseLeave={e => (e.currentTarget as HTMLElement).style.background = "transparent"}
                      >
                        <span style={{ color: "#b84400", fontWeight: 700, fontSize: "13px", flexShrink: 0, paddingTop: "1px" }}>
                          {String(j + 1).padStart(2, "0")}
                        </span>
                        <div style={{ flex: 1 }}>
                          <p style={{ margin: 0, fontSize: "14px", fontWeight: 600, color: "#222233", lineHeight: 1.4 }}>{art.title}</p>
                          <span style={{ fontSize: "11px", color: "#999", marginTop: "2px", display: "block" }}>{art.source}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
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

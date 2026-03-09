import { NewsArticle } from "@/lib/newsData";
import { useLocation } from "wouter";
import { fetchNewsBySlug } from "@/lib/api";

function timeAgo(dateStr?: string): string {
  if (!dateStr) return "";
  try {
    const date = new Date(dateStr);
    const diff = Math.floor((Date.now() - date.getTime()) / 1000);
    if (diff < 3600) return `há ${Math.floor(diff / 60)} min`;
    if (diff < 86400) return `há ${Math.floor(diff / 3600)}h`;
    return date.toLocaleDateString("pt-BR", { day: "2-digit", month: "long", year: "numeric" });
  } catch { return ""; }
}

const CAT_COLORS: Record<string, string> = {
  "Política": "#b91c1c", "Brasil": "#b45309", "Economia": "#065f46",
  "Mundo": "#1e40af", "Tecnologia": "#6d28d9", "Esportes": "#c2410c",
  "Saúde": "#047857", "Justiça": "#334155",
};

export default function Hero({ article }: { article: NewsArticle }) {
  const [, navigate] = useLocation();
  const catColor = CAT_COLORS[article.category || ""] || "#b84400";

  return (
    <section
      onClick={() => navigate(`/noticia/${article.slug}`)}
      onMouseEnter={() => fetchNewsBySlug(article.slug).catch(() => {})}
      itemScope itemType="https://schema.org/NewsArticle"
      style={{
        display: "grid",
        gridTemplateColumns: "1fr 1fr",
        borderRadius: "12px",
        overflow: "hidden",
        cursor: "pointer",
        border: "1.5px solid #e8e8f0",
        minHeight: "320px",
        background: "#fff",
      }}
      className="hero-grid"
    >
      {/* Lado esquerdo — texto */}
      <div style={{
        padding: "36px 32px",
        display: "flex",
        flexDirection: "column",
        justifyContent: "space-between",
        background: "#fff",
      }}>
        <div>
          {/* Categoria + tempo */}
          <div style={{ display: "flex", alignItems: "center", gap: "10px", marginBottom: "16px" }}>
            <span style={{
              fontSize: "11px", fontWeight: 800,
              letterSpacing: "0.5px", textTransform: "uppercase",
              background: catColor + "18", color: catColor,
              borderRadius: "6px", padding: "3px 10px",
            }} itemProp="articleSection">
              {article.category}
            </span>
            <span style={{ fontSize: "12px", color: "#aaa" }} itemProp="datePublished">
              {timeAgo((article as any).publishedAt || article.date)}
            </span>
          </div>

          {/* Título */}
          <h2 style={{
            fontFamily: "'Playfair Display', serif",
            fontSize: "clamp(20px, 2.2vw, 28px)",
            fontWeight: 800,
            color: "#111122",
            lineHeight: 1.3,
            marginBottom: "16px",
            margin: "0 0 16px 0",
          }} itemProp="headline">
            {article.title}
          </h2>

          {/* Resumo */}
          <p style={{
            fontSize: "14px",
            lineHeight: "1.7",
            color: "#555",
            margin: "0 0 24px 0",
            display: "-webkit-box",
            WebkitLineClamp: 5,
            WebkitBoxOrient: "vertical",
            overflow: "hidden",
          }} itemProp="description">
            {article.summary}
          </p>
        </div>

        {/* Botão */}
        <div>
          <span style={{
            display: "inline-flex", alignItems: "center", gap: "8px",
            padding: "11px 22px",
            background: "#b84400", color: "#fff",
            borderRadius: "8px", fontSize: "13px", fontWeight: 700,
            fontFamily: "'DM Sans', sans-serif",
            letterSpacing: "0.1px",
            transition: "background 0.15s",
          }}>
            Leia a notícia completa
            <svg width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7"/>
            </svg>
          </span>
          {article.source && (
            <span style={{ display: "block", fontSize: "11px", color: "#bbb", marginTop: "10px" }}>
              Fonte: {article.source}
            </span>
          )}
        </div>
      </div>

      {/* Lado direito — imagem */}
      <div style={{ position: "relative", overflow: "hidden", minHeight: "300px" }}>
        <img
          src={article.image || "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=800&h=600&fit=crop"}
          alt={article.title}
          itemProp="image"
          style={{
            width: "100%", height: "100%",
            objectFit: "cover",
            objectPosition: "center top",
            position: "absolute", inset: 0,
            transition: "transform 0.5s ease",
          }}
          className="hero-img"
          onError={e => {
            (e.currentTarget as HTMLImageElement).src =
              "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=800&h=600&fit=crop";
          }}
        />
        {/* Barra laranja no topo */}
        <div style={{ position: "absolute", top: 0, left: 0, right: 0, height: "3px", background: "#b84400" }} />
      </div>

      <style>{`
        .hero-grid:hover .hero-img { transform: scale(1.04); }
        @media (max-width: 700px) {
          .hero-grid { grid-template-columns: 1fr !important; }
          .hero-grid > div:last-child { min-height: 200px !important; }
        }
      `}</style>
    </section>
  );
}

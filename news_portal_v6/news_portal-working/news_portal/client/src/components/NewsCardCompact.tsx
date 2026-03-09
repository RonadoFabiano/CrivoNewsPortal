import { useState } from "react";
import { useLocation } from "wouter";
import { fetchNewsBySlug } from "@/lib/api";
import { NewsArticle } from "@/lib/newsData";

// Cores por categoria — mesmas do portal
const CAT_COLORS: Record<string, { bg: string; color: string }> = {
  "Brasil":         { bg: "#fef3c7", color: "#b45309" },
  "Política":       { bg: "#fee2e2", color: "#b91c1c" },
  "Economia":       { bg: "#d1fae5", color: "#065f46" },
  "Negócios":       { bg: "#d1fae5", color: "#065f46" },
  "Mundo":          { bg: "#dbeafe", color: "#1e40af" },
  "Tecnologia":     { bg: "#ede9fe", color: "#6d28d9" },
  "Esportes":       { bg: "#ffedd5", color: "#c2410c" },
  "Saúde":          { bg: "#ecfdf5", color: "#047857" },
  "Ciência":        { bg: "#e0f2fe", color: "#0369a1" },
  "Educação":       { bg: "#fef9c3", color: "#a16207" },
  "Entretenimento": { bg: "#fce7f3", color: "#9d174d" },
  "Justiça":        { bg: "#f1f5f9", color: "#334155" },
  "Cultura":        { bg: "#fdf4ff", color: "#7e22ce" },
  "Cotidiano":      { bg: "#f0fdf4", color: "#166534" },
};

function timeAgo(dateStr?: string): string {
  if (!dateStr) return "";
  try {
    const date = new Date(dateStr);
    const diff = Math.floor((Date.now() - date.getTime()) / 1000);
    if (diff < 60)   return "agora mesmo";
    if (diff < 3600) return `há ${Math.floor(diff / 60)} min`;
    if (diff < 86400) return `há ${Math.floor(diff / 3600)}h`;
    if (diff < 172800) return "ontem";
    return date.toLocaleDateString("pt-BR", { day: "2-digit", month: "short" });
  } catch { return ""; }
}

interface Props {
  article: NewsArticle;
}

export default function NewsCardCompact({ article }: Props) {
  const [, navigate] = useLocation();
  const [hovered, setHovered] = useState(false);

  const cat = article.category || "Geral";
  const catStyle = CAT_COLORS[cat] || { bg: "#f1f5f9", color: "#475569" };
  const timeLabel = timeAgo((article as any).publishedAt || article.date);
  const isOriginal = article.source === "CRIVO News";

  return (
    <article
      onClick={() => navigate(`/noticia/${article.slug}`)}
      onMouseEnter={() => {
        setHovered(true);
        // Prefetch silencioso — popula o cache antes do clique
        fetchNewsBySlug(article.slug).catch(() => {});
      }}
      onMouseLeave={() => setHovered(false)}
      itemScope
      itemType="https://schema.org/NewsArticle"
      style={{
        display: "flex",
        gap: "14px",
        padding: "16px 18px",
        borderRadius: "10px",
        cursor: "pointer",
        border: "1.5px solid",
        borderColor: hovered ? "#e2e2f0" : "#f0f0f8",
        background: hovered ? "#fafafa" : "#fff",
        boxShadow: hovered ? "0 4px 16px rgba(0,0,0,0.07)" : "none",
        transform: hovered ? "translateY(-2px)" : "translateY(0)",
        transition: "all 0.18s ease",
      }}
    >
      {/* Imagem à esquerda */}
      <div style={{
        width: "92px", height: "92px",
        flexShrink: 0,
        borderRadius: "8px",
        overflow: "hidden",
        background: "#f0f0f6",
        position: "relative",
      }}>
        <img
          src={article.image || "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=300&h=300&fit=crop"}
          alt={article.title}
          itemProp="image"
          style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }}
          onError={e => {
            (e.currentTarget as HTMLImageElement).src =
              "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=300&h=300&fit=crop";
          }}
        />
        {/* Selo CRIVO Original */}
        {isOriginal && (
          <div style={{
            position: "absolute", bottom: "4px", left: "4px",
            background: "#16a34a", color: "#fff",
            fontSize: "8px", fontWeight: 800,
            padding: "2px 5px", borderRadius: "4px",
            letterSpacing: "0.4px",
          }}>
            CRIVO
          </div>
        )}
      </div>

      {/* Conteúdo à direita */}
      <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: "5px" }}>

        {/* Linha topo: tag categoria + tempo + selo verificado */}
        <div style={{ display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap" }}>
          <span
            itemProp="articleSection"
            style={{
              fontSize: "10px", fontWeight: 700,
              letterSpacing: "0.4px", textTransform: "uppercase",
              background: catStyle.bg, color: catStyle.color,
              borderRadius: "5px", padding: "2px 7px",
            }}
          >
            {cat}
          </span>

          {/* Ícone "Verificado pelo CRIVO" */}
          <span title="Resumo verificado por IA CRIVO" style={{
            display: "inline-flex", alignItems: "center", gap: "3px",
            fontSize: "9px", fontWeight: 700, color: "#16a34a",
            background: "#f0fdf4", borderRadius: "5px", padding: "2px 6px",
          }}>
            <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="20 6 9 17 4 12"/>
            </svg>
            CRIVO
          </span>

          <span style={{
            fontSize: "10px", color: "#aaa",
            marginLeft: "auto", flexShrink: 0,
            fontFamily: "'DM Sans', sans-serif",
          }}
            itemProp="datePublished"
          >
            {timeLabel}
          </span>
        </div>

        {/* Título */}
        <h3
          itemProp="headline"
          style={{
            fontSize: "13px", fontWeight: 700, lineHeight: "1.45",
            color: hovered ? "#b84400" : "#111122",
            fontFamily: "'DM Sans', sans-serif",
            margin: 0,
            display: "-webkit-box",
            WebkitLineClamp: 2,
            WebkitBoxOrient: "vertical",
            overflow: "hidden",
            transition: "color 0.15s",
          }}
        >
          {article.title}
        </h3>

        {/* Resumo — 2 linhas máximo */}
        <p
          itemProp="description"
          style={{
            fontSize: "11px", lineHeight: "1.55",
            color: "#777",
            margin: 0,
            display: "-webkit-box",
            WebkitLineClamp: 2,
            WebkitBoxOrient: "vertical",
            overflow: "hidden",
          }}
        >
          {article.summary}
        </p>

        {/* Fonte */}
        {article.source && (
          <span style={{ fontSize: "10px", color: "#bbb", fontWeight: 600 }}>
            {article.source}
          </span>
        )}
      </div>
    </article>
  );
}

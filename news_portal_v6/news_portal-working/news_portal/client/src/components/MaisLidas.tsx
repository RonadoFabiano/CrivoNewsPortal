import { useEffect, useState } from "react";
import { useLocation } from "wouter";
import { NewsArticle } from "@/lib/api";
import { getLatestNews } from "@/lib/newsCache";

export default function MaisLidas({ articles: propArticles }: { articles?: NewsArticle[] } = {}) {
  const [articles, setArticles] = useState<NewsArticle[]>([]);
  const [, navigate] = useLocation();

  useEffect(() => {
    if (propArticles && propArticles.length > 0) {
      setArticles(propArticles.slice(0, 5));
      return;
    }
    getLatestNews(5)
      .then(data => setArticles(data.slice(0, 5)))
      .catch(() => {});
  }, [propArticles]);

  if (articles.length === 0) return null;

  return (
    <section style={{ marginBottom: "32px" }}>
      {/* Cabeçalho */}
      <div style={{
        display: "flex", alignItems: "center", justifyContent: "space-between",
        borderBottom: "2px solid #111122",
        paddingBottom: "10px", marginBottom: "4px",
      }}>
        <h2 style={{
          fontFamily: "'Playfair Display', serif",
          fontSize: "17px", fontWeight: 700,
          color: "#111122", margin: 0,
        }}>
          Mais Lidas
        </h2>
        <span style={{ fontSize: "10px", color: "#aaa", fontWeight: 600, letterSpacing: "0.5px" }}>
          HOJE
        </span>
      </div>

      <div>
        {articles.map((a, i) => (
          <a
            key={a.slug}
            href={`/noticia/${a.slug}`}
            onClick={(event) => { event.preventDefault(); navigate(`/noticia/${a.slug}`); }}
            style={{
              display: "flex",
              gap: "12px",
              alignItems: "flex-start",
              padding: "11px 0",
              borderBottom: i < 4 ? "1px solid #f0f0f6" : "none",
              cursor: "pointer",
              textDecoration: "none",
            }}
            onMouseEnter={e => (e.currentTarget.style.paddingLeft = "6px")}
            onMouseLeave={e => (e.currentTarget.style.paddingLeft = "0")}
          >
            {/* Número */}
            <div style={{
              fontSize: "20px",
              fontWeight: 800,
              color: "#b84400",
              fontFamily: "'DM Sans', sans-serif",
              lineHeight: 1,
              flexShrink: 0,
              width: "26px",
              textAlign: "center",
              paddingTop: "2px",
            }}>
              {i + 1}
            </div>

            {/* Texto */}
            <div style={{ flex: 1, minWidth: 0 }}>
              <p style={{
                fontSize: "12px", fontWeight: 700,
                color: "#111122",
                fontFamily: "'DM Sans', sans-serif",
                margin: "0 0 4px",
                lineHeight: "1.4",
                display: "-webkit-box",
                WebkitLineClamp: 2,
                WebkitBoxOrient: "vertical",
                overflow: "hidden",
                transition: "color 0.15s",
              }}
                onMouseEnter={e => (e.currentTarget.style.color = "#b84400")}
                onMouseLeave={e => (e.currentTarget.style.color = "#111122")}
              >
                {a.title}
              </p>
              <span style={{ fontSize: "10px", color: "#bbb", fontWeight: 600 }}>
                {a.source}
              </span>
            </div>
          </a>
        ))}
      </div>
    </section>
  );
}

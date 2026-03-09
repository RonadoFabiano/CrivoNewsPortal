import { useEffect, useRef, useState } from "react";
import { useLocation } from "wouter";
import { getLatestNews } from "@/lib/newsCache";

export default function BarraAgora({ articles: propArticles }: { articles?: import("@/lib/api").NewsArticle[] } = {}) {
  const [items, setItems] = useState<{ title: string; slug: string; time: string }[]>([]);
  const [paused, setPaused] = useState(false);
  const [pos, setPos] = useState(0);
  const trackRef = useRef<HTMLDivElement>(null);
  const [, navigate] = useLocation();
  const rafRef = useRef<number>();
  const speed = 0.55;

  useEffect(() => {
    const source = propArticles && propArticles.length > 0
      ? Promise.resolve(propArticles)
      : getLatestNews(10);
    source.then(articles => {
      setItems(articles.slice(0, 8).map(a => ({
        title: a.title,
        slug: a.slug,
        time: (() => {
          const d = new Date((a as any).publishedAt || a.date);
          const diff = Math.floor((Date.now() - d.getTime()) / 60000);
          if (isNaN(diff) || diff < 0) return "";
          if (diff < 60) return `há ${diff} min`;
          return `há ${Math.floor(diff / 60)}h`;
        })(),
      })));
    }).catch(() => {});
  }, []);

  useEffect(() => {
    if (items.length === 0 || paused) return;
    const track = trackRef.current;
    if (!track) return;
    const animate = () => {
      setPos(p => {
        const half = track.scrollWidth / 2;
        return p >= half ? 0 : p + speed;
      });
      rafRef.current = requestAnimationFrame(animate);
    };
    rafRef.current = requestAnimationFrame(animate);
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); };
  }, [items, paused]);

  if (items.length === 0) return null;

  const doubled = [...items, ...items];

  return (
    <div style={{
      background: "#FFFBEB",
      borderBottom: "2px solid #EA580C",
      borderTop: "1px solid #FEF3C7",
      height: "38px",
      display: "flex",
      alignItems: "center",
      overflow: "hidden",
      position: "relative",
      zIndex: 40,
    }}>

      {/* Badge AGORA — centralizado, com blink ao vivo */}
      <div style={{
        background: "#EA580C",
        color: "#fff",
        fontFamily: "'DM Sans', sans-serif",
        fontWeight: 800,
        fontSize: "11px",
        letterSpacing: "1.2px",
        padding: "0 14px",
        height: "100%",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        flexShrink: 0,
        gap: "7px",
      }}>
        {/* Ponto vermelho com ripple */}
        <span style={{
          width: "9px", height: "9px", borderRadius: "50%",
          background: "#ef4444",
          display: "inline-block",
          flexShrink: 0,
          animation: "agoraRipple 1.6s ease-out infinite",
        }} />
        <span style={{ animation: "agoraTextBlink 1s step-end infinite" }}>AGORA</span>
      </div>

      {/* Separador */}
      <div style={{ width: "1px", height: "100%", background: "#FED7AA", flexShrink: 0 }} />

      {/* Ticker com fade nas bordas */}
      <div
        style={{
          flex: 1,
          overflow: "hidden",
          position: "relative",
          WebkitMaskImage: "linear-gradient(to right, transparent 0%, black 3%, black 97%, transparent 100%)",
          maskImage: "linear-gradient(to right, transparent 0%, black 3%, black 97%, transparent 100%)",
        }}
        onMouseEnter={() => setPaused(true)}
        onMouseLeave={() => setPaused(false)}
      >
        <div
          ref={trackRef}
          style={{
            display: "flex",
            alignItems: "center",
            transform: `translateX(-${pos}px)`,
            willChange: "transform",
            whiteSpace: "nowrap",
          }}
        >
          {doubled.map((item, i) => (
            <div
              key={i}
              onClick={() => navigate(`/noticia/${item.slug}`)}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: "10px",
                padding: "0 28px",
                cursor: "pointer",
                borderRight: "1px solid #FED7AA",
              }}
              onMouseEnter={e => (e.currentTarget.style.background = "rgba(234,88,12,0.06)")}
              onMouseLeave={e => (e.currentTarget.style.background = "transparent")}
            >
              {/* Dot separador */}
              <span style={{
                width: "5px", height: "5px", borderRadius: "50%",
                background: "#EA580C", flexShrink: 0,
              }} />

              {/* Título — escuro para contraste */}
              <span style={{
                fontSize: "12.5px",
                fontWeight: 600,
                color: "#111827",
                fontFamily: "'DM Sans', sans-serif",
                transition: "color 0.15s",
              }}
                onMouseEnter={e => (e.currentTarget.style.color = "#EA580C")}
                onMouseLeave={e => (e.currentTarget.style.color = "#111827")}
              >
                {item.title.length > (typeof window !== "undefined" && window.innerWidth < 640 ? 45 : 72) ? item.title.slice(0, typeof window !== "undefined" && window.innerWidth < 640 ? 45 : 72) + "…" : item.title}
              </span>

              {/* Tempo — laranja para não competir com o título */}
              {item.time && (
                <span style={{
                  fontSize: "10px",
                  fontWeight: 700,
                  color: "#EA580C",
                  flexShrink: 0,
                }}>
                  {item.time}
                </span>
              )}
            </div>
          ))}
        </div>
      </div>

      <style>{`
        @media (max-width: 640px) {
          .agora-ticker-item { padding: 0 16px !important; }
        }
        @keyframes agoraTextBlink {
          0%, 100% { opacity: 1; }
          49%       { opacity: 1; }
          50%       { opacity: 0; }
          99%       { opacity: 0; }
        }
        @keyframes agoraRipple {
          0%   { box-shadow: 0 0 0 0 rgba(239,68,68,0.8); }
          70%  { box-shadow: 0 0 0 9px rgba(239,68,68,0); }
          100% { box-shadow: 0 0 0 0 rgba(239,68,68,0); }
        }
      `}</style>
    </div>
  );
}

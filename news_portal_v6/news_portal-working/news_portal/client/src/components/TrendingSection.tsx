import { useEffect, useRef, useState } from "react";
import { useLocation } from "wouter";
import { TrendingItem } from "@/lib/api";
import { getCachedTrending } from "@/lib/newsCache";

const TYPE_LABELS: Record<string, string> = {
  category: "", country: "país", person: "pessoa", topic: "tema",
};

// Seta de tendência por tipo
function trendArrow(item: TrendingItem) {
  if (item.type === "country") return { arrow: "↗", color: "#3b82f6" };
  if (item.type === "person")  return { arrow: "↗", color: "#b84400" };
  if (item.type === "topic")   return { arrow: "↗", color: "#16a34a" };
  return { arrow: "↗", color: "#b84400" };
}

// Mini sparkline SVG — gerado aleatoriamente por seed do label
function Sparkline({ label, color }: { label: string; color: string }) {
  const seed = label.split("").reduce((a, c) => a + c.charCodeAt(0), 0);
  const pts: number[] = [];
  let v = 8 + (seed % 4);
  for (let i = 0; i < 6; i++) {
    v = Math.max(1, Math.min(13, v + ((seed * (i + 7)) % 5) - 2));
    pts.push(v);
  }
  const points = pts.map((y, i) => `${i * 5.6},${14 - y}`).join(" ");
  return (
    <svg width="28" height="14" viewBox="0 0 28 14" style={{ flexShrink: 0, opacity: 0.75 }}>
      <polyline points={points} fill="none" stroke={color} strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function SignalChip({ item, onClick }: { item: TrendingItem; onClick: () => void }) {
  const [hovered, setHovered] = useState(false);
  const [count, setCount] = useState(item.count);
  const typeLabel = TYPE_LABELS[item.type] || "";
  const { arrow, color } = trendArrow(item);

  const chipColor = item.type === "country" ? "#3b82f6"
    : item.type === "topic" ? "#16a34a" : "#b84400";

  return (
    <button
      onClick={onClick}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      style={{
        display: "inline-flex", alignItems: "center", gap: "6px",
        padding: "6px 12px 6px 9px",
        borderRadius: "20px",
        background: hovered ? "#fff" : "#f8f8fc",
        border: `1.5px solid ${hovered ? chipColor : "#e0e0ee"}`,
        cursor: "pointer",
        transition: "all 0.22s cubic-bezier(0.34,1.56,0.64,1)",
        transform: hovered ? "translateY(-2px)" : "translateY(0)",
        boxShadow: hovered ? `0 4px 16px ${chipColor}22, 0 1px 4px rgba(0,0,0,0.06)` : "none",
        outline: "none",
        flexShrink: 0,
        whiteSpace: "nowrap",
        position: "relative",
      }}
    >
      {/* Dot pulsante */}
      <span style={{
        width: "7px", height: "7px", borderRadius: "50%",
        background: chipColor, flexShrink: 0, display: "inline-block",
        animation: "chipRipple 2.4s ease-in-out infinite",
        // cor do ripple via filtro
      }} />

      {/* Nome */}
      <span style={{
        fontSize: "12px", fontWeight: 700,
        color: hovered ? chipColor : "#111122",
        fontFamily: "'DM Sans', sans-serif",
        transition: "color 0.2s",
      }}>
        {item.label}
      </span>

      {/* Tipo */}
      {typeLabel && (
        <span style={{ fontSize: "9px", color: "#bbb", fontWeight: 600, letterSpacing: "0.2px" }}>
          {typeLabel}
        </span>
      )}

      {/* Sparkline */}
      <Sparkline label={item.label} color={chipColor} />

      {/* Seta */}
      <span style={{ fontSize: "10px", color, fontWeight: 800 }}>{arrow}</span>

      {/* Contador */}
      <span style={{
        fontFamily: "'DM Mono', monospace",
        fontSize: "11px", fontWeight: 500,
        color: "#fff",
        background: hovered ? chipColor : chipColor + "cc",
        padding: "1px 7px", borderRadius: "10px",
        minWidth: "28px", textAlign: "center",
        transition: "background 0.2s",
      }}>
        {count}
      </span>

      <style>{`
        @keyframes chipRipple {
          0%   { box-shadow: 0 0 0 0 ${chipColor}80; }
          60%  { box-shadow: 0 0 0 5px ${chipColor}00; }
          100% { box-shadow: 0 0 0 0 ${chipColor}00; }
        }
      `}</style>
    </button>
  );
}

export default function TrendingSection() {
  const [items, setItems]     = useState<TrendingItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [, navigate]          = useLocation();

  useEffect(() => {
    getCachedTrending()
      .then(data => { setItems(data); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  if (!loading && items.length === 0) return null;

  return (
    <section style={{ marginBottom: "28px" }}>
      <div style={{ display: "flex", alignItems: "center", gap: "10px", marginBottom: "14px" }}>
        <div style={{
          width: "3px", height: "22px", borderRadius: "2px",
          background: "linear-gradient(180deg,#b84400,#e07020)", flexShrink: 0,
        }} />
        <h2 style={{
          fontFamily: "'DM Sans', sans-serif", fontWeight: 700, fontSize: "15px",
          color: "#111122", margin: 0, letterSpacing: "-0.2px",
        }}>
          Assuntos em Alta
        </h2>
        <span style={{ fontSize: "10px", color: "#aaa", fontWeight: 600, letterSpacing: "0.4px" }}>
          ÚLTIMAS 12H
        </span>
      </div>

      {loading ? (
        <div style={{ display: "flex", gap: "8px" }}>
          {Array.from({ length: 7 }).map((_, i) => (
            <div key={i} style={{
              height: "32px", borderRadius: "20px", background: "#f0f0f6",
              width: `${60 + i * 15}px`, opacity: 0.4, flexShrink: 0,
            }} />
          ))}
        </div>
      ) : (
        <div style={{
          display: "flex", gap: "8px",
          overflowX: "auto", flexWrap: "nowrap",
          paddingBottom: "4px",
          scrollbarWidth: "none", msOverflowStyle: "none",
        }}>
          {items.map((item, i) => (
            <SignalChip key={i} item={item} onClick={() => navigate("/" + item.slug)} />
          ))}
        </div>
      )}
    </section>
  );
}

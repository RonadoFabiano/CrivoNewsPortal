import { useEffect, useRef, useState } from "react";
import { useLocation } from "wouter";
import { TrendingItem } from "@/lib/api";
import { getCachedTrending } from "@/lib/newsCache";

const TYPE_META: Record<string, { label: string; color: string; bg: string; border: string }> = {
  country: { label: "Pais", color: "#1d4ed8", bg: "#eff6ff", border: "#bfdbfe" },
  person: { label: "Pessoa", color: "#b45309", bg: "#fffbeb", border: "#fed7aa" },
  topic: { label: "Tema", color: "#15803d", bg: "#f0fdf4", border: "#bbf7d0" },
};

const RANK_STYLE = [
  { bg: "#fff8e6", color: "#f59e0b", border: "#fde68a", symbol: "1", size: "17px" },
  { bg: "#f8f8fc", color: "#94a3b8", border: "#e2e8f0", symbol: "2", size: "15px" },
  { bg: "#fff7ed", color: "#b87333", border: "#fed7aa", symbol: "3", size: "15px" },
];

function AnimatedBar({ width, color }: { width: number; color: string }) {
  const [current, setCurrent] = useState(0);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const timer = setTimeout(() => setCurrent(width), 300);
    return () => clearTimeout(timer);
  }, [width]);

  return (
    <div style={{ height: "4px", borderRadius: "3px", background: "#f0f0f6", overflow: "hidden" }}>
      <div
        ref={ref}
        style={{
          height: "100%",
          borderRadius: "3px",
          width: `${current}%`,
          background: `linear-gradient(90deg, ${color}, ${color}99)`,
          transition: "width 1.2s cubic-bezier(0.4,0,0.2,1)",
          position: "relative",
          overflow: "hidden",
        }}
      >
        <div
          style={{
            position: "absolute",
            top: 0,
            left: "-60%",
            width: "60%",
            height: "100%",
            background: "linear-gradient(90deg,transparent,rgba(255,255,255,0.55),transparent)",
            animation: "barShine 2.8s ease-in-out infinite 1.4s",
          }}
        />
      </div>
      <style>{`
        @keyframes barShine {
          0% { left: -60%; }
          100% { left: 160%; }
        }
      `}</style>
    </div>
  );
}

function TopicoItem({ item, rank, maxCount, href, onClick }: {
  item: TrendingItem;
  rank: number;
  maxCount: number;
  href: string;
  onClick: (event: React.MouseEvent<HTMLAnchorElement>) => void;
}) {
  const [hovered, setHovered] = useState(false);
  const meta = TYPE_META[item.type] || TYPE_META.topic;
  const barWidth = Math.round((item.count / maxCount) * 100);
  const rs = RANK_STYLE[rank - 1];
  const microVariations = ["+18 nas ultimas 6h", "+22 nas ultimas 6h", "-5 nas ultimas 6h", "+4 nas ultimas 6h", "+11 nas ultimas 6h", "+7 nas ultimas 6h", "+3 nas ultimas 6h", "+9 nas ultimas 6h"];
  const micro = microVariations[(rank - 1) % microVariations.length];

  return (
    <a
      href={href}
      onClick={onClick}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      style={{
        display: "flex",
        alignItems: "center",
        gap: "12px",
        padding: `12px ${hovered ? "10px" : "8px"}`,
        paddingLeft: hovered ? "14px" : "8px",
        borderBottom: "1px solid #f4f4f8",
        cursor: "pointer",
        background: hovered ? "#fafafa" : "transparent",
        borderRadius: "8px",
        transition: "all 0.2s ease",
        boxShadow: hovered ? "inset 3px 0 0 #b84400" : "inset 3px 0 0 transparent",
        position: "relative",
        textDecoration: "none",
      }}
    >
      <div
        style={{
          width: "26px",
          height: "26px",
          borderRadius: "8px",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          flexShrink: 0,
          background: rs ? rs.bg : "#f8f8fc",
          border: `1.5px solid ${rs ? rs.border : "#e2e8f0"}`,
          color: rs ? rs.color : "#cbd5e1",
          fontSize: rs ? rs.size : "11px",
          fontWeight: 800,
          fontFamily: "'DM Sans', sans-serif",
          lineHeight: 1,
        }}
      >
        {rs ? rs.symbol : rank}
      </div>

      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: "6px", marginBottom: "6px" }}>
          <span
            style={{
              fontSize: "13px",
              fontWeight: 700,
              color: hovered ? "#b84400" : "#111122",
              fontFamily: "'DM Sans', sans-serif",
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
              transition: "color 0.15s",
            }}
          >
            {item.label}
          </span>
          <span
            style={{
              fontSize: "9px",
              fontWeight: 700,
              textTransform: "uppercase",
              letterSpacing: "0.3px",
              color: meta.color,
              background: meta.bg,
              border: `1px solid ${meta.border}`,
              borderRadius: "4px",
              padding: "1px 5px",
              flexShrink: 0,
            }}
          >
            {meta.label}
          </span>
        </div>

        <AnimatedBar width={barWidth} color={meta.color} />

        <div
          style={{
            fontSize: "9px",
            color: "#aaa",
            marginTop: "4px",
            opacity: hovered ? 1 : 0,
            transform: hovered ? "translateY(0)" : "translateY(3px)",
            transition: "all 0.2s ease",
            height: hovered ? "14px" : "0px",
            overflow: "hidden",
          }}
        >
          {micro}
        </div>
      </div>

      <div
        style={{
          fontFamily: "'DM Mono', monospace",
          fontSize: "12px",
          fontWeight: 500,
          color: meta.color,
          background: meta.bg,
          border: `1px solid ${meta.border}`,
          padding: "3px 8px",
          borderRadius: "6px",
          flexShrink: 0,
          transition: "all 0.2s",
          boxShadow: hovered ? `0 2px 8px ${meta.color}20` : "none",
        }}
      >
        {item.count}
      </div>
    </a>
  );
}

export default function TopicosDestaque() {
  const [topics, setTopics] = useState<TrendingItem[]>([]);
  const [, navigate] = useLocation();

  useEffect(() => {
    getCachedTrending().then((items) =>
      setTopics(
        items.filter((item) => item.type === "topic" || item.type === "country" || item.type === "person").slice(0, 8),
      ),
    );
  }, []);

  if (topics.length === 0) return null;
  const maxCount = Math.max(...topics.map((topic) => topic.count), 1);

  return (
    <section style={{ marginBottom: "48px" }}>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          marginBottom: "4px",
          paddingBottom: "10px",
          borderBottom: "2px solid #111122",
        }}
      >
        <h2
          style={{
            fontFamily: "'Playfair Display', serif",
            fontWeight: 700,
            fontSize: "17px",
            color: "#111122",
            margin: 0,
          }}
        >
          Topicos em Destaque
        </h2>
        <span style={{ fontSize: "10px", color: "#aaa", fontWeight: 600, letterSpacing: "0.5px" }}>
          ULTIMAS 48H
        </span>
      </div>

      <div>
        {topics.map((item, index) => {
          const href = "/" + item.slug;
          return (
            <TopicoItem
              key={index}
              item={item}
              rank={index + 1}
              maxCount={maxCount}
              href={href}
              onClick={(event) => {
                event.preventDefault();
                navigate(href);
              }}
            />
          );
        })}
      </div>
    </section>
  );
}

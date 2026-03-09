import { useEffect, useRef, useState, useCallback } from "react";
import { useLocation } from "wouter";
import Header from "@/components/Header";
import { getApiBase } from "@/lib/api";

// ── Tipos ────────────────────────────────────────────────────────
interface Connection { source: string; target: string; weight: number; }
interface NarrativeConnection {
  source: string; target: string;
  type: string; label: string; summary: string; confidence: number;
}
interface SpreadNode { name: string; type: string; role: string; enteredAt: string; }
interface SpreadPath { summary: string; nodes: SpreadNode[]; }
interface Headline { title: string; source: string; slug: string; publishedAt: string; category: string; }
interface Signal {
  id: string; name: string; lat: number; lng: number;
  volume: number; sources: number; score: number; intensity: number;
  growthPct: number; impact: "low" | "medium" | "high";
  relatedTopics: string[]; headlines: Headline[];
  connections: { country: string; weight: number }[];
}

// ── Coordenadas no SVG — calibradas pelos centroides dos continentes ──
// Calibração: EUA(155,130)→(37.1,-95.7) e Brasil(237,335)→(-14.2,-51.9) e Irã(573,152)→(32.4,53.7)
// x = 2.7979 * lng + 422.755
// y = -3.9961 * lat + 278.255
function latLngToSvg(lat: number, lng: number): [number, number] {
  const x = 2.7979 * lng + 422.755;
  const y = -3.9961 * lat + 278.255;
  // Clamp dentro do viewBox
  return [Math.round(Math.max(10, Math.min(990, x))), Math.round(Math.max(10, Math.min(510, y)))];
}

// ── Cor por intensidade ──────────────────────────────────────────
function signalColor(signal: Signal): string {
  if (signal.impact === "high") return "#ef4444";
  if (signal.impact === "medium") return "#e8621a";
  return "#3b82f6";
}

// ── Raio por intensidade ─────────────────────────────────────────
function signalRadius(signal: Signal): number {
  const base = 4;
  const extra = (signal.intensity / 100) * 14;
  return base + extra;
}

// ── Componente de tempo relativo ─────────────────────────────────
function timeAgo(iso: string): string {
  const diff = (Date.now() - new Date(iso).getTime()) / 60000;
  if (diff < 1) return "agora";
  if (diff < 60) return `${Math.round(diff)} min`;
  if (diff < 1440) return `${Math.round(diff / 60)}h`;
  return `${Math.round(diff / 1440)}d`;
}

// ── Painel lateral ───────────────────────────────────────────────
function SidePanel({ signal, onNavigate, spreadPaths, narrativeConns }: {
  signal: Signal | null;
  onNavigate: (slug: string) => void;
  spreadPaths: Record<string, SpreadPath>;
  narrativeConns: NarrativeConnection[];
}) {
  if (!signal) return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center",
      justifyContent: "center", height: "100%", gap: "12px", padding: "24px" }}>
      <div style={{ fontSize: "32px", opacity: 0.2 }}>🌐</div>
      <p style={{ fontSize: "12px", color: "#334155", textAlign: "center", fontWeight: 600 }}>
        Clique em um ponto no mapa para ver os detalhes da região
      </p>
    </div>
  );

  const color = signalColor(signal);
  const catColor = (cat: string) => {
    const map: Record<string, string> = {
      Política: "#e8621a", Brasil: "#e8621a", Economia: "#22c55e",
      Mundo: "#3b82f6", Conflito: "#ef4444", Justiça: "#8b5cf6",
      Tecnologia: "#06b6d4",
    };
    return map[cat] || "#94a3b8";
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", overflow: "hidden" }}>
      {/* Header */}
      <div style={{ padding: "16px 18px 12px", borderBottom: "1px solid #111e30",
        background: "linear-gradient(180deg,#0d1525 0%,#0a1020 100%)", flexShrink: 0 }}>
        <div style={{ fontSize: "9px", color: "#334155", fontWeight: 700,
          textTransform: "uppercase", letterSpacing: "1px" }}>
          Região selecionada
        </div>
        <div style={{ fontFamily: "'Playfair Display', serif", fontSize: "22px",
          fontWeight: 700, color: "#e2e8f0", margin: "2px 0" }}>
          {signal.name}
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
          <div style={{ width: "8px", height: "8px", borderRadius: "50%",
            background: color, boxShadow: `0 0 6px ${color}` }} />
          <span style={{ fontSize: "10px", color: "#334155" }}>
            {signal.volume} notícias · {signal.sources} fontes
          </span>
        </div>
      </div>

      {/* Stats */}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px",
        padding: "12px 18px", borderBottom: "1px solid #111e30", flexShrink: 0 }}>
        {[
          { label: "Volume 12h", value: signal.volume, sub: signal.growthPct > 0
            ? `+${signal.growthPct.toFixed(0)}%` : `${signal.growthPct.toFixed(0)}%`,
            subColor: signal.growthPct > 0 ? "#4ade80" : "#f87171" },
          { label: "Impacto", value: signal.impact === "high" ? "ALTO"
            : signal.impact === "medium" ? "MÉDIO" : "BAIXO",
            sub: `${signal.sources} fontes`, subColor: "#64748b" },
        ].map((s, i) => (
          <div key={i} style={{ background: "#0d1828", border: "1px solid #1a2840",
            borderRadius: "8px", padding: "10px 12px" }}>
            <div style={{ fontSize: "8px", color: "#334155", fontWeight: 700,
              textTransform: "uppercase", letterSpacing: "0.5px", marginBottom: "4px" }}>
              {s.label}
            </div>
            <div style={{ fontFamily: "'DM Mono', monospace", fontSize: "18px",
              color: "#e2e8f0", lineHeight: 1 }}>{s.value}</div>
            <div style={{ fontSize: "10px", fontWeight: 700, marginTop: "2px",
              color: s.subColor }}>{s.sub}</div>
          </div>
        ))}
      </div>

      {/* Tópicos */}
      {signal.relatedTopics.length > 0 && (
        <div style={{ padding: "12px 18px", borderBottom: "1px solid #111e30", flexShrink: 0 }}>
          <div style={{ fontSize: "9px", color: "#334155", fontWeight: 700,
            textTransform: "uppercase", letterSpacing: "0.5px", marginBottom: "8px" }}>
            Tópicos relacionados
          </div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: "5px" }}>
            {signal.relatedTopics.map((t, i) => (
              <span key={i} style={{ padding: "3px 9px", borderRadius: "10px",
                border: "1px solid #1a2840", background: "#0d1828",
                fontSize: "10px", fontWeight: 600, color: "#64748b" }}>
                {t}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Spread Path da IA */}
      {spreadPaths[signal.name] && spreadPaths[signal.name].nodes.length > 0 && (
        <div style={{ padding: "12px 18px", borderBottom: "1px solid #111e30", flexShrink: 0 }}>
          <div style={{ fontSize: "9px", color: "#334155", fontWeight: 700,
            textTransform: "uppercase", letterSpacing: "0.5px", marginBottom: "8px",
            display: "flex", alignItems: "center", gap: "6px" }}>
            <span style={{ width: "6px", height: "6px", borderRadius: "50%",
              background: "#e8621a", display: "inline-block" }}/>
            Como se espalhou · IA
          </div>
          {/* Resumo narrativo */}
          {spreadPaths[signal.name].summary && (
            <p style={{ fontSize: "10px", color: "#475569", lineHeight: "1.5",
              marginBottom: "10px", fontStyle: "italic" }}>
              {spreadPaths[signal.name].summary}
            </p>
          )}
          {/* Nós do spread path */}
          <div style={{ display: "flex", alignItems: "center", gap: "4px", flexWrap: "wrap" }}>
            {spreadPaths[signal.name].nodes.map((node, i) => {
              const roleColors: Record<string, string> = {
                origem: "#ef4444", resposta: "#e8621a",
                impacto: "#f59e0b", "consequência": "#3b82f6"
              };
              const isOrigin = node.role === "origem";
              return (
                <div key={i} style={{ display: "flex", alignItems: "center", gap: "4px" }}>
                  <div style={{
                    padding: "3px 8px", borderRadius: "5px",
                    background: isOrigin ? "rgba(239,68,68,0.12)" : "#0d1828",
                    border: `1px solid ${roleColors[node.role] || "#1a2840"}55`,
                    fontSize: "10px", fontWeight: 700,
                    color: roleColors[node.role] || "#475569",
                    position: "relative",
                  }}>
                    {node.name}
                    <span style={{ fontSize: "7px", color: "#334155",
                      display: "block", fontWeight: 400, marginTop: "1px" }}>
                      {node.role}
                    </span>
                  </div>
                  {i < spreadPaths[signal.name].nodes.length - 1 && (
                    <span style={{ color: "#1e3050", fontSize: "10px" }}>→</span>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Conexões narrativas da IA para este país */}
      {narrativeConns.filter(nc =>
          nc.source === signal.name || nc.target === signal.name
        ).length > 0 && (
        <div style={{ padding: "12px 18px", borderBottom: "1px solid #111e30", flexShrink: 0 }}>
          <div style={{ fontSize: "9px", color: "#334155", fontWeight: 700,
            textTransform: "uppercase", letterSpacing: "0.5px", marginBottom: "8px" }}>
            Relações detectadas · IA
          </div>
          {narrativeConns
            .filter(nc => nc.source === signal.name || nc.target === signal.name)
            .slice(0, 3)
            .map((nc, i) => {
              const other = nc.source === signal.name ? nc.target : nc.source;
              const typeColors: Record<string, string> = {
                conflito: "#ef4444", sancao: "#f97316", diplomacia: "#3b82f6",
                alianca: "#22c55e", comercio: "#a855f7", crise: "#ef4444",
                politica: "#e8621a", outro: "#475569",
              };
              const color = typeColors[nc.type] || "#475569";
              return (
                <div key={i} style={{ marginBottom: "8px",
                  padding: "8px 10px", borderRadius: "6px",
                  background: "#0d1828", border: `1px solid ${color}33` }}>
                  <div style={{ display: "flex", alignItems: "center",
                    justifyContent: "space-between", marginBottom: "3px" }}>
                    <span style={{ fontSize: "11px", fontWeight: 700, color: "#94a3b8" }}>
                      {signal.name} <span style={{ color: "#334155" }}>↔</span> {other}
                    </span>
                    <span style={{ fontSize: "9px", fontWeight: 800,
                      color, background: color + "22",
                      padding: "1px 6px", borderRadius: "4px",
                      textTransform: "uppercase", letterSpacing: "0.5px" }}>
                      {nc.label}
                    </span>
                  </div>
                  {nc.summary && (
                    <p style={{ fontSize: "10px", color: "#475569",
                      lineHeight: "1.4", margin: 0 }}>
                      {nc.summary}
                    </p>
                  )}
                </div>
              );
            })}
        </div>
      )}

      {/* Notícias */}
      <div style={{ flex: 1, overflowY: "auto", padding: "12px 18px" }}>
        <div style={{ fontSize: "9px", color: "#334155", fontWeight: 700,
          textTransform: "uppercase", letterSpacing: "0.5px", marginBottom: "10px" }}>
          Últimas manchetes
        </div>
        {signal.headlines.length === 0 ? (
          <p style={{ fontSize: "11px", color: "#334155" }}>Nenhuma manchete disponível.</p>
        ) : signal.headlines.map((h, i) => (
          <div key={i}
            onClick={() => onNavigate(h.slug)}
            style={{ padding: "10px 0", borderBottom: "1px solid #0d1828",
              cursor: "pointer", transition: "padding 0.15s" }}
            onMouseEnter={e => (e.currentTarget.style.paddingLeft = "5px")}
            onMouseLeave={e => (e.currentTarget.style.paddingLeft = "0")}
          >
            <div style={{ fontSize: "8px", fontWeight: 800, textTransform: "uppercase",
              letterSpacing: "0.5px", color: catColor(h.category), marginBottom: "3px" }}>
              {h.category}
            </div>
            <div style={{ fontSize: "11px", fontWeight: 600, color: "#64748b",
              lineHeight: "1.4", transition: "color 0.15s" }}
              onMouseEnter={e => (e.currentTarget.style.color = "#94a3b8")}
              onMouseLeave={e => (e.currentTarget.style.color = "#64748b")}>
              {h.title}
            </div>
            <div style={{ fontSize: "9px", color: "#1e3050", marginTop: "2px" }}>
              {h.source} · {timeAgo(h.publishedAt)}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Página principal ─────────────────────────────────────────────
export default function MapaGlobal() {
  const [signals, setSignals] = useState<Signal[]>([]);
  const [connections, setConnections] = useState<Connection[]>([]);
  const [narrativeConns, setNarrativeConns] = useState<NarrativeConnection[]>([]);
  const [spreadPaths, setSpreadPaths] = useState<Record<string, SpreadPath>>({});
  const [selected, setSelected] = useState<Signal | null>(null);
  const [loading, setLoading] = useState(true);
  const [window_, setWindow] = useState(12);
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [, navigate] = useLocation();

  const load = useCallback((w: number) => {
    setLoading(true);
    fetch(`${getApiBase()}/map/signals?window=${w}`)
      .then(r => r.json())
      .then(data => {
        setSignals(data.signals || []);
        setConnections(data.connections || []);
        if (data.signals?.length > 0 && !selected) {
          const top = [...(data.signals as Signal[])].sort((a, b) => b.score - a.score)[0];
          setSelected(top);
        }
        setLoading(false);
        // Busca narrativas da IA em paralelo (não bloqueia o mapa)
        fetch(`${getApiBase()}/map/narrative`)
          .then(r => r.json())
          .then(nd => {
            if (nd.ready) {
              setNarrativeConns(nd.connections || []);
              setSpreadPaths(nd.spreadPaths || {});
            }
          })
          .catch(() => {});
      })
      .catch(() => setLoading(false));
  }, []);

  useEffect(() => { load(window_); }, [window_]);

  // Atualiza a cada 5 minutos
  useEffect(() => {
    const iv = setInterval(() => load(window_), 300_000);
    return () => clearInterval(iv);
  }, [window_]);

  const maxScore = Math.max(...signals.map(s => s.score), 1);

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100vh",
      background: "#070d18", overflow: "hidden" }}>

      {/* Header mínimo escuro */}
      <div style={{ background: "#0a1020", borderBottom: "1px solid #1a2840",
        padding: "10px 24px", display: "flex", alignItems: "center",
        justifyContent: "space-between", flexShrink: 0, zIndex: 50 }}>
        <div style={{ display: "flex", alignItems: "center", gap: "20px" }}>
          <a href="/" style={{ textDecoration: "none" }}>
            <span style={{ fontFamily: "'Playfair Display', serif",
              fontSize: "18px", fontWeight: 800 }}>
              <span style={{ color: "#e8621a" }}>CRIVO</span>
              <span style={{ color: "#e2e8f0" }}>NEWS.</span>
            </span>
          </a>
          <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
            <div style={{ width: "3px", height: "3px", borderRadius: "50%", background: "#334155" }} />
            <span style={{ fontFamily: "'Playfair Display', serif",
              fontSize: "14px", color: "#94a3b8", fontWeight: 700 }}>
              Mapa Global
            </span>
          </div>
        </div>

        <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "5px",
            background: "rgba(74,222,128,0.08)", border: "1px solid rgba(74,222,128,0.2)",
            borderRadius: "20px", padding: "3px 10px" }}>
            <div style={{ width: "5px", height: "5px", borderRadius: "50%",
              background: "#4ade80", animation: "livePulse 1.5s ease-in-out infinite" }} />
            <span style={{ fontSize: "9px", fontWeight: 800, color: "#4ade80", letterSpacing: "1px" }}>
              AO VIVO
            </span>
          </div>
          <a href="/" style={{ fontSize: "11px", color: "#475569", textDecoration: "none",
            padding: "4px 10px", borderRadius: "6px", border: "1px solid #1a2840",
            background: "#0d1828", transition: "all 0.15s", fontWeight: 600 }}>
            ← Home
          </a>
        </div>
      </div>

      {/* Corpo */}
      <div style={{ display: "flex", flex: 1, overflow: "hidden" }}>

        {/* MAPA */}
        <div style={{ flex: 1, position: "relative", overflow: "hidden" }}>

          {/* Timeline */}
          <div style={{ position: "absolute", bottom: "16px", left: "50%",
            transform: "translateX(-50%)", zIndex: 20,
            display: "flex", gap: "0",
            background: "rgba(10,16,32,0.9)", border: "1px solid #1a2840",
            borderRadius: "20px", padding: "3px",
            backdropFilter: "blur(8px)" }}>
            {[2, 6, 12, 24, 48].map(h => (
              <button key={h} onClick={() => { setWindow(h); load(h); }}
                style={{ padding: "5px 14px", borderRadius: "16px", border: "none",
                  background: window_ === h
                    ? "linear-gradient(135deg,#e8621a,#b84400)" : "transparent",
                  color: window_ === h ? "#fff" : "#475569",
                  fontSize: "10px", fontWeight: 700, cursor: "pointer",
                  transition: "all 0.2s", fontFamily: "'DM Sans', sans-serif",
                  boxShadow: window_ === h ? "0 2px 12px rgba(232,98,26,0.35)" : "none" }}>
                {h}h
              </button>
            ))}
          </div>

          {/* Legenda */}
          <div style={{ position: "absolute", bottom: "16px", left: "20px",
            zIndex: 20, display: "flex", flexDirection: "column", gap: "5px" }}>
            {[
              { color: "#ef4444", label: "Conflito / Crise" },
              { color: "#e8621a", label: "Político" },
              { color: "#3b82f6", label: "Geopolítica" },
            ].map((l, i) => (
              <div key={i} style={{ display: "flex", alignItems: "center", gap: "6px",
                fontSize: "9px", color: "#334155", fontWeight: 600 }}>
                <div style={{ width: "7px", height: "7px", borderRadius: "50%",
                  background: l.color, boxShadow: `0 0 5px ${l.color}` }} />
                {l.label}
              </div>
            ))}
          </div>

          {/* SVG Mapa */}
          <svg viewBox="0 0 1000 520" preserveAspectRatio="xMidYMid meet"
            style={{ width: "100%", height: "100%", position: "absolute", inset: 0 }}>
            <defs>
              {/* Glow filters */}
              {["red","orange","blue"].map(c => (
                <filter key={c} id={`g${c}`} x="-80%" y="-80%" width="260%" height="260%">
                  <feGaussianBlur stdDeviation="6" result="b"/>
                  <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
                </filter>
              ))}
              <radialGradient id="ocean" cx="50%" cy="40%">
                <stop offset="0%" stopColor="#0a1e3a"/>
                <stop offset="100%" stopColor="#050c18"/>
              </radialGradient>
              <filter id="land">
                <feDropShadow dx="0" dy="2" stdDeviation="3" floodColor="#000" floodOpacity="0.4"/>
              </filter>
            </defs>

            {/* Oceano */}
            <rect width="1000" height="520" fill="url(#ocean)"/>

            {/* Grade */}
            <g stroke="#0a1e35" strokeWidth="0.4">
              {[130,260,390].map(y => <line key={y} x1="0" y1={y} x2="1000" y2={y}/>)}
              {[200,400,600,800].map(x => <line key={x} x1={x} y1="0" x2={x} y2="520"/>)}
            </g>
            <line x1="0" y1="250" x2="1000" y2="250" stroke="#0d2540" strokeWidth="1" strokeDasharray="6 8"/>

            {/* Continentes */}
            <g fill="#0c2038" filter="url(#land)">
              <path d="M75,75 L125,55 L205,60 L235,85 L248,130 L228,172 L205,205 L182,222 L158,212 L138,192 L108,202 L88,182 L68,150 L58,118 Z" stroke="#0f2d4a" strokeWidth="1.5"/>
              <path d="M182,222 L202,232 L212,252 L202,264 L186,252 L175,237 Z" stroke="#0f2d4a" strokeWidth="1"/>
              <path d="M192,268 L225,258 L262,263 L288,288 L294,335 L272,385 L242,415 L212,404 L187,373 L177,332 L177,298 Z" stroke="#0f2d4a" strokeWidth="1.5"/>
              <path d="M442,68 L484,62 L515,72 L524,98 L504,120 L480,132 L450,126 L432,112 L426,90 Z" stroke="#0f2d4a" strokeWidth="1.5"/>
              <path d="M470,38 L492,32 L504,52 L482,68 L465,58 Z" stroke="#0f2d4a" strokeWidth="1"/>
              <path d="M448,148 L512,138 L558,154 L568,202 L558,262 L532,325 L502,355 L472,334 L450,282 L440,220 L440,178 Z" stroke="#0f2d4a" strokeWidth="1.5"/>
              <path d="M528,128 L582,122 L614,138 L618,172 L592,182 L558,176 L532,160 Z" stroke="#132840" strokeWidth="1.5"/>
              <path d="M518,48 L645,38 L765,52 L805,68 L815,98 L782,115 L722,108 L652,118 L592,114 L548,104 L522,82 Z" stroke="#0f2d4a" strokeWidth="1.5"/>
              <path d="M612,168 L652,162 L668,194 L652,242 L626,254 L605,222 L600,192 Z" stroke="#0f2d4a" strokeWidth="1"/>
              <path d="M658,98 L752,92 L792,108 L802,150 L772,178 L722,182 L682,166 L655,145 L650,118 Z" stroke="#0f2d4a" strokeWidth="1.5"/>
              <path d="M762,315 L825,298 L865,312 L870,352 L834,374 L782,362 L756,342 Z" stroke="#0f2d4a" strokeWidth="1"/>
            </g>

            {/* Linhas de conexão — coloridas pelo tipo de relação da IA */}
            {(() => {
              // Cor por tipo de relação classificado pela IA
              const typeColor: Record<string, string> = {
                conflito:   "#ef4444",
                sancao:     "#f97316",
                diplomacia: "#3b82f6",
                alianca:    "#22c55e",
                comercio:   "#a855f7",
                crise:      "#ef4444",
                politica:   "#e8621a",
                outro:      "#475569",
              };

              // Usa conexões narrativas da IA se disponíveis, senão usa coocorrência
              const activeConns = narrativeConns.length > 0
                ? narrativeConns.slice(0, 12).map(nc => ({
                    source: nc.source, target: nc.target,
                    weight: nc.confidence,
                    color: typeColor[nc.type] || "#475569",
                    label: nc.label,
                  }))
                : connections.slice(0, 10).map(c => ({
                    source: c.source, target: c.target,
                    weight: c.weight, color: "#475569", label: "",
                  }));

              return activeConns.map(conn => {
                const a = signals.find(s => s.name === conn.source);
                const b = signals.find(s => s.name === conn.target);
                if (!a || !b) return null;
                const [x1, y1] = latLngToSvg(a.lat, a.lng);
                const [x2, y2] = latLngToSvg(b.lat, b.lng);
                const mx = (x1 + x2) / 2;
                const my = Math.min(y1, y2) - 35;
                const opacity = Math.min(0.4, 0.1 + (conn.weight / 100) * 0.3);
                const strokeW = conn.weight >= 70 ? "1.2" : "0.7";
                return (
                  <g key={`${conn.source}-${conn.target}`}>
                    <path d={`M ${x1},${y1} Q ${mx},${my} ${x2},${y2}`}
                      fill="none" stroke={conn.color}
                      strokeWidth={strokeW} strokeOpacity={opacity}
                      strokeDasharray="5 5">
                      <animate attributeName="stroke-dashoffset" values="0;-40"
                        dur={`${2.5 + (conn.weight % 3)}s`} repeatCount="indefinite"/>
                    </path>
                  </g>
                );
              });
            })()}

            {/* Pontos de intensidade */}
            {signals.map(signal => {
              const [cx, cy] = latLngToSvg(signal.lat, signal.lng);
              const r = signalRadius(signal);
              const color = signalColor(signal);
              const isSelected = selected?.id === signal.id;
              const isHovered = hoveredId === signal.id;
              const pulseDur = signal.impact === "high" ? "1.8s" : signal.impact === "medium" ? "2.4s" : "3.2s";

              return (
                <g key={signal.id}
                  style={{ cursor: "pointer" }}
                  onClick={() => setSelected(signal)}
                  onMouseEnter={() => setHoveredId(signal.id)}
                  onMouseLeave={() => setHoveredId(null)}>

                  {/* Halo externo de intensidade */}
                  <circle cx={cx} cy={cy} r={r * 2.8} fill={color} fillOpacity="0.04"/>
                  <circle cx={cx} cy={cy} r={r * 2} fill={color} fillOpacity="0.07"/>

                  {/* Pulso animado */}
                  <circle cx={cx} cy={cy} r={r} fill="none" stroke={color} strokeWidth="1.2" strokeOpacity="0">
                    <animate attributeName="r" values={`${r};${r * 2.8};${r}`} dur={pulseDur} repeatCount="indefinite"/>
                    <animate attributeName="stroke-opacity" values="0.6;0;0.6" dur={pulseDur} repeatCount="indefinite"/>
                  </circle>
                  <circle cx={cx} cy={cy} r={r * 0.7} fill="none" stroke={color} strokeWidth="0.8" strokeOpacity="0">
                    <animate attributeName="r" values={`${r * 0.5};${r * 2};${r * 0.5}`} dur={pulseDur} repeatCount="indefinite" begin="0.5s"/>
                    <animate attributeName="stroke-opacity" values="0.4;0;0.4" dur={pulseDur} repeatCount="indefinite" begin="0.5s"/>
                  </circle>

                  {/* Core com glow */}
                  <circle cx={cx} cy={cy} r={r} fill={color} fillOpacity="0.22"/>
                  <circle cx={cx} cy={cy} r={r * 0.65} fill={color} fillOpacity="0.55"/>
                  <circle cx={cx} cy={cy} r={r * 0.4} fill={color} fillOpacity="0.9"/>
                  <circle cx={cx} cy={cy} r={r * 0.2} fill="#fff" fillOpacity="0.9"/>

                  {/* Anel de seleção */}
                  {(isSelected || isHovered) && (
                    <circle cx={cx} cy={cy} r={r * 3.2} fill="none"
                      stroke={color} strokeWidth="1.5"
                      strokeOpacity={isSelected ? 0.5 : 0.25}
                      strokeDasharray="4 4"/>
                  )}

                  {/* Label */}
                  <text x={cx} y={cy + r * 2.2} textAnchor="middle"
                    fontSize="7.5" fill={color} fillOpacity="0.7"
                    fontFamily="DM Sans" fontWeight="800" letterSpacing="0.5">
                    {signal.name.toUpperCase()}
                  </text>
                </g>
              );
            })}
          </svg>

          {/* Ranking overlay */}
          <div style={{ position: "absolute", top: "16px", right: "16px",
            zIndex: 20, width: "175px" }}>
            <div style={{ fontSize: "9px", color: "#334155", fontWeight: 700,
              letterSpacing: "1px", textAlign: "right", marginBottom: "6px" }}>
              TOP REGIÕES
            </div>
            {[...signals].sort((a, b) => b.score - a.score).slice(0, 6).map((s, i) => {
              const color = signalColor(s);
              const bars = [0.2, 0.4, 0.5, 0.65, 0.8, 0.9, 1.0, 1.0, 0.95].slice(0, 8);
              const maxH = 14;
              return (
                <div key={s.id}
                  onClick={() => setSelected(s)}
                  style={{ display: "flex", alignItems: "center", gap: "7px",
                    background: selected?.id === s.id
                      ? `rgba(${s.impact === "high" ? "239,68,68" : "232,98,26"},0.06)` : "rgba(10,16,32,0.88)",
                    border: `1px solid ${selected?.id === s.id ? color + "55" : "#1a2840"}`,
                    borderRadius: "6px", padding: "6px 9px", marginBottom: "4px",
                    cursor: "pointer", transition: "all 0.2s",
                    backdropFilter: "blur(8px)" }}>
                  <span style={{ fontFamily: "'DM Mono',monospace", fontSize: "9px",
                    color: "#1e3050", width: "12px" }}>{i + 1}</span>
                  <span style={{ fontSize: "11px", fontWeight: 700, color, flex: 1,
                    whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                    {s.name}
                  </span>
                  {/* Sparkline */}
                  <div style={{ display: "flex", alignItems: "flex-end", gap: "1.5px", height: `${maxH}px` }}>
                    {bars.map((h, j) => (
                      <div key={j} style={{ width: "2.5px", borderRadius: "1px",
                        height: `${Math.round(h * maxH)}px`,
                        background: selected?.id === s.id ? color : color + "55" }} />
                    ))}
                  </div>
                  <span style={{ fontFamily: "'DM Mono',monospace", fontSize: "9px",
                    color: "#334155", minWidth: "24px", textAlign: "right" }}>
                    {s.volume}
                  </span>
                </div>
              );
            })}
            {loading && (
              <div style={{ fontSize: "10px", color: "#334155", textAlign: "center",
                padding: "8px", animation: "pulse 1.5s ease-in-out infinite" }}>
                carregando...
              </div>
            )}
          </div>

          {/* Header do mapa */}
          <div style={{ position: "absolute", top: "16px", left: "20px", zIndex: 20 }}>
            <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
              <span style={{ fontFamily: "'Playfair Display', serif",
                fontSize: "17px", fontWeight: 700, color: "#e2e8f0" }}>
                Mapa Global de Notícias
              </span>
            </div>
            <div style={{ fontSize: "10px", color: "#334155", marginTop: "3px" }}>
              {signals.length > 0
                ? `${signals.length} regiões ativas · ${window_}h`
                : "Carregando sinais..."}
            </div>
          </div>
        </div>

        {/* PAINEL LATERAL */}
        <div style={{ width: "300px", background: "#0a1020",
          borderLeft: "1px solid #1a2840", flexShrink: 0,
          display: "flex", flexDirection: "column", overflow: "hidden" }}>
          <SidePanel signal={selected} onNavigate={slug => navigate("/noticia/" + slug)}
            spreadPaths={spreadPaths} narrativeConns={narrativeConns} />
        </div>

      </div>

      <style>{`
        @keyframes livePulse {
          0%,100% { opacity:1; box-shadow:0 0 0 0 rgba(74,222,128,0.6); }
          50%      { opacity:0.5; box-shadow:0 0 0 6px rgba(74,222,128,0); }
        }
        @keyframes pulse {
          0%,100% { opacity:1; } 50% { opacity:0.4; }
        }
      `}</style>
    </div>
  );
}

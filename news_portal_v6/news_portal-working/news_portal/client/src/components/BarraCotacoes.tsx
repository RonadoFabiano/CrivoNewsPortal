import { useEffect, useRef, useState } from "react";

interface Cotacao {
  nome: string;
  valor: string;
  variacao: number;
  seta: "up" | "down" | "flat";
}

async function fetchCotacoes(): Promise<Cotacao[]> {
  const results: Cotacao[] = [];

  // AwesomeAPI — câmbio completo (funciona sem token)
  try {
    const pairs = "USD-BRL,EUR-BRL,BTC-BRL,ETH-BRL,GBP-BRL,ARS-BRL,JPY-BRL";
    const res = await fetch(
      `https://economia.awesomeapi.com.br/json/last/${pairs}`,
      { signal: AbortSignal.timeout(6000) }
    );
    const data = await res.json();

    const map: Record<string, string> = {
      USDBRL: "USD/BRL", EURBRL: "EUR/BRL", BTCBRL: "BITCOIN",
      ETHBRL: "ETHEREUM", GBPBRL: "GBP/BRL", ARSBRL: "ARS/BRL", JPYBRL: "JPY/BRL",
    };

    Object.entries(map).forEach(([key, nome]) => {
      if (!data[key]) return;
      const pct = parseFloat(data[key].pctChange);
      const bid = parseFloat(data[key].bid);
      let valor = "";
      if (key === "BTCBRL" || key === "ETHBRL") {
        valor = `R$ ${bid.toLocaleString("pt-BR", { maximumFractionDigits: 0 })}`;
      } else if (key === "JPYBRL") {
        valor = `R$ ${bid.toFixed(4)}`;
      } else {
        valor = `R$ ${bid.toFixed(2)}`;
      }
      results.push({ nome, valor, variacao: pct, seta: pct > 0 ? "up" : pct < 0 ? "down" : "flat" });
    });
  } catch (_) {}

  // Fallback estático completo se API falhar
  if (results.length < 3) {
    return [
      { nome: "USD/BRL",   valor: "R$ 5,82",       variacao:  0.63, seta: "up"   },
      { nome: "EUR/BRL",   valor: "R$ 6,11",        variacao:  0.43, seta: "up"   },
      { nome: "GBP/BRL",   valor: "R$ 7,44",        variacao: -0.21, seta: "down" },
      { nome: "BITCOIN",   valor: "R$ 449.200",     variacao: -1.23, seta: "down" },
      { nome: "ETHEREUM",  valor: "R$ 12.840",      variacao:  0.87, seta: "up"   },
      { nome: "IBOVESPA",  valor: "131.248",         variacao:  0.84, seta: "up"   },
      { nome: "PETR4",     valor: "R$ 37,42",       variacao:  1.23, seta: "up"   },
      { nome: "VALE3",     valor: "R$ 58,90",       variacao: -0.45, seta: "down" },
      { nome: "OURO",      valor: "R$ 15.240",      variacao:  0.55, seta: "up"   },
      { nome: "PETRÓLEO",  valor: "US$ 70,14",      variacao: -0.88, seta: "down" },
    ];
  }

  return results;
}

export default function BarraCotacoes() {
  const [cotacoes, setCotacoes] = useState<Cotacao[]>([]);
  const [hora, setHora] = useState("");
  const [pos, setPos] = useState(0);
  const [paused, setPaused] = useState(false);
  const trackRef = useRef<HTMLDivElement>(null);
  const rafRef = useRef<number>();

  useEffect(() => {
    const load = () => {
      fetchCotacoes().then(data => {
        setCotacoes(data);
        setPos(0); // reset posição ao atualizar dados
      });
      setHora(new Date().toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" }));
    };
    load();
    const iv = setInterval(load, 60_000);
    return () => clearInterval(iv);
  }, []);

  useEffect(() => {
    if (cotacoes.length === 0 || paused) return;
    if (rafRef.current) cancelAnimationFrame(rafRef.current);

    const animate = () => {
      const track = trackRef.current;
      if (!track) { rafRef.current = requestAnimationFrame(animate); return; }
      // Usa 1/4 do scrollWidth pois duplicamos 4x
      const quarter = track.scrollWidth / 4;
      setPos(p => quarter <= 0 ? 0 : p >= quarter ? 0 : p + 0.5);
      rafRef.current = requestAnimationFrame(animate);
    };
    rafRef.current = requestAnimationFrame(animate);
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); };
  }, [cotacoes, paused]);

  if (cotacoes.length === 0) return null;

  // 4 cópias para loop contínuo sem buracos
  const items = [...cotacoes, ...cotacoes, ...cotacoes, ...cotacoes];

  const col  = (c: Cotacao) => c.seta === "up" ? "#4ade80" : c.seta === "down" ? "#f87171" : "#94a3b8";
  const bg   = (c: Cotacao) => c.seta === "up" ? "rgba(74,222,128,0.12)" : c.seta === "down" ? "rgba(248,113,113,0.12)" : "rgba(148,163,184,0.1)";

  return (
    <div style={{
      background: "#0f1923",
      borderBottom: "1px solid #1e2d3d",
      height: "34px",
      display: "flex",
      alignItems: "center",
      overflow: "hidden",
    }}>
      {/* Badge */}
      <div style={{
        background: "#1a2e42", color: "#64b5f6",
        fontWeight: 800, fontSize: "10px", letterSpacing: "1px",
        padding: "0 12px", height: "100%",
        display: "flex", alignItems: "center", gap: "6px",
        flexShrink: 0, borderRight: "1px solid #1e2d3d",
        fontFamily: "'DM Sans', sans-serif",
      }}>
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none"
          stroke="#64b5f6" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="22 7 13.5 15.5 8.5 10.5 2 17"/>
          <polyline points="16 7 22 7 22 13"/>
        </svg>
        MERCADO
      </div>

      <div style={{ width: "1px", height: "100%", background: "#1e2d3d", flexShrink: 0 }} />

      {/* Ticker */}
      <div
        style={{ flex: 1, overflow: "hidden" }}
        onMouseEnter={() => setPaused(true)}
        onMouseLeave={() => setPaused(false)}
      >
        <div
          ref={trackRef}
          style={{
            display: "inline-flex",
            alignItems: "center",
            transform: `translateX(-${pos}px)`,
            willChange: "transform",
            whiteSpace: "nowrap",
          }}
        >
          {items.map((c, i) => (
            <div key={i} style={{
              display: "inline-flex", alignItems: "center", gap: "6px",
              padding: "0 18px", borderRight: "1px solid #1e2d3d", flexShrink: 0,
            }}>
              <span style={{ fontSize: "10.5px", fontWeight: 800, color: "#94a3b8",
                letterSpacing: "0.4px", fontFamily: "'DM Sans', sans-serif" }}>
                {c.nome}
              </span>
              <span style={{ fontSize: "11.5px", fontWeight: 700, color: "#e2e8f0",
                fontVariantNumeric: "tabular-nums", fontFamily: "'DM Sans', sans-serif" }}>
                {c.valor}
              </span>
              <span style={{ fontSize: "9px", color: col(c) }}>
                {c.seta === "up" ? "▲" : c.seta === "down" ? "▼" : "—"}
              </span>
              <span style={{ fontSize: "10px", fontWeight: 700, color: col(c),
                background: bg(c), padding: "1px 4px", borderRadius: "3px",
                fontVariantNumeric: "tabular-nums", fontFamily: "'DM Sans', sans-serif" }}>
                {c.variacao > 0 ? "+" : ""}{c.variacao.toFixed(2)}%
              </span>
            </div>
          ))}
        </div>
      </div>

      {/* Hora */}
      {hora && (
        <div style={{
          padding: "0 12px", fontSize: "9px", color: "#475569", flexShrink: 0,
          borderLeft: "1px solid #1e2d3d", whiteSpace: "nowrap",
          display: "flex", alignItems: "center", gap: "5px", height: "100%",
          fontFamily: "'DM Sans', sans-serif",
        }}>
          <span style={{ width: "5px", height: "5px", borderRadius: "50%",
            background: "#4ade80", display: "inline-block",
            animation: "livePulse 2s ease-in-out infinite" }} />
          {hora} BRT
        </div>
      )}

      <style>{`@keyframes livePulse { 0%,100%{opacity:1} 50%{opacity:0.3} }`}</style>
    </div>
  );
}

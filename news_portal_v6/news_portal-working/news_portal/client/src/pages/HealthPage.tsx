import { useEffect, useState } from "react";
import { useLocation } from "wouter";
import { fetchHealth, fetchSourceStats, fetchTokenMetrics, type SourceCategoryStat, type SourceStat, type SourceStatsResponse, type TokenMetricKey, type TokenMetricsResponse } from "@/lib/api";

type Status = "OK" | "DEGRADED" | "loading";

type DashboardData = {
  health: Record<string, any> | null;
  tokenMetrics: TokenMetricsResponse | null;
  sourceStats: SourceStatsResponse | null;
};

const panelStyle = {
  background: "#fff",
  border: "1px solid #e0e0e8",
  borderRadius: "12px",
} as const;

function Badge({ status }: { status: string }) {
  const normalized = String(status).toLowerCase();
  const color = normalized === "ok" || normalized === "true" || normalized === "disponivel"
    ? "#22c55e"
    : normalized === "degraded" || normalized === "false" || normalized === "penalizada"
    ? "#ef4444"
    : "#f59e0b";

  return (
    <span style={{ background: color + "20", color, border: `1px solid ${color}40`, borderRadius: "20px", padding: "2px 12px", fontSize: "12px", fontWeight: 700 }}>
      {status}
    </span>
  );
}

function StatCard({ label, value, sub }: { label: string; value: string | number; sub?: string }) {
  return (
    <div style={{ ...panelStyle, padding: "16px 20px", flex: "1 1 140px" }}>
      <p style={{ margin: 0, fontSize: "11px", fontWeight: 700, letterSpacing: "1.5px", textTransform: "uppercase", color: "#999" }}>{label}</p>
      <p style={{ margin: "6px 0 0", fontSize: "28px", fontWeight: 800, color: "#111122", lineHeight: 1 }}>{value}</p>
      {sub && <p style={{ margin: "4px 0 0", fontSize: "11px", color: "#999" }}>{sub}</p>}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: "32px" }}>
      <h2 style={{ fontFamily: "'DM Sans', sans-serif", fontWeight: 700, fontSize: "13px", letterSpacing: "2px", textTransform: "uppercase", color: "#b84400", marginBottom: "16px" }}>
        {title}
      </h2>
      {children}
    </div>
  );
}

function formatCompact(value: number | undefined) {
  if (value == null) return "-";
  return new Intl.NumberFormat("pt-BR").format(value);
}

function pct(value: number, total: number) {
  if (!total) return 0;
  return Math.max(0, Math.min(100, Math.round((value * 100) / total)));
}

function Bar({ value, color = "linear-gradient(90deg, #b84400, #e07020)" }: { value: number; color?: string }) {
  return (
    <div style={{ marginTop: "10px", background: "#ececf2", borderRadius: "999px", height: "8px", overflow: "hidden" }}>
      <div style={{ width: `${Math.max(2, Math.min(100, value))}%`, height: "100%", background: color, borderRadius: "999px" }} />
    </div>
  );
}

function KeyCard({ item, maxTotal }: { item: TokenMetricKey; maxTotal: number }) {
  const totalTokens = (item.metrics?.totalTokensIn || 0) + (item.metrics?.totalTokensOut || 0);
  const series = item.metrics?.cycleHistory || [];
  const localMax = Math.max(1, ...series.map(c => (c.tokensIn || 0) + (c.tokensOut || 0)));

  return (
    <div style={{ ...panelStyle, padding: "18px 20px" }}>
      <div style={{ display: "flex", justifyContent: "space-between", gap: "12px", alignItems: "flex-start", flexWrap: "wrap" }}>
        <div>
          <p style={{ margin: 0, fontWeight: 800, fontSize: "16px", color: "#111122" }}>{item.name}</p>
          <p style={{ margin: "4px 0 0", fontSize: "12px", color: "#7b7b8c" }}>
            {formatCompact(totalTokens)} tokens totais | {formatCompact(item.metrics?.totalRequests || 0)} requests | {formatCompact(item.metrics?.total429s || 0)} throttles
          </p>
        </div>
        <Badge status={item.status} />
      </div>

      <Bar value={pct(totalTokens, maxTotal)} />

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "10px", marginTop: "14px" }}>
        <div>
          <p style={{ margin: 0, fontSize: "11px", color: "#999", textTransform: "uppercase", letterSpacing: "1px" }}>Uso atual</p>
          <p style={{ margin: "4px 0 0", fontSize: "18px", fontWeight: 800, color: "#111122" }}>{item.tokPct}%</p>
          <p style={{ margin: "2px 0 0", fontSize: "12px", color: "#7b7b8c" }}>{formatCompact(item.tokUsed)} / {formatCompact(item.tokLimit)} tok/min</p>
        </div>
        <div>
          <p style={{ margin: 0, fontSize: "11px", color: "#999", textTransform: "uppercase", letterSpacing: "1px" }}>Requests</p>
          <p style={{ margin: "4px 0 0", fontSize: "18px", fontWeight: 800, color: "#111122" }}>{item.reqPct}%</p>
          <p style={{ margin: "2px 0 0", fontSize: "12px", color: "#7b7b8c" }}>{formatCompact(item.reqUsed)} / {formatCompact(item.reqLimit)} req/min</p>
        </div>
      </div>

      {series.length > 0 && (
        <div style={{ marginTop: "14px" }}>
          <p style={{ margin: "0 0 6px", fontSize: "11px", color: "#999", textTransform: "uppercase", letterSpacing: "1px" }}>Ultimos ciclos</p>
          <div style={{ display: "flex", alignItems: "end", gap: "6px", height: "44px" }}>
            {series.map((cycle, idx) => {
              const total = (cycle.tokensIn || 0) + (cycle.tokensOut || 0);
              const height = Math.max(6, Math.round((total / localMax) * 44));
              return <div key={`${item.name}-${idx}`} title={`${total} tokens`} style={{ flex: 1, height, borderRadius: "6px 6px 0 0", background: cycle.throttles > 0 ? "#ef4444" : "#b84400" }} />;
            })}
          </div>
        </div>
      )}
    </div>
  );
}

function CategoryPills({ categories }: { categories: SourceCategoryStat[] }) {
  return (
    <div style={{ display: "flex", gap: "8px", flexWrap: "wrap", marginTop: "10px" }}>
      {categories.slice(0, 5).map((category) => (
        <span key={`${category.category}-${category.count}`} style={{ background: "#f6eee9", color: "#8d3a12", border: "1px solid #e9c7b4", borderRadius: "999px", padding: "4px 10px", fontSize: "12px", fontWeight: 600 }}>
          {category.category} ({formatCompact(category.count)})
        </span>
      ))}
    </div>
  );
}

function SourceCard({ source, maxTotal }: { source: SourceStat; maxTotal: number }) {
  return (
    <div style={{ ...panelStyle, padding: "18px 20px" }}>
      <div style={{ display: "flex", justifyContent: "space-between", gap: "12px", flexWrap: "wrap" }}>
        <div>
          <p style={{ margin: 0, fontWeight: 800, fontSize: "16px", color: "#111122" }}>{source.source}</p>
          <p style={{ margin: "4px 0 0", fontSize: "12px", color: "#7b7b8c" }}>Categoria dominante: {source.dominantCategory}</p>
        </div>
        <div style={{ textAlign: "right" }}>
          <p style={{ margin: 0, fontWeight: 800, fontSize: "24px", color: "#111122" }}>{formatCompact(source.total)}</p>
          <p style={{ margin: 0, fontSize: "11px", color: "#999", textTransform: "uppercase", letterSpacing: "1px" }}>noticias</p>
        </div>
      </div>
      <Bar value={pct(source.total, maxTotal)} color="linear-gradient(90deg, #111122, #b84400)" />
      <CategoryPills categories={source.categories} />
    </div>
  );
}

export default function HealthPage() {
  const [, navigate] = useLocation();
  const [data, setData] = useState<DashboardData>({ health: null, tokenMetrics: null, sourceStats: null });
  const [status, setStatus] = useState<Status>("loading");
  const [lastRefresh, setLastRefresh] = useState(new Date());

  const load = () => {
    setStatus("loading");
    Promise.all([fetchHealth(), fetchTokenMetrics(), fetchSourceStats()]).then(([health, tokenMetrics, sourceStats]) => {
      setData({ health, tokenMetrics, sourceStats });
      setStatus((health?.status as Status) || "DEGRADED");
      setLastRefresh(new Date());
    }).catch(() => {
      setStatus("DEGRADED");
      setLastRefresh(new Date());
    });
  };

  useEffect(() => {
    load();
    const t = setInterval(load, 15000);
    return () => clearInterval(t);
  }, []);

  const health = data.health;
  const db = (health?.database as any) || {};
  const entities = (health?.entities as any) || {};
  const caches = (health?.cache as Record<string, any>) || {};
  const ollama = (health?.ollama as any) || {};
  const hints = (health?.hints as Record<string, string>) || {};
  const tokenMetrics = data.tokenMetrics;
  const keyRanking = [...(tokenMetrics?.keys || [])].sort((a, b) => {
    const totalA = (a.metrics?.totalTokensIn || 0) + (a.metrics?.totalTokensOut || 0);
    const totalB = (b.metrics?.totalTokensIn || 0) + (b.metrics?.totalTokensOut || 0);
    return totalB - totalA || (b.metrics?.total429s || 0) - (a.metrics?.total429s || 0);
  });
  const maxKeyTotal = Math.max(1, ...keyRanking.map(item => (item.metrics?.totalTokensIn || 0) + (item.metrics?.totalTokensOut || 0)));

  const sourceStats = data.sourceStats;
  const topSources = sourceStats?.sources || [];
  const maxSourceTotal = Math.max(1, ...topSources.map(item => item.total), 1);

  return (
    <div style={{ minHeight: "100vh", background: "#f4f4f8", fontFamily: "'DM Sans', sans-serif" }}>
      <div style={{ background: "#111122", padding: "16px 32px", display: "flex", alignItems: "center", justifyContent: "space-between", gap: "16px", flexWrap: "wrap" }}>
        <div style={{ display: "flex", alignItems: "center", gap: "16px" }}>
          <span style={{ cursor: "pointer", color: "#b84400", fontWeight: 700, fontSize: "18px", fontFamily: "'Playfair Display', serif" }} onClick={() => navigate("/")}>
            CRIVO
          </span>
          <span style={{ color: "#555", fontSize: "13px" }}>/ sistema</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
          <span style={{ fontSize: "12px", color: "#666" }}>
            Atualizado: {lastRefresh.toLocaleTimeString("pt-BR")}
          </span>
          <button onClick={load} style={{ background: "#b84400", color: "#fff", border: "none", borderRadius: "8px", padding: "6px 16px", cursor: "pointer", fontSize: "13px", fontWeight: 600 }}>
            Refresh
          </button>
        </div>
      </div>

      <div style={{ maxWidth: "1120px", margin: "0 auto", padding: "40px 24px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: "16px", marginBottom: "40px", flexWrap: "wrap" }}>
          <div style={{ width: "12px", height: "12px", borderRadius: "50%", background: status === "OK" ? "#22c55e" : status === "DEGRADED" ? "#ef4444" : "#f59e0b", boxShadow: `0 0 0 4px ${(status === "OK" ? "#22c55e" : status === "DEGRADED" ? "#ef4444" : "#f59e0b")}20` }} />
          <h1 style={{ fontFamily: "'Playfair Display', serif", fontWeight: 700, fontSize: "28px", color: "#111122", margin: 0 }}>
            {status === "loading" ? "Verificando sistema..." : status === "OK" ? "Sistema operacional" : "Sistema degradado"}
          </h1>
          {status !== "loading" && <Badge status={status} />}
        </div>

        {Object.entries(hints).length > 0 && (
          <div style={{ background: "#fff8ec", border: "1px solid #b8440040", borderRadius: "12px", padding: "16px 20px", marginBottom: "32px" }}>
            <p style={{ fontWeight: 700, fontSize: "13px", color: "#b84400", marginBottom: "8px" }}>Atencao</p>
            {Object.entries(hints).map(([k, v]) => (
              <p key={k} style={{ margin: "4px 0", fontSize: "14px", color: "#555" }}>- {v}</p>
            ))}
          </div>
        )}

        <Section title="Banco de dados">
          <div style={{ display: "flex", gap: "12px", flexWrap: "wrap" }}>
            <StatCard label="Total Raw" value={db.raw_total ?? "-"} />
            <StatCard label="Processados" value={db.processed ?? "-"} />
            <StatCard label="Fila IA" value={db.pending ?? "-"} sub="aguardando Ollama" />
            <StatCard label="Falhas IA" value={db.failed ?? "-"} />
          </div>
          {db.ok === false && <p style={{ color: "#ef4444", fontSize: "13px", marginTop: "8px" }}>Erro: {db.error}</p>}
        </Section>

        <Section title="Consumo de API Keys">
          <div style={{ display: "flex", gap: "12px", flexWrap: "wrap", marginBottom: "16px" }}>
            <StatCard label="Keys ativas" value={tokenMetrics?.poolSize ?? "-"} />
            <StatCard label="Tokens agora" value={formatCompact(tokenMetrics?.totalTokNow)} sub={`limite efetivo ${formatCompact(tokenMetrics?.effectiveTpmLimit)}`} />
            <StatCard label="Req agora" value={formatCompact(tokenMetrics?.totalReqNow)} />
            <StatCard label="Tokens totais" value={formatCompact(tokenMetrics?.totalTokensAllTime)} />
          </div>
          {keyRanking.length > 0 ? (
            <div style={{ display: "grid", gap: "12px" }}>
              {keyRanking.map(item => <KeyCard key={item.name} item={item} maxTotal={maxKeyTotal} />)}
            </div>
          ) : (
            <div style={{ ...panelStyle, padding: "18px 20px", color: "#7b7b8c" }}>Sem metricas de consumo disponiveis.</div>
          )}
        </Section>

        <Section title="Extracao de entidades">
          <div style={{ display: "flex", gap: "12px", flexWrap: "wrap" }}>
            <StatCard label="Processadas" value={entities.done ?? "-"} sub={entities.progress_pct} />
            <StatCard label="Pendentes" value={entities.pending ?? "-"} sub="aguardando EntityWorker" />
            <StatCard label="Falhas" value={entities.failed ?? "-"} />
          </div>
          {entities.total > 0 && (
            <div style={{ marginTop: "16px", background: "#e8e8ee", borderRadius: "8px", height: "8px", overflow: "hidden" }}>
              <div style={{ height: "100%", background: "linear-gradient(90deg, #b84400, #e07020)", borderRadius: "8px", width: entities.progress_pct || "0%", transition: "width 0.5s" }} />
            </div>
          )}
        </Section>

        <Section title="Portais com mais noticias">
          <div style={{ display: "flex", gap: "12px", flexWrap: "wrap", marginBottom: "16px" }}>
            <StatCard label="Portais" value={sourceStats?.totalSources ?? "-"} />
            <StatCard label="Noticias mapeadas" value={formatCompact(sourceStats?.totalArticles)} />
            <StatCard label="Categoria lider" value={sourceStats?.categoryTotals?.[0]?.category || "-"} sub={sourceStats?.categoryTotals?.[0] ? `${formatCompact(sourceStats.categoryTotals[0].count)} noticias` : undefined} />
          </div>
          {topSources.length > 0 ? (
            <div style={{ display: "grid", gap: "12px" }}>
              {topSources.map(item => <SourceCard key={item.source} source={item} maxTotal={maxSourceTotal} />)}
            </div>
          ) : (
            <div style={{ ...panelStyle, padding: "18px 20px", color: "#7b7b8c" }}>Sem ranking de portais disponivel.</div>
          )}
        </Section>

        <Section title="Categorias por portal">
          {topSources.length > 0 ? (
            <div style={{ ...panelStyle, overflow: "hidden" }}>
              <div style={{ display: "grid", gridTemplateColumns: "minmax(180px, 1.4fr) minmax(80px, 0.4fr) minmax(180px, 1fr) minmax(260px, 2fr)" }}>
                {[
                  "Portal",
                  "Total",
                  "Dominante",
                  "Distribuicao"
                ].map((label) => (
                  <div key={label} style={{ padding: "14px 16px", background: "#f7f7fb", borderBottom: "1px solid #ececf2", fontSize: "11px", fontWeight: 700, letterSpacing: "1px", textTransform: "uppercase", color: "#7b7b8c" }}>
                    {label}
                  </div>
                ))}

                {topSources.map((item) => (
                  <div key={item.source} style={{ display: "contents" }}>
                    <div style={{ padding: "16px", borderBottom: "1px solid #ececf2", fontWeight: 700, color: "#111122" }}>{item.source}</div>
                    <div style={{ padding: "16px", borderBottom: "1px solid #ececf2", color: "#111122" }}>{formatCompact(item.total)}</div>
                    <div style={{ padding: "16px", borderBottom: "1px solid #ececf2", color: "#7b7b8c" }}>{item.dominantCategory}</div>
                    <div style={{ padding: "12px 16px", borderBottom: "1px solid #ececf2" }}>
                      <CategoryPills categories={item.categories} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : (
            <div style={{ ...panelStyle, padding: "18px 20px", color: "#7b7b8c" }}>Sem distribuicao de categorias para mostrar.</div>
          )}
        </Section>

        <Section title="Cache">
          <div style={{ display: "grid", gap: "12px" }}>
            {Object.entries(caches).map(([name, stats]: [string, any]) => (
              <div key={name} style={{ ...panelStyle, padding: "16px 20px", display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: "12px" }}>
                <div>
                  <p style={{ margin: 0, fontWeight: 700, fontSize: "14px", color: "#111122" }}>{name}</p>
                  <p style={{ margin: "2px 0 0", fontSize: "12px", color: "#999" }}>{stats.size} entradas em memoria</p>
                </div>
                <div style={{ display: "flex", gap: "20px" }}>
                  <div style={{ textAlign: "center" }}>
                    <p style={{ margin: 0, fontSize: "20px", fontWeight: 800, color: "#22c55e" }}>{stats.hit_rate}</p>
                    <p style={{ margin: 0, fontSize: "10px", color: "#999", textTransform: "uppercase", letterSpacing: "1px" }}>Hit Rate</p>
                  </div>
                  <div style={{ textAlign: "center" }}>
                    <p style={{ margin: 0, fontSize: "20px", fontWeight: 800, color: "#111122" }}>{stats.hits}</p>
                    <p style={{ margin: 0, fontSize: "10px", color: "#999", textTransform: "uppercase", letterSpacing: "1px" }}>Hits</p>
                  </div>
                  <div style={{ textAlign: "center" }}>
                    <p style={{ margin: 0, fontSize: "20px", fontWeight: 800, color: "#111122" }}>{stats.misses}</p>
                    <p style={{ margin: 0, fontSize: "10px", color: "#999", textTransform: "uppercase", letterSpacing: "1px" }}>Misses</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </Section>

        <Section title="Ollama">
          <div style={{ ...panelStyle, padding: "20px", display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: "12px" }}>
            <div>
              <p style={{ margin: 0, fontWeight: 700, fontSize: "15px", color: "#111122" }}>Ollama Local</p>
              <p style={{ margin: "4px 0 0", fontSize: "13px", color: "#999" }}>{ollama.url || "-"}</p>
            </div>
            <Badge status={String(ollama.reachable)} />
          </div>
          {ollama.error && <p style={{ color: "#ef4444", fontSize: "13px", marginTop: "8px" }}>{ollama.error}</p>}
        </Section>

        <Section title="Workers agendados">
          <div style={{ display: "flex", gap: "12px", flexWrap: "wrap" }}>
            {Object.entries((health?.workers || {}) as Record<string, string>).map(([name, sched]) => (
              <div key={name} style={{ ...panelStyle, padding: "14px 18px", flex: "1 1 200px" }}>
                <p style={{ margin: 0, fontWeight: 700, fontSize: "14px", color: "#111122" }}>{name.replace("_", " ")}</p>
                <p style={{ margin: "4px 0 0", fontSize: "12px", color: "#22c55e", fontWeight: 600 }}>o {sched}</p>
              </div>
            ))}
          </div>
        </Section>

        <Section title="Acoes">
          <div style={{ display: "flex", gap: "10px", flexWrap: "wrap" }}>
            {[
              { label: "Forcar ingestao", url: "/api/db/ingest" },
              { label: "Limpar caches", url: "/api/db/cache-clear" },
              { label: "Reset PENDING", url: "/api/db/reset-pending" },
              { label: "Ver sitemap", url: "/sitemap.xml" },
            ].map(({ label, url }) => (
              <a key={url} href={url} target="_blank" rel="noreferrer"
                style={{ background: "#111122", color: "#fff", borderRadius: "8px", padding: "8px 18px", fontSize: "13px", fontWeight: 600, textDecoration: "none", transition: "background 0.2s" }}
                onMouseEnter={e => (e.currentTarget as HTMLElement).style.background = "#b84400"}
                onMouseLeave={e => (e.currentTarget as HTMLElement).style.background = "#111122"}
              >
                {label}
              </a>
            ))}
          </div>
        </Section>
      </div>
    </div>
  );
}



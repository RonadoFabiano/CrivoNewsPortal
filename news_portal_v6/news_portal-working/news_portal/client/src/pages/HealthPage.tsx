import { useEffect, useState } from "react";
import { useLocation } from "wouter";
import { fetchHealth } from "@/lib/api";

type Status = "OK" | "DEGRADED" | "loading";

function Badge({ status }: { status: string }) {
  const color = status === "OK" || status === "true" ? "#22c55e"
    : status === "DEGRADED" || status === "false" ? "#ef4444"
    : "#f59e0b";
  return (
    <span style={{ background: color + "20", color, border: `1px solid ${color}40`, borderRadius: "20px", padding: "2px 12px", fontSize: "12px", fontWeight: 700 }}>
      {status}
    </span>
  );
}

function StatCard({ label, value, sub }: { label: string; value: string | number; sub?: string }) {
  return (
    <div style={{ background: "#fff", border: "1px solid #e0e0e8", borderRadius: "12px", padding: "16px 20px", flex: "1 1 140px" }}>
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

export default function HealthPage() {
  const [, navigate] = useLocation();
  const [health, setHealth] = useState<Record<string, any> | null>(null);
  const [status, setStatus] = useState<Status>("loading");
  const [lastRefresh, setLastRefresh] = useState(new Date());

  const load = () => {
    setStatus("loading");
    fetchHealth().then(data => {
      setHealth(data);
      setStatus((data?.status as Status) || "DEGRADED");
      setLastRefresh(new Date());
    });
  };

  useEffect(() => { load(); const t = setInterval(load, 15000); return () => clearInterval(t); }, []);

  const db = health?.database as any || {};
  const entities = health?.entities as any || {};
  const caches = health?.cache as Record<string, any> || {};
  const ollama = health?.ollama as any || {};
  const hints = health?.hints as Record<string, string> || {};

  return (
    <div style={{ minHeight: "100vh", background: "#f4f4f8", fontFamily: "'DM Sans', sans-serif" }}>
      {/* Top bar */}
      <div style={{ background: "#111122", padding: "16px 32px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
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
            ↻ Refresh
          </button>
        </div>
      </div>

      <div style={{ maxWidth: "900px", margin: "0 auto", padding: "40px 24px" }}>
        {/* Status geral */}
        <div style={{ display: "flex", alignItems: "center", gap: "16px", marginBottom: "40px" }}>
          <div style={{ width: "12px", height: "12px", borderRadius: "50%", background: status === "OK" ? "#22c55e" : status === "DEGRADED" ? "#ef4444" : "#f59e0b", boxShadow: `0 0 0 4px ${status === "OK" ? "#22c55e" : "#ef4444"}20` }} />
          <h1 style={{ fontFamily: "'Playfair Display', serif", fontWeight: 700, fontSize: "28px", color: "#111122", margin: 0 }}>
            {status === "loading" ? "Verificando sistema..." : status === "OK" ? "Sistema operacional" : "Sistema degradado"}
          </h1>
          {status !== "loading" && <Badge status={status} />}
        </div>

        {/* Hints de diagnóstico */}
        {Object.entries(hints).length > 0 && (
          <div style={{ background: "#fff8ec", border: "1px solid #b8440040", borderRadius: "12px", padding: "16px 20px", marginBottom: "32px" }}>
            <p style={{ fontWeight: 700, fontSize: "13px", color: "#b84400", marginBottom: "8px" }}>⚠️ Atenção</p>
            {Object.entries(hints).map(([k, v]) => (
              <p key={k} style={{ margin: "4px 0", fontSize: "14px", color: "#555" }}>• {v}</p>
            ))}
          </div>
        )}

        {/* Banco */}
        <Section title="🗄️ Banco de dados">
          <div style={{ display: "flex", gap: "12px", flexWrap: "wrap" }}>
            <StatCard label="Total Raw" value={db.raw_total ?? "—"} />
            <StatCard label="Processados" value={db.processed ?? "—"} />
            <StatCard label="Fila IA" value={db.pending ?? "—"} sub="aguardando Ollama" />
            <StatCard label="Falhas IA" value={db.failed ?? "—"} />
          </div>
          {db.ok === false && <p style={{ color: "#ef4444", fontSize: "13px", marginTop: "8px" }}>Erro: {db.error}</p>}
        </Section>

        {/* Entidades */}
        <Section title="🧬 Extração de entidades">
          <div style={{ display: "flex", gap: "12px", flexWrap: "wrap" }}>
            <StatCard label="Processadas" value={entities.done ?? "—"} sub={entities.progress_pct} />
            <StatCard label="Pendentes" value={entities.pending ?? "—"} sub="aguardando EntityWorker" />
            <StatCard label="Falhas" value={entities.failed ?? "—"} />
          </div>
          {/* Barra de progresso */}
          {entities.total > 0 && (
            <div style={{ marginTop: "16px", background: "#e8e8ee", borderRadius: "8px", height: "8px", overflow: "hidden" }}>
              <div style={{ height: "100%", background: "linear-gradient(90deg, #b84400, #e07020)", borderRadius: "8px", width: entities.progress_pct || "0%", transition: "width 0.5s" }} />
            </div>
          )}
        </Section>

        {/* Cache */}
        <Section title="⚡ Cache">
          <div style={{ display: "grid", gap: "12px" }}>
            {Object.entries(caches).map(([name, stats]: [string, any]) => (
              <div key={name} style={{ background: "#fff", border: "1px solid #e0e0e8", borderRadius: "12px", padding: "16px 20px", display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: "12px" }}>
                <div>
                  <p style={{ margin: 0, fontWeight: 700, fontSize: "14px", color: "#111122" }}>{name}</p>
                  <p style={{ margin: "2px 0 0", fontSize: "12px", color: "#999" }}>{stats.size} entradas em memória</p>
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

        {/* Ollama */}
        <Section title="🤖 Ollama">
          <div style={{ background: "#fff", border: "1px solid #e0e0e8", borderRadius: "12px", padding: "20px", display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: "12px" }}>
            <div>
              <p style={{ margin: 0, fontWeight: 700, fontSize: "15px", color: "#111122" }}>Ollama Local</p>
              <p style={{ margin: "4px 0 0", fontSize: "13px", color: "#999" }}>{ollama.url || "—"}</p>
            </div>
            <Badge status={String(ollama.reachable)} />
          </div>
          {ollama.error && <p style={{ color: "#ef4444", fontSize: "13px", marginTop: "8px" }}>{ollama.error}</p>}
        </Section>

        {/* Workers */}
        <Section title="⚙️ Workers agendados">
          <div style={{ display: "flex", gap: "12px", flexWrap: "wrap" }}>
            {Object.entries((health?.workers || {}) as Record<string, string>).map(([name, sched]) => (
              <div key={name} style={{ background: "#fff", border: "1px solid #e0e0e8", borderRadius: "12px", padding: "14px 18px", flex: "1 1 200px" }}>
                <p style={{ margin: 0, fontWeight: 700, fontSize: "14px", color: "#111122" }}>{name.replace("_", " ")}</p>
                <p style={{ margin: "4px 0 0", fontSize: "12px", color: "#22c55e", fontWeight: 600 }}>● {sched}</p>
              </div>
            ))}
          </div>
        </Section>

        {/* Links de ação */}
        <Section title="🔧 Ações">
          <div style={{ display: "flex", gap: "10px", flexWrap: "wrap" }}>
            {[
              { label: "Forçar ingestão", url: "/api/db/ingest" },
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

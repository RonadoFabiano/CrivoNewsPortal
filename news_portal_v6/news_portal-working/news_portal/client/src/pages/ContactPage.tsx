import { useEffect } from "react";
import { useLocation } from "wouter";
import Header from "@/components/Header";
import { categories } from "@/lib/newsData";
import { applySeo, resetSeo } from "@/lib/seo";

export default function ContactPage() {
  const [, navigate] = useLocation();

  useEffect(() => {
    applySeo({
      title: "Contato - CRIVO News",
      description: "Entre em contato com a equipe do CRIVO News para sugestoes, correcoes e assuntos editoriais.",
      path: "/contato",
    });
    return () => resetSeo();
  }, []);

  return (
    <div className="min-h-screen bg-background">
      <Header
        categories={categories}
        onCategorySelect={(cat) => {
          if (cat === "Todos") { navigate("/"); return; }
          const slug = cat.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "-");
          navigate("/" + slug);
        }}
        activeCategory=""
      />

      <main style={{ maxWidth: "760px", margin: "0 auto", padding: "48px 24px" }}>

        <nav style={{ fontSize: "13px", color: "#888", marginBottom: "32px" }}>
          <a href="/" style={{ color: "#b84400", textDecoration: "none" }}>Inicio</a>
          <span style={{ margin: "0 8px" }}>›</span>
          <span>Contato</span>
        </nav>

        <h1 style={{
          fontFamily: "'Playfair Display', serif",
          fontSize: "clamp(28px, 4vw, 40px)",
          fontWeight: 700, color: "#111122", marginBottom: "8px",
        }}>Contato</h1>
        <div style={{ width: "48px", height: "3px", background: "#b84400", marginBottom: "32px", borderRadius: "2px" }} />

        <div style={{ display: "grid", gap: "24px", gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))", marginBottom: "48px" }}>
          {[
            { icon: "✉️", title: "E-mail editorial", desc: "Para sugestões de pauta, correções ou dúvidas sobre conteúdo", contact: "contato@crivo.news" },
            { icon: "🤝", title: "Parcerias", desc: "Para parcerias de conteúdo ou distribuição", contact: "parcerias@crivo.news" },
            { icon: "⚠️", title: "Correções", desc: "Identificou um erro? Nos avise — corrigimos em até 24h", contact: "correcoes@crivo.news" },
          ].map(item => (
            <div key={item.title} style={{
              padding: "24px", borderRadius: "12px",
              border: "1.5px solid #e8e8f0",
              background: "#fff",
            }}>
              <div style={{ fontSize: "28px", marginBottom: "12px" }}>{item.icon}</div>
              <h2 style={{ fontSize: "16px", fontWeight: 700, color: "#111122", marginBottom: "8px" }}>{item.title}</h2>
              <p style={{ fontSize: "14px", color: "#666", lineHeight: "1.6", marginBottom: "12px" }}>{item.desc}</p>
              <a href={`mailto:${item.contact}`} style={{ color: "#b84400", fontSize: "14px", fontWeight: 600 }}>{item.contact}</a>
            </div>
          ))}
        </div>

        <div style={{ padding: "24px", background: "#f8f8fc", borderRadius: "12px" }}>
          <h2 style={{ fontSize: "16px", fontWeight: 700, color: "#111122", marginBottom: "8px" }}>Política de resposta</h2>
          <p style={{ fontSize: "14px", color: "#555", lineHeight: "1.7" }}>
            Respondemos todos os e-mails em até 48 horas úteis. Solicitações de correção têm prioridade e são tratadas em até 24 horas.
            Para pedidos urgentes relacionados a direitos autorais ou conteúdo sensível, use o e-mail de correções.
          </p>
        </div>

      </main>
    </div>
  );
}

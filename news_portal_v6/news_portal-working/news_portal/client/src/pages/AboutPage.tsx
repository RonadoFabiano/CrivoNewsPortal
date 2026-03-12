import { useEffect } from "react";
import { useLocation } from "wouter";
import Header from "@/components/Header";
import { categories } from "@/lib/newsData";
import { applySeo, resetSeo, BASE_URL } from "@/lib/seo";

export default function AboutPage() {
  const [, navigate] = useLocation();

  useEffect(() => {
    applySeo({
      title: "Sobre o CRIVO News",
      description: "Conheca o CRIVO News, como funciona a curadoria editorial e como o portal organiza as noticias do Brasil e do mundo.",
      path: "/sobre",
    });

    const existing = document.getElementById("schema-org");
    if (existing) existing.remove();
    const script = document.createElement("script");
    script.id = "schema-org";
    script.type = "application/ld+json";
    script.text = JSON.stringify({
      "@context": "https://schema.org",
      "@type": "NewsMediaOrganization",
      "name": "CRIVO News",
      "url": BASE_URL,
      "logo": BASE_URL + "/logo.png",
      "description": "Portal de curadoria jornalistica com inteligencia artificial",
      "foundingDate": "2026",
      "publishingPrinciples": BASE_URL + "/politica-editorial",
      "contactPoint": {
        "@type": "ContactPoint",
        "contactType": "editorial",
        "email": "contato@crivo.news"
      },
      "sameAs": []
    });
    document.head.appendChild(script);
    return () => {
      resetSeo();
      document.getElementById("schema-org")?.remove();
    };
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

        {/* Breadcrumb */}
        <nav style={{ fontSize: "13px", color: "#888", marginBottom: "32px" }}>
          <span style={{ cursor: "pointer", color: "#b84400" }} onClick={() => navigate("/")}>Início</span>
          <span style={{ margin: "0 8px" }}>›</span>
          <span>Sobre</span>
        </nav>

        <h1 style={{
          fontFamily: "'Playfair Display', serif",
          fontSize: "clamp(28px, 4vw, 40px)",
          fontWeight: 700, color: "#111122",
          marginBottom: "8px", lineHeight: 1.2,
        }}>
          Sobre o CRIVO News
        </h1>
        <div style={{ width: "48px", height: "3px", background: "#b84400", marginBottom: "32px", borderRadius: "2px" }} />

        <Section title="O que é o CRIVO News">
          <p>O <strong>CRIVO News</strong> é um portal de curadoria jornalística que utiliza inteligência artificial para monitorar, filtrar e contextualizar as notícias mais relevantes do Brasil e do mundo em tempo real.</p>
          <p style={{ marginTop: "16px" }}>Nosso nome vem do instrumento de filtragem — o crivo — que separa o que é essencial do que é ruído. Essa é exatamente nossa missão: entregar informação filtrada, sem exagero e sem sensacionalismo.</p>
        </Section>

        <Section title="Como funciona">
          <p>Coletamos notícias de múltiplos veículos jornalísticos brasileiros de referência. Cada notícia passa por um processo automatizado de análise onde:</p>
          <ul style={{ marginTop: "12px", paddingLeft: "20px", lineHeight: "2" }}>
            <li>O título é reescrito para ser mais claro e direto</li>
            <li>Uma descrição contextualizada é gerada com base no conteúdo original</li>
            <li>A notícia é classificada por categoria e tema automaticamente</li>
            <li>Entidades relevantes (países, pessoas, tópicos) são identificadas</li>
            <li>Notícias duplicadas ou muito similares são automaticamente removidas</li>
          </ul>
          <p style={{ marginTop: "16px" }}>O conteúdo original sempre é preservado e acessível — o CRIVO News nunca substitui a fonte, apenas facilita o acesso a ela.</p>
        </Section>

        <Section title="Nossa proposta editorial">
          <p>Acreditamos que o excesso de informação é tão prejudicial quanto a falta dela. O leitor moderno não tem tempo para checar dezenas de portais — mas merece estar bem informado.</p>
          <p style={{ marginTop: "16px" }}>O CRIVO News não produz opinião. Não temos partido, não temos patrocinadores editoriais e não somos financiados por anunciantes que influenciem a curadoria. Nossa única responsabilidade é com a clareza e a relevância.</p>
        </Section>

        <Section title="Fontes">
          <p>Monitoramos veículos jornalísticos com redações estabelecidas e histórico de apuração responsável. A lista de fontes é revisada periodicamente. Se identificar um problema com alguma fonte ou notícia, entre em <span style={{ color: "#b84400", cursor: "pointer" }} onClick={() => navigate("/contato")}>contato</span>.</p>
        </Section>

        <div style={{ marginTop: "48px", padding: "24px", background: "#f8f8fc", borderRadius: "12px", borderLeft: "3px solid #b84400" }}>
          <p style={{ fontSize: "14px", color: "#555", lineHeight: "1.7" }}>
            Dúvidas, sugestões ou pedidos de correção? Acesse nossa página de{" "}
            <span style={{ color: "#b84400", cursor: "pointer", fontWeight: 600 }} onClick={() => navigate("/contato")}>Contato</span>{" "}
            ou leia nossa{" "}
            <span style={{ color: "#b84400", cursor: "pointer", fontWeight: 600 }} onClick={() => navigate("/politica-editorial")}>Política Editorial</span>.
          </p>
        </div>

      </main>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section style={{ marginBottom: "36px" }}>
      <h2 style={{
        fontFamily: "'Playfair Display', serif",
        fontSize: "20px", fontWeight: 700,
        color: "#111122", marginBottom: "12px",
      }}>{title}</h2>
      <div style={{ fontSize: "16px", lineHeight: "1.8", color: "#333" }}>
        {children}
      </div>
    </section>
  );
}

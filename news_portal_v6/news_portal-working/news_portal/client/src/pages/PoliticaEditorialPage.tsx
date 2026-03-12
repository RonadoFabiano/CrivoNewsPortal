import { useEffect } from "react";
import { useLocation } from "wouter";
import Header from "@/components/Header";
import { categories } from "@/lib/newsData";
import { applySeo, resetSeo } from "@/lib/seo";

export default function PoliticaEditorialPage() {
  const [, navigate] = useLocation();

  useEffect(() => {
    applySeo({
      title: "Politica Editorial - CRIVO News",
      description: "Conheca os principios editoriais, criterios de curadoria e compromissos do CRIVO News com a qualidade da informacao.",
      path: "/politica-editorial",
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
          <span style={{ cursor: "pointer", color: "#b84400" }} onClick={() => navigate("/")}>Início</span>
          <span style={{ margin: "0 8px" }}>›</span>
          <span>Política Editorial</span>
        </nav>

        <h1 style={{
          fontFamily: "'Playfair Display', serif",
          fontSize: "clamp(28px, 4vw, 40px)",
          fontWeight: 700, color: "#111122", marginBottom: "8px",
        }}>Política Editorial</h1>
        <div style={{ width: "48px", height: "3px", background: "#b84400", marginBottom: "8px", borderRadius: "2px" }} />
        <p style={{ fontSize: "13px", color: "#888", marginBottom: "36px" }}>
          Última atualização: março de 2026
        </p>

        {[
          {
            title: "1. Missão",
            text: "O CRIVO News tem como missão tornar a informação jornalística mais acessível, clara e contextualizada. Usamos tecnologia de inteligência artificial como ferramenta de curadoria e síntese — nunca como substituto do jornalismo profissional."
          },
          {
            title: "2. Critérios de seleção de fontes",
            text: "Monitoramos exclusivamente veículos jornalísticos com redações estabelecidas, CNPJ ativo, histórico de cobertura verificável e que sigam os princípios básicos do jornalismo ético. Não indexamos blogs pessoais, sites de opinião sem identificação ou portais com histórico de desinformação."
          },
          {
            title: "3. Uso de inteligência artificial",
            text: "A IA é usada para: reescrever títulos com maior clareza, gerar resumos contextualizados, classificar notícias por categoria e identificar entidades relevantes. Todo conteúdo gerado pela IA é baseado no texto original da fonte — nunca inventamos fatos. O link para a notícia original é sempre preservado e exibido."
          },
          {
            title: "4. Direitos autorais",
            text: "O CRIVO News não reproduz o conteúdo completo das matérias. Exibimos apenas título, imagem destacada e um resumo gerado a partir do texto original. O leitor é sempre direcionado à fonte para leitura completa. Caso algum veículo deseje ser removido do monitoramento, basta enviar solicitação para contato@crivo.news."
          },
          {
            title: "5. Correções e transparência",
            text: "Erros identificados são corrigidos em até 24 horas. Correções significativas são indicadas na própria notícia com nota de atualização. Não excluímos notícias — corrigimos com transparência."
          },
          {
            title: "6. Independência editorial",
            text: "O CRIVO News não aceita pagamento para incluir, excluir ou destacar notícias. Não temos acordos comerciais com as fontes que monitoramos. Nossa curadoria é baseada exclusivamente em critérios de relevância e qualidade informativa."
          },
          {
            title: "7. Privacidade",
            text: "Não coletamos dados pessoais dos leitores além do necessário para o funcionamento técnico do site. Não utilizamos cookies de rastreamento publicitário. Para dúvidas sobre privacidade, entre em contato pelo e-mail contato@crivo.news."
          },
        ].map(item => (
          <section key={item.title} style={{ marginBottom: "32px" }}>
            <h2 style={{
              fontFamily: "'Playfair Display', serif",
              fontSize: "18px", fontWeight: 700,
              color: "#111122", marginBottom: "10px",
            }}>{item.title}</h2>
            <p style={{ fontSize: "15px", lineHeight: "1.8", color: "#333" }}>{item.text}</p>
          </section>
        ))}

      </main>
    </div>
  );
}

import { NewsArticle } from "@/lib/newsData";
import { X } from "lucide-react";

interface ArticleModalProps {
  article: NewsArticle | null;
  isOpen: boolean;
  onClose: () => void;
}

export default function ArticleModal({
  article,
  isOpen,
  onClose,
}: ArticleModalProps) {
  if (!isOpen || !article) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4 animate-fade-in-up"
      onClick={onClose}
    >
      <div
        className="bg-background rounded-xl max-w-2xl w-full max-h-[90vh] overflow-y-auto shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Close Button */}
        <div className="sticky top-0 flex items-center justify-between p-6 border-b border-border bg-background">
          <h2 className="text-2xl font-bold text-foreground">Artigo Completo</h2>
          <button
            onClick={onClose}
            className="p-2 hover:bg-secondary rounded-lg transition-colors"
          >
            <X className="w-6 h-6" />
          </button>
        </div>

        {/* Content */}
        <div className="p-6 md:p-8 space-y-6">
          {/* Image */}
          <img
            src={article.image}
            alt={article.title}
            className="w-full h-96 object-cover rounded-lg"
            onError={(e) => {
              e.currentTarget.src =
                "https://images.unsplash.com/photo-1557821552-17105176677c?w=1200&h=600&fit=crop";
            }}
          />

          {/* Metadata */}
          <div className="flex items-center gap-4 flex-wrap">
            <span className="category-badge">{article.category}</span>
            <span className="text-sm text-muted-foreground">{article.date}</span>
          </div>

          {/* Title */}
          <div>
            <h1 className="text-4xl md:text-5xl font-bold text-foreground leading-tight mb-4">
              {article.title}
            </h1>
            <div className="h-1 w-16 bg-accent rounded-full"></div>
          </div>

          {/* Summary */}
          <p className="text-lg text-foreground leading-relaxed">
            {article.summary}
          </p>

          {/* Original link */}
          <div className="flex flex-wrap items-center gap-3">
            <a
              href={article.link}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-2 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground hover:opacity-90 transition-opacity"
            >
              Abrir notícia original
            </a>
            <span className="text-sm text-muted-foreground">
              Fonte: <span className="font-medium">{article.source}</span>
            </span>
          </div>

          {/* Extended Content */}
          <div className="prose prose-lg max-w-none text-foreground space-y-4">
            <p>
              Este portal está puxando notícias automaticamente do RSS e já
              organizando por categoria. No próximo passo, dá pra usar IA para
              melhorar o resumo, gerar contexto e sugerir “Leia também”.
            </p>

            <p>
              Para SEO, o ideal é criar uma rota pública por slug (ex:
              /noticia/{article.slug}) e gerar sitemap.xml — isso ajuda o Google
              a indexar e mandar tráfego orgânico.
            </p>

            <p>
              Você pode personalizar este template adicionando suas próprias
              notícias, ajustando o design conforme necessário, e integrando
              com suas APIs de dados de notícias.
            </p>

            <h3 className="text-2xl font-bold text-foreground pt-4">
              Próximos Passos
            </h3>

            <ul className="space-y-2 text-foreground">
              <li className="flex gap-3">
                <span className="text-accent font-bold">•</span>
                <span>Integrar com agentes de IA para curadoria automática</span>
              </li>
              <li className="flex gap-3">
                <span className="text-accent font-bold">•</span>
                <span>Adicionar sistema de comentários e interação</span>
              </li>
              <li className="flex gap-3">
                <span className="text-accent font-bold">•</span>
                <span>Implementar busca avançada e filtros</span>
              </li>
              <li className="flex gap-3">
                <span className="text-accent font-bold">•</span>
                <span>Criar newsletter e sistema de notificações</span>
              </li>
            </ul>
          </div>

          {/* Share Section */}
          <div className="pt-6 border-t border-border">
            <p className="text-sm font-semibold text-foreground mb-4">
              Compartilhar
            </p>
            <div className="flex gap-3">
              <button className="p-3 bg-secondary hover:bg-muted rounded-lg transition-colors">
                <span className="text-sm font-semibold text-foreground">
                  Facebook
                </span>
              </button>
              <button className="p-3 bg-secondary hover:bg-muted rounded-lg transition-colors">
                <span className="text-sm font-semibold text-foreground">
                  Twitter
                </span>
              </button>
              <button className="p-3 bg-secondary hover:bg-muted rounded-lg transition-colors">
                <span className="text-sm font-semibold text-foreground">
                  LinkedIn
                </span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

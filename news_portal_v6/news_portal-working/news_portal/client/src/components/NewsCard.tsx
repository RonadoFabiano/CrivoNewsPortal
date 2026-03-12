import { NewsArticle } from "@/lib/newsData";
import { ArrowRight } from "lucide-react";
import { useLocation } from "wouter";

interface NewsCardProps {
  article: NewsArticle;
  onClick?: () => void;
}

export default function NewsCard({ article }: NewsCardProps) {
  const [, navigate] = useLocation();
  const href = `/noticia/${article.slug}`;

  const handleNavigate = (event: React.MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    navigate(href);
  };

  return (
    <article itemScope itemType="https://schema.org/NewsArticle">
      <a href={href} onClick={handleNavigate} className="news-card group block cursor-pointer">
        <div className="relative overflow-hidden bg-secondary">
          <img
            src={article.image || "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=800&h=500&fit=crop"}
            alt={article.title}
            itemProp="image"
            className="news-card-image group-hover:scale-105 transition-transform duration-500"
            onError={(e) => {
              e.currentTarget.src =
                "https://images.unsplash.com/photo-1557821552-17105176677c?w=800&h=500&fit=crop";
            }}
          />
          <div className="absolute top-0 left-0 h-1 w-12 bg-accent" />
        </div>

        <div className="news-card-content">
          <div className="flex items-center gap-2">
            <span className="category-badge" itemProp="articleSection">{article.category}</span>
            <span className="text-xs text-muted-foreground" itemProp="datePublished">{article.date}</span>
          </div>

          <h3 className="news-card-title group-hover:text-accent transition-colors" itemProp="headline">
            {article.title}
          </h3>

          <p className="news-card-summary" itemProp="description">{article.summary}</p>

          <div className="flex items-center gap-2 text-accent font-semibold text-sm pt-2 group-hover:gap-3 transition-all">
            <span>Leia mais</span>
            <ArrowRight className="w-4 h-4" />
          </div>
        </div>
      </a>
    </article>
  );
}

export type BackendNewsItem = {
  slug: string;
  title: string;
  description: string;
  link: string;
  source: string;
  image: string;
  publishedAt: string;
  category: string;
  categories?: string[];  // múltiplas categorias (novo)
};

export type NewsArticle = {
  id: string;
  slug: string;
  title: string;
  summary: string;
  category: string;
  categories: string[];   // múltiplas categorias para filtros
  date: string;
  image: string;
  link: string;
  source: string;
  featured?: boolean;
};

// Detecta automaticamente o host — funciona tanto em localhost quanto na rede local (mobile)
// Se VITE_API_BASE estiver definido no .env, usa ele
// Caso contrário, usa o mesmo hostname do frontend com porta 8081
function getApiBase(): string {
  if ((import.meta as any).env?.VITE_API_BASE) {
    return (import.meta as any).env.VITE_API_BASE;
  }
  if (typeof window !== "undefined") {
    const host = window.location.hostname;
    return `http://${host}:8081/api`;
  }
  return "http://localhost:8081/api";
}
export { getApiBase };
const API_BASE = getApiBase();

function formatDatePtBR(isoInstant: string): string {
  try {
    return new Date(isoInstant).toLocaleDateString("pt-BR", {
      day: "2-digit", month: "long", year: "numeric"
    });
  } catch { return ""; }
}

function toArticle(item: BackendNewsItem, featured = false): NewsArticle {
  // Suporta tanto o campo "categories" (array) quanto "category" (string)
  const cats: string[] = item.categories?.length
    ? item.categories
    : item.category ? [item.category] : ["Geral"];

  return {
    id:         item.slug,
    slug:       item.slug,
    title:      item.title,
    summary:    item.description,
    category:   cats[0],           // primeira = mais relevante
    categories: cats,
    date:       item.publishedAt ? formatDatePtBR(item.publishedAt) : "",
    publishedAt: item.publishedAt || "",
    image:      item.image ||
      "https://images.unsplash.com/photo-1557821552-17105176677c?w=800&h=500&fit=crop",
    link:       item.link,
    source:     item.source,
    featured,
  };
}

/**
 * Busca artigos processados pelo Ollama (/api/db/news).
 * Retorna apenas artigos com título e descrição gerados pela IA.
 * Suporta filtro por categoria múltipla.
 */
export async function fetchNews(params: {
  category?: string;
  limit?: number;
  signal?: AbortSignal;
}): Promise<NewsArticle[]> {
  const url = new URL(`${API_BASE}/db/news`);
  if (params.category && params.category !== "Todos") {
    url.searchParams.set("category", params.category);
  }
  url.searchParams.set("limit", String(params.limit ?? 60));

  const res = await fetch(url.toString(), {
    headers: { Accept: "application/json" },
    signal: params.signal,
  });

  if (!res.ok) throw new Error(`Falha ao buscar notícias (HTTP ${res.status})`);

  const data = (await res.json()) as BackendNewsItem[];
  return data.map((x, idx) => toArticle(x, idx === 0));
}

/**
 * Busca artigo individual pelo slug.
 * Tenta /api/db/news/{slug} primeiro, depois /api/news/{slug}.
 */
// Cache de artigos individuais — evita refetch ao voltar para o mesmo artigo
const _articleCache = new Map<string, { data: NewsArticle; ts: number }>();
const ARTICLE_TTL = 10 * 60 * 1000; // 10 minutos

export async function fetchNewsBySlug(slug: string): Promise<NewsArticle> {
  const cached = _articleCache.get(slug);
  if (cached && Date.now() - cached.ts < ARTICLE_TTL) return cached.data;

  const res = await fetch(`${API_BASE}/db/news/${encodeURIComponent(slug)}`, {
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`Notícia não encontrada (HTTP ${res.status})`);
  const article = toArticle(await res.json() as BackendNewsItem);
  _articleCache.set(slug, { data: article, ts: Date.now() });
  return article;
}


// ── Trending topics ───────────────────────────────────────────────────────────
export interface TrendingItem {
  label: string;
  type: "country" | "person" | "topic";
  slug: string;
  count: number;
}

export async function fetchTrending(): Promise<TrendingItem[]> {
  try {
    const res = await fetch(`${API_BASE}/db/trending`, {
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return [];
    return (await res.json()) as TrendingItem[];
  } catch { return []; }
}

// ── Notícias por tag SEO (/noticias/{tag}) ────────────────────────────────────
export async function fetchByTag(tag: string, limit = 40): Promise<NewsArticle[]> {
  const res = await fetch(`${API_BASE}/db/tag/${encodeURIComponent(tag)}?limit=${limit}`, {
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`Tag não encontrada (HTTP ${res.status})`);
  const data = (await res.json()) as BackendNewsItem[];
  return data.map((x, i) => toArticle(x, i === 0));
}

// ── Notícias por entidade (/pais /pessoa /topico) ─────────────────────────────
export async function fetchByEntity(
  type: "country" | "person" | "topic",
  value: string,
  limit = 40
): Promise<NewsArticle[]> {
  const endpoint = type === "country" ? "country"
                 : type === "person"  ? "person"
                 : "topic";
  const res = await fetch(
    `${API_BASE}/db/${endpoint}/${encodeURIComponent(value)}?limit=${limit}`,
    { headers: { Accept: "application/json" } }
  );
  if (!res.ok) throw new Error(`Entidade não encontrada (HTTP ${res.status})`);
  const data = (await res.json()) as BackendNewsItem[];
  return data.map((x, i) => toArticle(x, i === 0));
}


// ── Busca full-text no backend ────────────────────────────────────────────────
export async function fetchSearch(query: string, limit = 30): Promise<NewsArticle[]> {
  if (!query || query.trim().length < 2) return [];
  try {
    const res = await fetch(
      `${API_BASE}/db/search?q=${encodeURIComponent(query.trim())}&limit=${limit}`,
      { headers: { Accept: "application/json" } }
    );
    if (!res.ok) return [];
    const data = (await res.json()) as BackendNewsItem[];
    return data.map(x => toArticle(x));
  } catch { return []; }
}

// ── Recomendação ──────────────────────────────────────────────────────────────
export async function fetchRecommendations(slug: string): Promise<NewsArticle[]> {
  try {
    const res = await fetch(`${API_BASE}/db/recommend/${encodeURIComponent(slug)}`, {
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return [];
    const data = (await res.json()) as BackendNewsItem[];
    return data.map(x => toArticle(x));
  } catch { return []; }
}

// ── Ranking / Mais lidas ──────────────────────────────────────────────────────
export async function fetchRanked(limit = 20): Promise<NewsArticle[]> {
  try {
    const res = await fetch(`${API_BASE}/db/trending-news?limit=${limit}`, {
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return [];
    const data = (await res.json()) as BackendNewsItem[];
    return data.map((x, i) => toArticle(x, i === 0));
  } catch { return []; }
}

// ── Clusters ──────────────────────────────────────────────────────────────────
export interface Cluster {
  cluster_slug: string;
  size: number;
  label: string;
  image?: string;
  publishedAt: string;
  articles: { slug: string; title: string; source: string; publishedAt: string; image?: string }[];
}

export async function fetchClusters(): Promise<Cluster[]> {
  try {
    const res = await fetch(`${API_BASE}/db/clusters`, {
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return [];
    return (await res.json()) as Cluster[];
  } catch { return []; }
}

// ── Explica ───────────────────────────────────────────────────────────────────
export interface ExplicaResult {
  topic: string;
  slug: string;
  summary: string;
  context: string;
  related_articles: BackendNewsItem[];
  generated_at: string;
  error?: string;
}

export async function fetchExplica(slug: string): Promise<ExplicaResult | null> {
  try {
    const res = await fetch(`${API_BASE}/db/explica/${encodeURIComponent(slug)}`, {
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return null;
    return (await res.json()) as ExplicaResult;
  } catch { return null; }
}

// ── Health ────────────────────────────────────────────────────────────────────
export async function fetchHealth(): Promise<Record<string, unknown> | null> {
  try {
    const res = await fetch(`${API_BASE}/db/health`, {
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return null;
    return await res.json();
  } catch { return null; }
}

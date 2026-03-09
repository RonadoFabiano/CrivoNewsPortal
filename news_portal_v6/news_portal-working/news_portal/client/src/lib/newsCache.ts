/**
 * Cache em memória compartilhado.
 * Garante UMA única requisição ao backend por tipo de dado,
 * reutilizada por todos os componentes que montam juntos.
 */
import { fetchNews, fetchTrending, NewsArticle, TrendingItem } from "./api";

// ── Notícias ──────────────────────────────────────────────────
let newsData: NewsArticle[] | null = null;
let newsTs = 0;
let newsInflight: Promise<NewsArticle[]> | null = null;
const NEWS_TTL = 2 * 60 * 1000;

export async function getLatestNews(limit = 60): Promise<NewsArticle[]> {
  if (newsData && Date.now() - newsTs < NEWS_TTL) return newsData.slice(0, limit);
  if (newsInflight) return newsInflight.then(d => d.slice(0, limit));
  newsInflight = fetchNews({ limit: 60 }).then(data => {
    newsData = data; newsTs = Date.now(); newsInflight = null; return data;
  }).catch(e => { newsInflight = null; throw e; });
  return newsInflight.then(d => d.slice(0, limit));
}

// ── Trending ──────────────────────────────────────────────────
let trendData: TrendingItem[] | null = null;
let trendTs = 0;
let trendInflight: Promise<TrendingItem[]> | null = null;
const TREND_TTL = 5 * 60 * 1000;

export async function getCachedTrending(): Promise<TrendingItem[]> {
  if (trendData && Date.now() - trendTs < TREND_TTL) return trendData;
  if (trendInflight) return trendInflight;
  trendInflight = fetchTrending().then(data => {
    trendData = data; trendTs = Date.now(); trendInflight = null; return data;
  }).catch(e => { trendInflight = null; throw e; });
  return trendInflight;
}

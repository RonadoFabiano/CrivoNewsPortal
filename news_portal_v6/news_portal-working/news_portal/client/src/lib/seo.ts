const BASE_URL = "https://crivo.news";
const DEFAULT_IMAGE = `${BASE_URL}/placeholder.svg`;
const DEFAULT_TITLE = "CRIVO News";
const DEFAULT_DESCRIPTION = "Portal de noticias com curadoria de IA. Informacao filtrada, sem ruido e sem exagero.";

type SeoOptions = {
  title: string;
  description?: string;
  path?: string;
  image?: string;
  type?: "website" | "article";
  robots?: string;
};

function ensureMeta(selector: string): HTMLMetaElement {
  let el = document.querySelector(selector) as HTMLMetaElement | null;
  if (!el) {
    el = document.createElement("meta");
    const match = selector.match(/\[([^=\]]+)="([^"]+)"\]/);
    if (match) {
      el.setAttribute(match[1], match[2]);
    }
    document.head.appendChild(el);
  }
  return el;
}

function setMeta(selector: string, value: string) {
  ensureMeta(selector).setAttribute("content", value);
}

function ensureCanonical(): HTMLLinkElement {
  let link = document.querySelector('link[rel="canonical"]') as HTMLLinkElement | null;
  if (!link) {
    link = document.createElement("link");
    link.rel = "canonical";
    document.head.appendChild(link);
  }
  return link;
}

function absoluteUrl(path?: string) {
  if (!path || path === "/") return BASE_URL;
  if (path.startsWith("http://") || path.startsWith("https://")) return path;
  return `${BASE_URL}${path.startsWith("/") ? path : `/${path}`}`;
}

export function applySeo({
  title,
  description = DEFAULT_DESCRIPTION,
  path = "/",
  image = DEFAULT_IMAGE,
  type = "website",
  robots = "index, follow",
}: SeoOptions) {
  const canonical = absoluteUrl(path);
  const pageTitle = title.includes("CRIVO News") ? title : `${title} | CRIVO News`;

  document.title = pageTitle;

  setMeta('meta[name="description"]', description);
  setMeta('meta[name="robots"]', robots);

  setMeta('meta[property="og:site_name"]', "CRIVO News");
  setMeta('meta[property="og:title"]', pageTitle);
  setMeta('meta[property="og:description"]', description);
  setMeta('meta[property="og:url"]', canonical);
  setMeta('meta[property="og:type"]', type);
  setMeta('meta[property="og:image"]', image);

  setMeta('meta[name="twitter:card"]', "summary_large_image");
  setMeta('meta[name="twitter:title"]', pageTitle);
  setMeta('meta[name="twitter:description"]', description);
  setMeta('meta[name="twitter:image"]', image);

  ensureCanonical().href = canonical;
}

export function resetSeo() {
  document.title = DEFAULT_TITLE;
  setMeta('meta[name="description"]', DEFAULT_DESCRIPTION);
  setMeta('meta[name="robots"]', "index, follow");
  setMeta('meta[property="og:site_name"]', "CRIVO News");
  setMeta('meta[property="og:title"]', DEFAULT_TITLE);
  setMeta('meta[property="og:description"]', DEFAULT_DESCRIPTION);
  setMeta('meta[property="og:url"]', BASE_URL);
  setMeta('meta[property="og:type"]', "website");
  setMeta('meta[property="og:image"]', DEFAULT_IMAGE);
  setMeta('meta[name="twitter:card"]', "summary_large_image");
  setMeta('meta[name="twitter:title"]', DEFAULT_TITLE);
  setMeta('meta[name="twitter:description"]', DEFAULT_DESCRIPTION);
  setMeta('meta[name="twitter:image"]', DEFAULT_IMAGE);
  ensureCanonical().href = BASE_URL;
}

export { BASE_URL, DEFAULT_IMAGE };
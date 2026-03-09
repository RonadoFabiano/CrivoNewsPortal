import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import NotFound from "@/pages/NotFound";
import NewsPage from "@/pages/NewsPage";
import CategoryPage from "@/pages/CategoryPage";
import EntityPage from "@/pages/EntityPage";
import TrendingPage from "@/pages/TrendingPage";
import ClustersPage from "@/pages/ClustersPage";
import ExplicaPage from "@/pages/ExplicaPage";
import HealthPage from "@/pages/HealthPage";
import AboutPage from "@/pages/AboutPage";
import ContactPage from "@/pages/ContactPage";
import PoliticaEditorialPage from "@/pages/PoliticaEditorialPage";
import { Route, Switch } from "wouter";
import ErrorBoundary from "./components/ErrorBoundary";
import { ThemeProvider } from "./contexts/ThemeContext";
import Home from "./pages/Home";
import BuscaPage from "./pages/BuscaPage";
import MapaGlobal from "./pages/MapaGlobal";

// Categorias com rota própria — /brasil, /economia, etc.
const CATEGORY_ROUTES: Record<string, string> = {
  "/brasil":         "Brasil",
  "/mundo":          "Mundo",
  "/politica":       "Política",
  "/economia":       "Economia",
  "/negocios":       "Negócios",
  "/tecnologia":     "Tecnologia",
  "/esportes":       "Esportes",
  "/ciencia":        "Ciência",
  "/saude":          "Saúde",
  "/educacao":       "Educação",
  "/entretenimento": "Entretenimento",
  "/cotidiano":      "Cotidiano",
  "/justica":        "Justiça",
  "/cultura":        "Cultura",
  "/geral":          "Geral",
};

function Router() {
  return (
    <Switch>
      <Route path="/" component={Home} />

      {/* Rotas de categoria — /brasil, /economia, etc. */}
      {Object.entries(CATEGORY_ROUTES).map(([path, label]) => (
        <Route key={path} path={path}>
          {() => <CategoryPage category={label} />}
        </Route>
      ))}

      {/* Notícia individual */}
      <Route path="/noticia/:slug" component={NewsPage} />

      {/* Páginas SEO por tag automática */}
      <Route path="/noticias/:tag">
        {(params) => <EntityPage type="tag" value={params.tag || ""} />}
      </Route>

      {/* Páginas de entidade */}
      <Route path="/pais/:slug">
        {(params) => <EntityPage type="country" value={params.slug || ""} />}
      </Route>
      <Route path="/pessoa/:slug">
        {(params) => <EntityPage type="person" value={params.slug || ""} />}
      </Route>
      <Route path="/topico/:slug">
        {(params) => <EntityPage type="topic" value={params.slug || ""} />}
      </Route>

      {/* Trending, Clusters, Explica, Health */}
      <Route path="/trending" component={TrendingPage} />
      <Route path="/mais-lidas" component={TrendingPage} />
      <Route path="/clusters" component={ClustersPage} />
      <Route path="/explica/:slug" component={ExplicaPage} />
      <Route path="/sistema" component={HealthPage} />
      <Route path="/sobre" component={AboutPage} />
      <Route path="/contato" component={ContactPage} />
      <Route path="/politica-editorial" component={PoliticaEditorialPage} />

      <Route path="/busca" component={BuscaPage} />
        <Route path="/mapa" component={MapaGlobal} />

      <Route path="/404" component={NotFound} />
      <Route component={NotFound} />
    </Switch>
  );
}

function App() {
  return (
    <ErrorBoundary>
      <ThemeProvider defaultTheme="light">
        <TooltipProvider>
          <Toaster />
          <Router />
        </TooltipProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
}

export default App;

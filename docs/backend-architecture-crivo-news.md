# Arquitetura Backend - Crivo News

Este documento descreve a arquitetura atual do backend Spring do Crivo News de ponta a ponta: configuracao, coleta, persistencia, pipeline de processamento, IA, tokens, schedulers, APIs e o papel de cada classe principal.

Referencias principais:
- [GlobalPulseNewsApplication.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/GlobalPulseNewsApplication.java)
- [application.yml](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/resources/application.yml)
- [AppRuntimeProperties.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/config/AppRuntimeProperties.java)
- [NewsController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/NewsController.java)
- [NewsIngestionJob.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/NewsIngestionJob.java)
- [RssService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RssService.java)
- [ScraperOrchestrator.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ScraperOrchestrator.java)
- [RuntimeAdminController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RuntimeAdminController.java)

## 1. Visao Geral

O backend e uma esteira assincrona com varios trilhos paralelos:

1. Coleta noticias de RSS e de scrapers por portal.
2. Deduplica e salva em `raw_article`.
3. Baixa HTML bruto do artigo.
4. Normaliza HTML em texto limpo.
5. Gera titulo, descricao e categorias com IA.
6. Enriquece entidades e sinais editoriais.
7. Atualiza caches de produto, ranking, trending e mapa narrativo.
8. Exponibiliza tudo por endpoints REST para frontend e operacao.

Ponto chave de operacao:
- O sistema nao roda em sequencia linear unica.
- Existem varios `@Scheduled` ativos ao mesmo tempo.
- O log cronologico mistura coleta nova, filas antigas do banco e jobs analiticos.

## SEO e Indexacao

A descoberta organica agora depende de um conjunto coordenado entre frontend e backend:

- [SitemapController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/SitemapController.java) gera o sitemap com paginas publicas, categorias, noticias, paises, pessoas, topicos e paginas de explica.
- [robots.txt](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/resources/static/robots.txt) entrega a versao do backend.
- [robots.txt](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/public/robots.txt) entrega a versao estatica do frontend.
- [seo.ts](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/lib/seo.ts) centraliza canonical, meta description, robots, Open Graph e Twitter Cards.

### Regras de indexacao

Paginas com `index, follow`:
- home
- noticias
- categorias
- topicos
- paises
- pessoas
- explica
- sobre
- contato
- politica editorial
- mapa

Paginas com `noindex, follow`:
- busca
- 404

Paginas com `noindex, nofollow`:
- sistema

### Links internos

Para o Google percorrer melhor o site, as paginas institucionais, navegacao principal, cards editoriais e listas de tendencia passaram a usar links reais (`<a href>`) em vez de apenas `onClick + navigate()`.

Arquivos com melhorias de navegacao interna:
- [AboutPage.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/pages/AboutPage.tsx)
- [ContactPage.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/pages/ContactPage.tsx)
- [PoliticaEditorialPage.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/pages/PoliticaEditorialPage.tsx)
- [ExplicaPage.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/pages/ExplicaPage.tsx)
- [TrendingPage.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/pages/TrendingPage.tsx)
- [ClustersPage.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/pages/ClustersPage.tsx)
- [EntityPage.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/pages/EntityPage.tsx)
- [CategoryPage.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/pages/CategoryPage.tsx)
- [Header.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/components/Header.tsx)
- [Hero.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/components/Hero.tsx)
- [NewsCard.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/components/NewsCard.tsx)
- [NewsCardCompact.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/components/NewsCardCompact.tsx)
- [MaisLidas.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/components/MaisLidas.tsx)
- [TrendingSection.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/components/TrendingSection.tsx)
- [TopicosDestaque.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/components/TopicosDestaque.tsx)

### Como ajustar

Para alterar SEO de uma pagina publica, o ponto principal agora e `applySeo(...)` em [seo.ts](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/lib/seo.ts). As paginas de noticia continuam com logica propria em [NewsPage.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/pages/NewsPage.tsx) por causa do schema `NewsArticle` e dos metadados especificos de artigo.

Campos ajustaveis por pagina:
- titulo
- descricao
- caminho canonico
- imagem social
- tipo (`website` ou `article`)
- politica de `robots`

Para adicionar novas paginas ao sitemap, ajustar:
- [SitemapController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/SitemapController.java)
- e garantir que a rota exista no frontend em [App.tsx](C:/projetos/CrivoNewsPortal/news_portal_v6/news_portal-working/news_portal/client/src/App.tsx)


## 2. Estrutura de Configuracao

A configuracao operacional agora fica centralizada em:
- [application.yml](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/resources/application.yml)
- [AppRuntimeProperties.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/config/AppRuntimeProperties.java)

`AppRuntimeProperties` faz o binding de tudo que estiver abaixo de `app.*`.

Blocos principais:
- `app.ingestion`
  Controla o ciclo principal de ingestao.
- `app.rss`
  Controla o scheduler e cache do RSS.
- `app.scraper`
  Controla refresh, timeout, limite por portal e selecao de portais.
- `app.lab`
  Modo laboratorio para isolar fonte ou desligar workers.
- `app.scheduling`
  Pool do scheduler global.
- `app.ai.worker`
  Worker de resumo por IA.

Configuracao padrao atual:
- RSS habilitado
- todos os portais habilitados
- workers habilitados
- limite do scraper por portal em `0`, ou seja, sem corte artificial

### 2.1 Chaves mais importantes

Em [application.yml](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/resources/application.yml):

- `app.ingestion.enable-rss`
  Liga ou desliga a participacao do RSS dentro do ciclo de ingestao.
- `app.ingestion.max-per-cycle`
  Quantos artigos novos no maximo entram por ciclo.
- `app.ingestion.initial-delay-ms`
- `app.ingestion.fixed-delay-ms`

- `app.rss.enabled`
  Liga ou desliga o scheduler do `RssService`.
- `app.rss.defaultFeed`
- `app.rss.initial-delay-ms`
- `app.rss.fixed-delay-ms`
- `app.rss.category-cache-ttl-minutes`

- `app.scraper.fetch-timeout-ms`
- `app.scraper.max-per-portal`
- `app.scraper.fixed-delay-ms`
- `app.scraper.cache-ttl-minutes`
- `app.scraper.active-portals`
  Lista de portais permitidos. Vazia = todos.

- `app.lab.only-source`
  Isola um portal ou fonte especifica, como `Metropoles`.
- `app.lab.disable-workers`
  Desliga normalizacao, IA, entidades, mapa narrativo e digest semanal.

- `app.scheduling.pool-size`
- `app.scheduling.await-termination-seconds`
- `app.scheduling.thread-name-prefix`

- `app.ai.worker.enabled`
- `app.ai.worker.delayMs`
- `app.ai.worker.maxChars`
- `app.ai.worker.maxRetries`

## 3. Fluxo de Negocio Ponta a Ponta

### 3.1 Entrada

Existem duas fontes de entrada:
- RSS via [RssService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RssService.java)
- Scraper por home via [ScraperOrchestrator.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ScraperOrchestrator.java)

Quem une essas duas entradas e decide o que vai para o banco e:
- [NewsIngestionJob.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/NewsIngestionJob.java)

### 3.2 Persistencia em duas camadas

A persistencia foi separada em duas entidades principais:

- [RawArticle.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/RawArticle.java)
  Camada operacional de captura.
- [ProcessedArticle.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/ProcessedArticle.java)
  Camada editorial e analitica ja enriquecida.

Separacao conceitual:
- `raw_article` guarda o bruto, estados de fila e HTML.
- `processed_article` guarda o artigo pronto para consumo do produto.

### 3.3 Pipeline resumido

1. `NewsIngestionJob` chama RSS e Scraper.
2. Faz deduplicacao por slug, URL e similaridade de titulo.
3. Usa `ArticleExtractor` para baixar HTML bruto quando necessario.
4. Salva em `raw_article` com estados iniciais.
5. `HtmlNormalizerService` converte HTML em texto.
6. `AiSummaryWorker` cria ou atualiza `processed_article`.
7. `EntityWorker` extrai entidades, tags e sinais analiticos.
8. `MapNarrativeWorker` monta relacoes narrativas a partir dos processados.

## 4. Schedulers e Tempos

Todos os schedulers usam o pool configurado em:
- [SchedulingConfig.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/config/SchedulingConfig.java)

Esse pool agora vem de `app.scheduling.*`.

### 4.1 Schedulers ativos

- [RssService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RssService.java)
  Metodo: `refreshCacheScheduled`
  Trigger: fixed delay
  Config: `app.rss.initial-delay-ms`, `app.rss.fixed-delay-ms`
  Papel: renovar cache por categoria do RSS.

- [ScraperOrchestrator.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ScraperOrchestrator.java)
  Metodo: `scheduledRefresh`
  Trigger: fixed delay
  Config: `app.scraper.fixed-delay-ms`
  Papel: renovar cache agregado do scraper para os portais ativos.

- [NewsIngestionJob.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/NewsIngestionJob.java)
  Metodo: `run`
  Trigger: fixed delay
  Config: `app.ingestion.initial-delay-ms`, `app.ingestion.fixed-delay-ms`
  Papel: juntar RSS e scraper, deduplicar e inserir `raw_article`.

- [HtmlNormalizerService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/HtmlNormalizerService.java)
  Metodo: `processQueue`
  Trigger: fixed delay
  Valor atual no codigo: `initialDelay=20000`, `fixedDelay=10000`
  Papel: normalizar HTML em texto.

- [AiSummaryWorker.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/AiSummaryWorker.java)
  Metodo: `runOne`
  Trigger: fixed delay
  Config: `app.ai.worker.delayMs`
  Papel: chamar IA e gerar `processed_article`.

- [EntityWorker.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/EntityWorker.java)
  Metodo: `runOne`
  Trigger: fixed delay
  Valor atual no codigo: `initialDelay=75000`, `fixedDelay=35000`
  Papel: extrair entidades e tags.

- [MapNarrativeWorker.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/MapNarrativeWorker.java)
  Metodo: `run`
  Trigger: fixed delay
  Valor atual no codigo: `initialDelay=120000`, `fixedDelay=1800000`
  Papel: atualizar cache narrativo.

- [WeeklyDigestJob.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/WeeklyDigestJob.java)
  Metodo: `generateWeeklyDigest`
  Trigger: cron
  Valor atual no codigo: segunda-feira as 07:00
  Papel: gerar digest editorial semanal.

- [GroqRateLimiter.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/GroqRateLimiter.java)
  Metodo: `closeCycle`
  Trigger: fixed delay
  Valor atual no codigo: `60000`
  Papel: girar ciclos de metricas de token.

### 4.2 Endpoint de observabilidade de schedulers

Existe um endpoint administrativo para ver as configuracoes em runtime:
- [RuntimeAdminController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RuntimeAdminController.java)
- `GET /api/admin/runtime`

Ele expoe:
- configuracao de laboratorio
- configuracao de ingestao
- configuracao de RSS
- configuracao de scraper
- configuracao de scheduling
- lista dos schedulers conhecidos, com status, delays e descricao

## 5. Camada de Entrada

### 5.1 RSS

Classe central:
- [RssService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RssService.java)

Responsabilidades:
- manter mapa `SOURCES` por categoria
- buscar multiplas fontes em paralelo
- diferenciar `GOOGLE` de `DIRECT`
- resolver URL final e imagem
- montar `NewsItem`
- deduplicar por slug e titulo
- manter cache por categoria

Dependencias principais:
- [PublisherPreviewService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/PublisherPreviewService.java)
  Resolve URL final e imagem para wrappers do Google News.

Pontos importantes:
- `DIRECT` normalmente ja vem com URL final.
- `GOOGLE` precisa de resolucao antes de gerar o item final.
- `app.rss.enabled=false` desliga o scheduler do RSS.
- `app.ingestion.enable-rss=false` remove RSS apenas do ciclo de ingestao.

### 5.2 Scraper por portal

Classe central:
- [ScraperOrchestrator.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ScraperOrchestrator.java)

Responsabilidades:
- construir a lista de portais ativos
- abrir a home de cada portal
- descobrir links validos
- extrair artigo completo ou cair em fallback minimo
- manter cache por portal e cache agregado
- expor status do cache e portais ativos

Regras de ativacao de portal:
1. Se `app.lab.only-source` estiver preenchido, roda somente aquela fonte.
2. Senao, se `app.scraper.active-portals` tiver valores, roda apenas essa lista.
3. Senao, roda todos os portais registrados.

### 5.3 Contrato e implementacoes do scraper

Contrato:
- [NewsPortalScraper.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/NewsPortalScraper.java)
- [PortalScrapeRequest.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/PortalScrapeRequest.java)

Implementacoes registradas no orquestrador:
- [ConfigurablePortalScraper.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ConfigurablePortalScraper.java)
- [InfoMoneyPortalScraper.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/InfoMoneyPortalScraper.java)
- [ForbesBrasilPortalScraper.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ForbesBrasilPortalScraper.java)

Motor tecnico comum:
- [ScraperPortalSupport.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ScraperPortalSupport.java)

Esse helper faz:
- limpeza de ruido de HTML
- descoberta do bloco principal de conteudo
- fallback de titulo
- normalizacao de URL canonica
- heuristicas de imagem
- heuristicas inspiradas na `ExtractionDebugger`

### 5.4 Classe de debug

- [ExtractionDebugger.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/debug/ExtractionDebugger.java)

Papel:
- nao participa da producao
- e um laboratorio para testar portais isoladamente
- serviu como base para o motor atual de extracao do scraper

## 6. Camada de Ingestao

Classe central:
- [NewsIngestionJob.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/NewsIngestionJob.java)

Responsabilidades:
- chamar RSS e scraper
- consolidar itens
- deduplicar por URL e titulo
- baixar HTML com `ArticleExtractor`
- salvar `RawArticle`
- manter cache recente de titulos para evitar repeticao

### 6.1 correlationId por ciclo

Cada ciclo de ingestao agora cria um id curto e loga tudo com prefixo:
- `[INGESTION cid=xxxxxxxx]`

Objetivo:
- separar um ciclo do outro no log
- facilitar leitura quando varios schedulers escrevem em paralelo

### 6.2 Chamadas principais

`NewsIngestionJob.ingestOnce()` chama:
- [RssService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RssService.java)
- [ScraperOrchestrator.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ScraperOrchestrator.java)
- [ArticleExtractor.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/ArticleExtractor.java)
- [RawArticleRepository.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/RawArticleRepository.java)

## 7. Persistencia e Filas

### 7.1 `raw_article`

Entidade:
- [RawArticle.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/RawArticle.java)

Campos relevantes:
- `canonicalUrl`
- `slug`
- `rawTitle`
- `rawDescription`
- `htmlContent`
- `normalizeStatus`
- `rawContentText`
- `imageUrl`
- `source`
- `originalCategory`
- `aiStatus`
- `aiRetries`

Estados principais:
- `normalizeStatus`: `PENDING_NORMALIZE` -> `NORMALIZED`
- `aiStatus`: `PENDING` -> `DONE` ou `FAILED`

Repositorio:
- [RawArticleRepository.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/RawArticleRepository.java)

Queries operacionais:
- `findPendingNormalize(...)`
- `findPendingQueue(...)`
- `findRecentTitles(...)`

### 7.2 `processed_article`

Entidade:
- [ProcessedArticle.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/ProcessedArticle.java)

Campos relevantes:
- `rawArticleId`
- `slug`
- `link`
- `imageUrl`
- `source`
- `aiTitle`
- `aiDescription`
- `aiCategories`
- `aiTags`
- `entities`
- `entityStatus`
- `scope`
- `tone`
- `keyFact`
- `hasVictims`
- `victimCount`
- `locationState`
- `locationCity`

Repositorio:
- [ProcessedArticleRepository.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/ProcessedArticleRepository.java)

## 8. Pipeline de Conteudo

### 8.1 Captura de HTML

- [ArticleExtractor.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/ArticleExtractor.java)

Funcao:
- faz HTTP para baixar o HTML bruto do artigo
- nao faz enriquecimento editorial

### 8.2 Normalizacao

- [HtmlNormalizerService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/HtmlNormalizerService.java)

Funcao:
- pega `htmlContent`
- remove ruido
- extrai texto limpo
- grava em `rawContentText`
- pode limpar HTML bruto para economizar banco

Comportamento de laboratorio:
- para imediatamente se `app.lab.disable-workers=true`

### 8.3 Resumo com IA

- [AiSummaryWorker.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/AiSummaryWorker.java)
- [GroqSummarizer.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/GroqSummarizer.java)

Funcao:
- consome `RawArticle` normalizado
- chama o provedor de IA
- gera `ProcessedArticle`
- usa fallback editorial minimo em caso de erro definitivo

Comportamento de laboratorio:
- para se `app.lab.disable-workers=true`
- pode ser desligado separadamente por `app.ai.worker.enabled=false`

### 8.4 Entidades

- [EntityWorker.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/EntityWorker.java)
- [GroqEntityExtractor.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/GroqEntityExtractor.java)

Funcao:
- extrai `countries`, `people`, `organizations`, `topics`
- gera `aiTags`
- preenche `scope`, `tone`, `keyFact`, `location*`, `victim*`

Comportamento de laboratorio:
- para se `app.lab.disable-workers=true`

### 8.5 Narrativa

- [MapNarrativeWorker.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/MapNarrativeWorker.java)
- [NarrativeAnalyzer.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/NarrativeAnalyzer.java)

Funcao:
- analisa coocorrencia entre paises e temas
- gera conexoes e spread paths
- atualiza cache do mapa narrativo

Comportamento de laboratorio:
- para se `app.lab.disable-workers=true`

## 9. IA e Gestao de Tokens

### 9.1 Fachada operacional

- [GroqRateLimiter.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/GroqRateLimiter.java)

Papel:
- inicializar o pool de keys de IA
- entregar uma key elegivel por chamada
- registrar consumo de requests e tokens
- penalizar keys apos 429
- fechar ciclos de metricas

### 9.2 Pool real de keys

- [LlmKeyPool.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/pool/LlmKeyPool.java)
- [KeyMetrics.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/pool/KeyMetrics.java)

Politicas aplicadas:
- round robin com preferencia por key menos usada
- limite de requests por minuto
- limite de tokens por minuto
- intervalo minimo entre chamadas
- penalizacao temporaria apos 429
- historico por ciclo para dashboard

Quem usa o pool:
- [GroqSummarizer.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/GroqSummarizer.java)
- [GroqEntityExtractor.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/GroqEntityExtractor.java)
- [NarrativeAnalyzer.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/NarrativeAnalyzer.java)

Observabilidade:
- [TokenMetricsController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/TokenMetricsController.java)

## 10. APIs REST

### 10.1 Feed e operacao

Classe principal:
- [NewsController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/NewsController.java)

Grupos principais:
- `/api/news`
  Feed legado baseado em cache RSS e scraper.
- `/api/news/{slug}`
  Artigo legado em memoria.
- `/api/status`
  Estado dos caches.
- `/api/scraper/refresh`
  Dispara refresh manual do scraper.
- `/api/db/news`
  Feed vindo do banco processado.
- `/api/db/news/{slug}`
  Artigo processado individual.
- `/api/db/status`
  Estado das filas do banco.
- `/api/db/ingest`
  Dispara ingestao manual.

### 10.2 Saude operacional

- [HealthController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/HealthController.java)

Mostra:
- estado do banco
- fila de IA
- estado de entidades
- caches
- conectividade auxiliar

### 10.3 Administracao de runtime

- [RuntimeAdminController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RuntimeAdminController.java)

Endpoint:
- `/api/admin/runtime`

Mostra:
- laboratorio ativo
- delays e limites de ingestao
- config de RSS
- config de scraper
- config de scheduling
- schedulers ativos e suas configuracoes

### 10.4 Dashboard administrativo

- [TokenMetricsController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/TokenMetricsController.java)

Endpoints principais:
- `/api/admin/token-metrics`
- `/api/admin/source-stats`

### 10.5 Sitemap e servicos de produto

- [SitemapController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/SitemapController.java)
- [TrendingService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/TrendingService.java)
- [ClusterService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/ClusterService.java)
- [RankingService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/RankingService.java)
- [RecommendationService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/RecommendationService.java)
- [ExplicaService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/ExplicaService.java)
- [WeeklyDigestJob.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/WeeklyDigestJob.java)

## 11. Como ler o log sem se perder

Leia por prefixo e nao por ordem cronologica pura.

Prefixos mais importantes:
- `[RSS]`
  Cache e coleta RSS.
- `[SCRAPER]`
  Descoberta de links, progresso por portal e cache do scraper.
- `[INGESTION cid=...]`
  Ciclo principal que junta RSS e scraper.
- `[NORMALIZER]`
  Fila de limpeza de HTML.
- `[AI WORKER]`
  Resumos e geracao de `processed_article`.
- `[ENTITY-WORKER]`
  Entidades e tags.
- `[MAP-NARRATIVE]`
  Cache narrativo.
- `[KEY-POOL]`
  Uso, bloqueio e penalizacao de API keys.

Regra pratica:
- se o objetivo e entender coleta, siga apenas `[RSS]`, `[SCRAPER]` e `[INGESTION cid=...]`
- se o objetivo e entender enriquecimento, siga `[NORMALIZER]`, `[AI WORKER]` e `[ENTITY-WORKER]`
- se o objetivo e entender limites de IA, siga `[KEY-POOL]`

## 12. Modo Laboratorio

O modo laboratorio nao exige mais comentario de codigo.

Controle por configuracao:
- `app.lab.only-source=Metropoles`
  isola um portal ou fonte.
- `app.lab.disable-workers=true`
  impede que os workers paralelos continuem processando filas antigas.

Isso permite cenarios como:
- testar somente `Metropoles`
- manter RSS desligado apenas no ciclo de ingestao
- parar workers para observar so a coleta

Defaults atuais em `application.yml`:
- tudo habilitado
- todos os portais habilitados
- RSS habilitado
- workers habilitados

## 13. Como habilitar e desabilitar cada trilho

Tudo e controlado por [application.yml](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/resources/application.yml) ou por variaveis de ambiente equivalentes.

### 13.1 Deixar tudo habilitado

Configuracao recomendada para operacao completa:
- `app.ingestion.enable-rss: true`
- `app.rss.enabled: true`
- `app.scraper.active-portals: []`
- `app.lab.only-source: ""`
- `app.lab.disable-workers: false`
- `app.ai.worker.enabled: true`

Efeito:
- RSS entra no ciclo de ingestao
- scheduler do RSS roda normalmente
- todos os portais do scraper ficam ativos
- workers de normalizacao, IA, entidades e narrativa continuam rodando

### 13.2 Rodar somente um portal no scraper

Exemplo:
- `app.lab.only-source: "Metropoles"`

Efeito:
- o `ScraperOrchestrator` resolve a lista final para apenas esse portal
- os outros portais ficam fora do refresh do scraper
- RSS continua ligado se as flags de RSS estiverem ativas

### 13.3 Rodar apenas uma lista especifica de portais

Exemplo:
- `app.scraper.active-portals: ["Metropoles", "UOL", "G1 Globo"]`
- `app.lab.only-source: ""`

Efeito:
- o scraper roda apenas os portais da lista
- a lista so vale quando `app.lab.only-source` estiver vazio

### 13.4 Tirar o RSS somente da ingestao

Exemplo:
- `app.ingestion.enable-rss: false`
- `app.rss.enabled: true`

Efeito:
- o `NewsIngestionJob` nao mistura RSS com o scraper
- o `RssService` continua atualizando cache por scheduler
- endpoints legados que dependem do cache RSS continuam podendo responder

### 13.5 Desligar totalmente o RSS

Exemplo:
- `app.ingestion.enable-rss: false`
- `app.rss.enabled: false`

Efeito:
- RSS sai do ciclo de ingestao
- scheduler do RSS para de renovar cache
- o fluxo passa a depender apenas do scraper e do banco

### 13.6 Congelar os workers e observar so a coleta

Exemplo:
- `app.lab.disable-workers: true`

Efeito:
- `HtmlNormalizerService` para
- `AiSummaryWorker` para
- `EntityWorker` para
- `MapNarrativeWorker` para
- `WeeklyDigestJob` para
- coleta, RSS, scraper e ingestao continuam ativos

### 13.7 Desligar so o worker de resumo por IA

Exemplo:
- `app.ai.worker.enabled: false`
- `app.lab.disable-workers: false`

Efeito:
- o `AiSummaryWorker` nao processa novos artigos
- os outros workers continuam ativos, respeitando suas filas e estados

### 13.8 Ajustar tempos dos schedulers

Exemplos:
- `app.ingestion.fixed-delay-ms: 300000`
- `app.rss.fixed-delay-ms: 900000`
- `app.scraper.fixed-delay-ms: 900000`
- `app.ai.worker.delayMs: 40000`
- `app.scheduling.pool-size: 4`

Use [RuntimeAdminController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RuntimeAdminController.java) em `GET /api/admin/runtime` para confirmar o estado efetivo em runtime.
## 13. Padronizacao de logs

Foi iniciada a padronizacao de logs para ASCII, com objetivo de:
- reduzir mojibake
- facilitar grep e leitura operacional
- deixar os logs consistentes entre Windows, IntelliJ e terminal

Pontos ja tratados no fluxo novo:
- prefixos novos de ingestao
- mensagens novas de scheduler e runtime
- configuracao principal em ASCII

Ainda pode existir texto antigo legado em classes historicas que nao foram totalmente saneadas.

## 14. Mapa de chamadas classe a classe

### Boot e configuracao
- [GlobalPulseNewsApplication.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/GlobalPulseNewsApplication.java)
  Inicializa a aplicacao Spring.
- [AppRuntimeProperties.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/config/AppRuntimeProperties.java)
  Faz binding das configuracoes `app.*`.
- [SchedulingConfig.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/config/SchedulingConfig.java)
  Configura o scheduler pool.
- [WebConfig.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/config/WebConfig.java)
  Configs web gerais.
- [CacheConfig.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/CacheConfig.java)
  Cache manager.
- [CorsConfig.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/CorsConfig.java)
  CORS.

### Entrada
- [RssService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RssService.java)
  Coleta RSS e monta `NewsItem`.
- [PublisherPreviewService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/PublisherPreviewService.java)
  Resolve URL final e imagem para wrappers.
- [ScraperOrchestrator.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ScraperOrchestrator.java)
  Coleta por home e agrega os resultados.
- [NewsPortalScraper.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/NewsPortalScraper.java)
  Contrato de scraper.
- [ConfigurablePortalScraper.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ConfigurablePortalScraper.java)
  Implementacao generica.
- [InfoMoneyPortalScraper.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/InfoMoneyPortalScraper.java)
  Ajustes do InfoMoney.
- [ForbesBrasilPortalScraper.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ForbesBrasilPortalScraper.java)
  Ajustes da Forbes Brasil.
- [ScraperPortalSupport.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/ScraperPortalSupport.java)
  Motor de extracao e heuristicas.
- [NewsItem.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/NewsItem.java)
  DTO da noticia capturada.

### Ingestao e filas
- [NewsIngestionJob.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/NewsIngestionJob.java)
  Junta fontes e grava `raw_article`.
- [ArticleExtractor.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/ArticleExtractor.java)
  Baixa HTML.
- [HtmlNormalizerService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/HtmlNormalizerService.java)
  Limpa HTML e extrai texto.
- [AiSummaryWorker.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/AiSummaryWorker.java)
  Gera processados com IA.
- [EntityWorker.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/EntityWorker.java)
  Enriquece entidades.
- [MapNarrativeWorker.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/MapNarrativeWorker.java)
  Atualiza mapa narrativo.
- [WeeklyDigestJob.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/WeeklyDigestJob.java)
  Gera digest semanal.

### IA e tokens
- [GroqSummarizer.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/GroqSummarizer.java)
- [GroqEntityExtractor.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/GroqEntityExtractor.java)
- [NarrativeAnalyzer.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/NarrativeAnalyzer.java)
- [OllamaSummarizer.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/OllamaSummarizer.java)
- [OllamaEntityExtractor.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/OllamaEntityExtractor.java)
- [GroqRateLimiter.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/GroqRateLimiter.java)
- [LlmKeyPool.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/pool/LlmKeyPool.java)
- [KeyMetrics.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/pool/KeyMetrics.java)
- [GroqProvider.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/pool/GroqProvider.java)
- [LlmProvider.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/ai/pool/LlmProvider.java)

### Persistencia
- [RawArticle.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/RawArticle.java)
- [ProcessedArticle.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/ProcessedArticle.java)
- [RawArticleRepository.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/RawArticleRepository.java)
- [ProcessedArticleRepository.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/ProcessedArticleRepository.java)
- [NewsArticle.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/NewsArticle.java)
- [NewsArticleRepository.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/db/NewsArticleRepository.java)

### APIs de produto e admin
- [NewsController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/NewsController.java)
- [HealthController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/HealthController.java)
- [TokenMetricsController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/TokenMetricsController.java)
- [RuntimeAdminController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/RuntimeAdminController.java)
- [SitemapController.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/api/SitemapController.java)
- [TrendingService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/TrendingService.java)
- [ClusterService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/ClusterService.java)
- [RankingService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/RankingService.java)
- [RecommendationService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/RecommendationService.java)
- [ExplicaService.java](C:/projetos/CrivoNewsPortal/news_portal_v6/backend-spring-slug-sitemap/backend-spring/src/main/java/com/globalpulse/news/service/ExplicaService.java)


# CRIVO News — Guia de Deploy

## Pré-requisitos
- Conta GitHub (gratuita)
- Conta Fly.io (gratuita): https://fly.io/app/sign-up
- Conta Vercel (gratuita): https://vercel.com/signup
- flyctl instalado: https://fly.io/docs/hands-on/install-flyctl/

---

## PASSO 1 — Subir código no GitHub

```bash
# Na pasta do projeto
git init
git add .
git commit -m "CRIVO News v1.0"
git branch -M main

# Crie um repo em github.com/new (ex: crivo-news)
git remote add origin https://github.com/SEU_USUARIO/crivo-news.git
git push -u origin main
```

---

## PASSO 2 — Deploy do Backend no Fly.io

```bash
# Instala flyctl (Windows)
# Baixe em: https://fly.io/docs/hands-on/install-flyctl/

# Login
flyctl auth login

# Acesse a pasta do backend
cd backend-spring-slug-sitemap/backend-spring

# Cria o app (primeira vez)
flyctl launch --name crivo-news-backend --region gru --no-deploy

# Configura as variáveis secretas (NUNCA commite no git)
flyctl secrets set \
  SPRING_DATASOURCE_URL="jdbc:postgresql://db.SEU_REF.supabase.co:5432/postgres" \
  SPRING_DATASOURCE_PASSWORD="SUA_SENHA_SUPABASE" \
  AI_GROQ_APIKEY="gsk_SUA_NOVA_KEY_AQUI"

# Deploy!
flyctl deploy

# Verifica se subiu
flyctl status
flyctl logs
```

✅ Backend rodando em: https://crivo-news-backend.fly.dev

Teste: https://crivo-news-backend.fly.dev/api/db/health

---

## PASSO 3 — Deploy do Frontend no Vercel

### Opção A — Via interface (mais fácil):
1. Acesse https://vercel.com/new
2. Clique "Import Git Repository"
3. Selecione o repo `crivo-news`
4. Configure:
   - **Root Directory**: `news_portal-working/news_portal`
   - **Build Command**: `npm run build`
   - **Output Directory**: `dist/public`
5. Em **Environment Variables**, adicione:
   - `VITE_API_BASE` = `https://crivo-news-backend.fly.dev/api`
6. Clique **Deploy**

### Opção B — Via CLI:
```bash
npm i -g vercel
cd news_portal-working/news_portal
vercel --prod
```

✅ Frontend rodando em: https://crivo-news.vercel.app

---

## PASSO 4 — Domínio personalizado (opcional)

### No Vercel (frontend):
1. Settings → Domains → Add → `crivo.news`
2. Aponta DNS: `CNAME @ cname.vercel-dns.com`

### No Fly.io (backend):
```bash
flyctl certs create api.crivo.news
```
Aponta DNS: `CNAME api api.crivo.news.fly.dev`

Atualiza `.env.production`:
```
VITE_API_BASE=https://api.crivo.news/api
```

---

## PASSO 5 — Manutenção

```bash
# Ver logs do backend
flyctl logs -a crivo-news-backend

# Redeploy após mudança
flyctl deploy

# Escalar memória se precisar
flyctl scale memory 512 -a crivo-news-backend

# Ver uso de recursos
flyctl status -a crivo-news-backend
```

---

## Custos estimados

| Serviço | Custo |
|---|---|
| Vercel (frontend) | Grátis |
| Fly.io (backend) | Grátis (3 VMs free tier) |
| Supabase (banco) | Grátis (500MB) |
| Groq (IA) | ~$0/mês no free tier |
| **TOTAL** | **$0/mês** |

Se passar do free tier do Fly.io: ~$3-5/mês para 512MB RAM.

---

## Troubleshooting

**Backend não sobe:**
```bash
flyctl logs -a crivo-news-backend
# Verifique se os secrets estão configurados:
flyctl secrets list
```

**Frontend com "Load failed":**
- Verifique se VITE_API_BASE está correto nas env vars do Vercel
- Verifique CORS no backend (está configurado como "*")

**Supabase: connection timeout:**
- Free tier hiberna após inatividade — primeiro request é mais lento
- Considere upgrade para Supabase Pro ($25/mês) se for produção real

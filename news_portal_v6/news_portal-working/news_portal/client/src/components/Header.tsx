import { useState, useEffect, useRef } from "react";
import SearchBar from "@/components/SearchBar";
import BarraAgora from "@/components/BarraAgora";
import BarraCotacoes from "@/components/BarraCotacoes";
import { useLocation } from "wouter";
import { Menu, X } from "lucide-react";

interface HeaderProps {
  categories: string[];
  onCategorySelect: (category: string) => void;
  activeCategory: string;
  articles?: import("@/lib/api").NewsArticle[];
}

// â”€â”€ Neural Canvas: partÃ­culas + retÃ¢ngulos flutuantes â”€â”€â”€â”€â”€â”€â”€â”€â”€
function NeuralCanvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const CD = "10,21,32";
    const CA = "184,68,0";

    let rafId: number;
    let pts: Array<{
      x:number; y:number; vx:number; vy:number;
      r:number; accent:boolean; phi:number; omega:number;
    }> = [];
    let rects: Array<{
      x:number; y:number; w:number; h:number;
      vx:number; vy:number; rot:number; vr:number;
      alpha:number; accent:boolean;
    }> = [];

    let W = 0, H = 0;

    const build = (w: number, h: number) => {
      W = w; H = h;
      const n = Math.max(28, Math.floor((W * H) / 5500));
      pts = Array.from({ length: n }, () => ({
        x: Math.random()*W, y: Math.random()*H,
        vx: (Math.random()-.5)*.55, vy: (Math.random()-.5)*.42,
        r: 1.6 + Math.random()*2.4,
        accent: Math.random() < .10,
        phi: Math.random()*Math.PI*2,
        omega: .016 + Math.random()*.024,
      }));

      const nr = Math.max(5, Math.floor(W / 200));
      rects = Array.from({ length: nr }, () => ({
        x: Math.random()*W, y: Math.random()*H,
        w: 26 + Math.random()*52, h: 16 + Math.random()*36,
        vx: (Math.random()-.5)*.2, vy: (Math.random()-.5)*.16,
        rot: Math.random()*Math.PI*2, vr: (Math.random()-.5)*.003,
        alpha: .05 + Math.random()*.09,
        accent: Math.random() < .25,
      }));
    };

    const resize = () => {
      const banner = canvas.parentElement!;
      canvas.width  = banner.offsetWidth;
      canvas.height = banner.offsetHeight;
      build(canvas.width, canvas.height);
    };

    const frame = () => {
      ctx.clearRect(0, 0, W, H);

      // RetÃ¢ngulos flutuantes
      rects.forEach(r => {
        r.x = (r.x + r.vx + W) % W;
        r.y = (r.y + r.vy + H) % H;
        r.rot += r.vr;
        ctx.save();
        ctx.translate(r.x, r.y);
        ctx.rotate(r.rot);
        ctx.strokeStyle = r.accent
          ? `rgba(${CA},${r.alpha * 1.5})`
          : `rgba(${CD},${r.alpha})`;
        ctx.lineWidth = r.accent ? 1.1 : 0.65;
        ctx.strokeRect(-r.w/2, -r.h/2, r.w, r.h);
        if (r.accent) {
          ctx.globalAlpha = r.alpha * .45;
          ctx.beginPath();
          ctx.moveTo(-r.w/2, 0); ctx.lineTo(r.w/2, 0);
          ctx.moveTo(0, -r.h/2); ctx.lineTo(0, r.h/2);
          ctx.stroke();
          ctx.globalAlpha = 1;
        }
        ctx.restore();
      });

      // PartÃ­culas
      pts.forEach(p => {
        p.x = (p.x + p.vx + W) % W;
        p.y = (p.y + p.vy + H) % H;
        p.phi += p.omega;
      });

      const D = Math.min(W * .21, 160);
      for (let i = 0; i < pts.length; i++) {
        for (let j = i+1; j < pts.length; j++) {
          const a = pts[i], b = pts[j];
          const dx = a.x-b.x, dy = a.y-b.y;
          const d  = Math.sqrt(dx*dx + dy*dy);
          if (d > D) continue;
          const al  = (1 - d/D) * .55;
          const acc = a.accent || b.accent;
          ctx.beginPath();
          ctx.strokeStyle = acc ? `rgba(${CA},${al*.9})` : `rgba(${CD},${al})`;
          ctx.lineWidth = acc ? 1.0 : .65;
          ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y);
          ctx.stroke();
        }
      }

      pts.forEach(p => {
        const pulse = .8 + Math.sin(p.phi)*.2;
        const r     = p.r * pulse;
        const alpha = .6 + Math.sin(p.phi)*.25;
        if (p.accent) {
          const g = ctx.createRadialGradient(p.x,p.y,0, p.x,p.y, r*4.5);
          g.addColorStop(0, `rgba(${CA},${alpha*.85})`);
          g.addColorStop(1, `rgba(${CA},0)`);
          ctx.beginPath(); ctx.arc(p.x,p.y, r*4.5, 0, Math.PI*2);
          ctx.fillStyle = g; ctx.fill();
          ctx.beginPath(); ctx.arc(p.x,p.y, r, 0, Math.PI*2);
          ctx.fillStyle = `rgba(${CA},${alpha})`; ctx.fill();
        } else {
          ctx.beginPath(); ctx.arc(p.x,p.y, r, 0, Math.PI*2);
          ctx.fillStyle = `rgba(${CD},${alpha*.75})`; ctx.fill();
        }
      });

      rafId = requestAnimationFrame(frame);
    };

    resize();
    frame();

    const ro = new ResizeObserver(() => {
      cancelAnimationFrame(rafId);
      resize();
      frame();
    });
    ro.observe(canvas.parentElement!);

    return () => { cancelAnimationFrame(rafId); ro.disconnect(); };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      style={{ position:"absolute", inset:0, width:"100%", height:"100%" }}
    />
  );
}

// â”€â”€ Loop reveal letra a letra â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
function LoopReveal() {
  const elRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = elRef.current;
    if (!el) return;

    const parts = ["A notícia filtrada","·","Sem ruído","·","Sem exagero"];
    const STEP=38, HOLD=1800, FADE=400, PAUSE=500;
    const spans: HTMLSpanElement[] = [];

    parts.forEach(part => {
      if (part === "·") {
        const s = document.createElement("span");
        s.textContent = "·";
        s.style.cssText = "display:inline-block;opacity:0;color:#d05000;font-weight:800;margin:0 8px;";
        el.appendChild(s);
        spans.push(s);
      } else {
        Array.from(part).forEach(ch => {
          const s = document.createElement("span");
          s.textContent = ch === " " ? "\u00A0" : ch;
          s.style.cssText = "display:inline-block;opacity:0;transform:translateY(5px);";
          el.appendChild(s);
          spans.push(s);
        });
      }
    });

    let t: ReturnType<typeof setTimeout>;

    function loop() {
      spans.forEach(s => {
        s.style.transition = "none";
        s.style.opacity    = "0";
        s.style.transform  = "translateY(5px)";
      });
      spans.forEach((s, i) => {
        t = setTimeout(() => {
          s.style.transition = "opacity .18s ease,transform .18s ease";
          s.style.opacity    = "1";
          s.style.transform  = "translateY(0)";
        }, PAUSE + i * STEP);
      });
      const total = PAUSE + spans.length * STEP;
      t = setTimeout(() => {
        spans.forEach(s => {
          s.style.transition = `opacity ${FADE}ms ease`;
          s.style.opacity    = "0";
        });
      }, total + HOLD);
      t = setTimeout(loop, total + HOLD + FADE + 300);
    }

    t = setTimeout(loop, 1200);
    return () => clearTimeout(t);
  }, []);

  return (
    <div
      ref={elRef}
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontFamily: "'DM Sans', sans-serif",
        fontWeight: 600,
        fontSize: "clamp(10px, 1.4vw, 14px)",
        color: "#b84400",
        letterSpacing: "0.3px",
        minHeight: "20px",
        flexWrap: "wrap",
        gap: "2px",
      }}
    />
  );
}

// â”€â”€ Header principal â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
export default function Header({ categories, onCategorySelect, activeCategory, articles }: HeaderProps) {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [, navigate] = useLocation();

  const toCategoryPath = (category: string) => {
    if (category === "Todos") return "/";
    return "/" + category
      .toLowerCase()
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/\s+/g, "-");
  };

  const handleAnchorNavigate = (event: React.MouseEvent<HTMLAnchorElement>, href: string) => {
    event.preventDefault();
    navigate(href);
  };

  return (
    <>
      <link
        href="https://fonts.googleapis.com/css2?family=Playfair+Display:wght@700;900&family=DM+Sans:wght@300;400;500;600;700&display=swap"
        rel="stylesheet"
      />

      <style>{`
        @keyframes crivoGrad {
          0%   { background-position: 200% center; }
          100% { background-position: -200% center; }
        }
        @keyframes crivoDot {
          0%,100% { opacity:1; transform:scale(1); }
          50%      { opacity:0.28; transform:scale(0.75); }
        }
        @keyframes crivoFadeUp {
          from { opacity:0; transform:translateY(8px); }
          to   { opacity:1; transform:translateY(0); }
        }
        @keyframes crivoLine {
          0%   { background-position: 200% center; }
          100% { background-position: -200% center; }
        }
        .crivo-pill {
          padding: 6px 16px;
          border-radius: 20px;
          font-size: 12px;
          white-space: nowrap;
          border: 1.5px solid #c8c8d4;
          cursor: pointer;
          font-family: 'DM Sans', sans-serif;
          font-weight: 500;
          transition: all 0.2s ease;
          background: transparent;
          color: #3a3a4a;
        }
        .crivo-pill:hover {
          background: #fff3ec;
          color: #b84400;
          border-color: #b84400;
        }
        .crivo-pill.active {
          background: #b84400;
          color: #fff;
          border-color: #b84400;
        }
        /* Esconde scrollbar no Chrome/Safari */
        .categories-scroll::-webkit-scrollbar {
          display: none;
        }
        /* Mobile: banner menor, pills menores */
        @media (max-width: 640px) {
          .crivo-pill {
            padding: 5px 13px;
            font-size: 11.5px;
          }
        }
      `}</style>

      <header className="sticky top-0 z-50 shadow-sm" style={{ background: "#fff" }}>

        {/* â”€â”€ BANNER â”€â”€ */}
        <div style={{
          position: "relative",
          width: "100%",
          height: "clamp(100px, 14vw, 130px)",
          background: "#e8e9f0",
          overflow: "hidden",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}>
          <NeuralCanvas />

          {/* Vinheta lateral suave */}
          <div style={{
            position: "absolute", inset: 0, zIndex: 1, pointerEvents: "none",
            background: `
              linear-gradient(to right, rgba(232,233,240,0.88) 0%, transparent 12%, transparent 88%, rgba(232,233,240,0.88) 100%),
              radial-gradient(ellipse 60% 80% at 50% 50%, rgba(232,233,240,0.75) 15%, transparent 80%)
            `,
          }} />

          {/* ConteÃºdo */}
          <div style={{
            position: "relative", zIndex: 2,
            display: "flex", flexDirection: "column",
            alignItems: "center", gap: "10px",
            width: "100%", padding: "0 24px", textAlign: "center",
          }}>

            {/* Logo */}
            <div style={{
              display: "flex", alignItems: "baseline",
              justifyContent: "center", lineHeight: 1,
              filter: "drop-shadow(0 1px 4px rgba(0,0,0,0.15))",
            }}>
              <span style={{
                fontFamily: "'Playfair Display', serif",
                fontWeight: 900,
                fontSize: "clamp(34px, 4.8vw, 56px)",
                letterSpacing: "-1.5px",
                background: "linear-gradient(90deg,#0a1520 0%,#0a1520 10%,#a03e00 30%,#d96010 50%,#a03e00 70%,#0a1520 90%,#0a1520 100%)",
                backgroundSize: "250% auto",
                WebkitBackgroundClip: "text",
                WebkitTextFillColor: "transparent",
                backgroundClip: "text",
                animation: "crivoGrad 5s linear infinite",
              }}>CRIVO</span>

              <span style={{
                fontFamily: "'DM Sans', sans-serif",
                fontWeight: 700,
                fontSize: "clamp(20px, 3vw, 36px)",
                letterSpacing: "6px",
                textTransform: "uppercase",
                WebkitTextFillColor: "#1a1a2e",
                marginLeft: "10px",
              }}>News</span>

              <span style={{
                fontFamily: "'Playfair Display', serif",
                fontWeight: 900,
                fontSize: "clamp(34px, 4.8vw, 56px)",
                WebkitTextFillColor: "#b84400",
                display: "inline-block",
                animation: "crivoDot 2.5s ease-in-out infinite",
              }}>.</span>
            </div>

            {/* Slogan â€” imponente */}
            <p style={{
              fontFamily: "'DM Sans', sans-serif",
              fontWeight: 700,
              fontSize: "clamp(10px, 1.8vw, 17px)",
              color: "#111122",
              letterSpacing: "2.5px",
              textTransform: "uppercase",
              animation: "crivoFadeUp 0.9s ease 0.4s both",
              textShadow: "0 1px 3px rgba(232,233,240,0.9)",
            }}>
              {"A informação que separa o "}
              <em style={{ fontStyle:"normal", color:"#b84400", fontWeight:800 }}>fato</em>
              {" do ruído"}
            </p>

            {/* Subtexto loop */}
            <LoopReveal />
          </div>

          {/* Linha animada inferior */}
          <div style={{
            position: "absolute", bottom: 0, left: 0,
            width: "100%", height: "3px", zIndex: 3,
            background: "linear-gradient(90deg,transparent 0%,#b84400 22%,#e8621a 50%,#b84400 78%,transparent 100%)",
            backgroundSize: "300% auto",
            animation: "crivoLine 3s linear infinite",
          }} />
        </div>

        {/* â”€â”€ NAV LINKS â”€â”€ */}
        <div className="container">
          <div className="flex items-center justify-between py-3">
            <nav className="hidden md:flex items-center gap-8">
              <a href="/" onClick={(event) => { onCategorySelect("Todos"); handleAnchorNavigate(event, "/"); }} className="text-sm font-medium text-foreground hover:text-accent transition-colors">Inicio</a>
              <a href="/mapa" onClick={(event) => handleAnchorNavigate(event, "/mapa")} style={{ display:"inline-flex", alignItems:"center", gap:"5px" }} className="text-sm font-medium text-foreground hover:text-accent transition-colors">
                <span style={{ width:"6px", height:"6px", borderRadius:"50%", background:"#22c55e", display:"inline-block", animation:"liveDot 1.5s ease-in-out infinite" }}/>
                Mapa Global
              </a>
              <a href="/sobre" onClick={(event) => handleAnchorNavigate(event, "/sobre")} className="text-sm font-medium text-foreground hover:text-accent transition-colors">Sobre</a>
              <a href="/contato" onClick={(event) => handleAnchorNavigate(event, "/contato")} className="text-sm font-medium text-foreground hover:text-accent transition-colors">Contato</a>
            </nav>
            {/* Busca â€” sÃ³ desktop */}
            <div className="hidden md:block">
              <SearchBar />
            </div>
            <button
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
              className="md:hidden p-2 hover:bg-secondary rounded-lg transition-colors"
            >
              {mobileMenuOpen ? <X className="w-6 h-6" /> : <Menu className="w-6 h-6" />}
            </button>
          </div>

          {mobileMenuOpen && (
            <nav className="md:hidden pb-4 pt-2 border-t border-border space-y-3 animate-fade-in-up">
              <div style={{ paddingBottom: "4px" }}>
                <SearchBar />
              </div>
              <a href="/" onClick={(event) => { onCategorySelect("Todos"); setMobileMenuOpen(false); handleAnchorNavigate(event, "/"); }} className="block text-sm font-medium text-foreground hover:text-accent transition-colors">Inicio</a>
              <a href="/mapa" onClick={(event) => { setMobileMenuOpen(false); handleAnchorNavigate(event, "/mapa"); }} style={{ display:"flex", alignItems:"center", gap:"6px" }} className="block text-sm font-medium text-foreground hover:text-accent transition-colors">
                <span style={{ width:"6px", height:"6px", borderRadius:"50%", background:"#22c55e", display:"inline-block" }}/>
                Mapa Global
              </a>
              <a href="/sobre" onClick={(event) => { setMobileMenuOpen(false); handleAnchorNavigate(event, "/sobre"); }} className="block text-sm font-medium text-foreground hover:text-accent transition-colors">Sobre</a>
              <a href="/contato" onClick={(event) => { setMobileMenuOpen(false); handleAnchorNavigate(event, "/contato"); }} className="block text-sm font-medium text-foreground hover:text-accent transition-colors">Contato</a>
            </nav>
          )}
        </div>

        {/* â”€â”€ CATEGORIAS: scroll horizontal no mobile, wrap no desktop â”€â”€ */}
        <div style={{
          background: "#fff",
          boxShadow: "0 1px 0 #e0e0e8, 0 2px 8px rgba(0,0,0,0.06)",
          padding: "10px 0",
        }}>
          <div style={{
            display: "flex",
            gap: "6px",
            // Mobile: scroll horizontal sem quebrar linha
            // Desktop: centralizado com wrap
            overflowX: "auto",
            flexWrap: "nowrap",
            justifyContent: "flex-start",
            padding: "2px 16px",
            // Esconde scrollbar visualmente mas mantÃ©m funcional
            scrollbarWidth: "none",
            msOverflowStyle: "none",
          }}
          className="categories-scroll"
          >
            {/* BotÃ£o Home fixo â€” sempre o primeiro */}
            <a
              href="/"
              onClick={(event) => { onCategorySelect("Todos"); setMobileMenuOpen(false); handleAnchorNavigate(event, "/"); }}
              className={`crivo-pill${activeCategory === "Todos" ? " active" : ""}`}
              style={{ flexShrink: 0, display: "inline-flex", alignItems: "center", gap: "5px", textDecoration: "none" }}
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>
                <polyline points="9 22 9 12 15 12 15 22"/>
              </svg>
              Home
            </a>

            {/* Separador */}
            <div style={{ width: "1px", height: "20px", background: "#e0e0ea", flexShrink: 0, alignSelf: "center" }} />

            {/* Categorias â€” filtra "Todos" para nÃ£o duplicar */}
            {categories.filter(c => c !== "Todos").map((category) => {
              const href = toCategoryPath(category);
              return (
                <a
                  key={category}
                  href={href}
                  onClick={(event) => {
                    onCategorySelect(category);
                    setMobileMenuOpen(false);
                    handleAnchorNavigate(event, href);
                  }}
                  className={`crivo-pill${activeCategory === category ? " active" : ""}`}
                  style={{ flexShrink: 0, textDecoration: "none" }}
                >
                  {category}
                </a>
              );
            })}
          </div>
        </div>

        {/* â”€â”€ BARRA AGORA â”€â”€ */}
        <BarraAgora articles={articles} />

        {/* â”€â”€ BARRA MERCADO â”€â”€ */}
        <BarraCotacoes />

      </header>
    </>
  );
}










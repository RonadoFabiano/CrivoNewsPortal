interface Props {
  title: string;
  url: string;
}

export default function BotoesCompartilhar({ title, url }: Props) {
  const encoded = encodeURIComponent(url);
  const text = encodeURIComponent(title + " — CRIVO News");

  const btns = [
    {
      label: "WhatsApp",
      color: "#25D366",
      href: `https://wa.me/?text=${text}%20${encoded}`,
      icon: (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor">
          <path d="M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51-.173-.008-.371-.01-.57-.01-.198 0-.52.074-.792.372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709.306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289.173-1.413-.074-.124-.272-.198-.57-.347z"/>
          <path d="M12 0C5.373 0 0 5.373 0 12c0 2.116.555 4.103 1.523 5.824L.057 23.886a.5.5 0 0 0 .611.61l6.222-1.635A11.945 11.945 0 0 0 12 24c6.627 0 12-5.373 12-12S18.627 0 12 0zm0 22c-1.886 0-3.653-.5-5.187-1.373l-.372-.214-3.854 1.013 1.032-3.762-.233-.387A9.944 9.944 0 0 1 2 12C2 6.477 6.477 2 12 2s10 4.477 10 10-4.477 10-10 10z"/>
        </svg>
      ),
    },
    {
      label: "Telegram",
      color: "#229ED9",
      href: `https://t.me/share/url?url=${encoded}&text=${text}`,
      icon: (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor">
          <path d="M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.962 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z"/>
        </svg>
      ),
    },
    {
      label: "X",
      color: "#000",
      href: `https://twitter.com/intent/tweet?text=${text}&url=${encoded}`,
      icon: (
        <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
          <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-4.714-6.231-5.401 6.231H2.746l7.73-8.835L1.254 2.25H8.08l4.259 5.631L18.244 2.25zm-1.161 17.52h1.833L7.084 4.126H5.117L17.083 19.77z"/>
        </svg>
      ),
    },
  ];

  return (
    <div style={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" }}>
      <span style={{ fontSize: "11px", color: "#aaa", fontWeight: 600, letterSpacing: "0.3px" }}>
        COMPARTILHAR
      </span>
      {btns.map(b => (
        <a
          key={b.label}
          href={b.href}
          target="_blank"
          rel="noopener noreferrer"
          title={`Compartilhar no ${b.label}`}
          style={{
            display: "inline-flex", alignItems: "center", gap: "5px",
            padding: "5px 10px",
            borderRadius: "6px",
            border: `1.5px solid ${b.color}33`,
            background: `${b.color}0d`,
            color: b.color,
            fontSize: "11px", fontWeight: 700,
            textDecoration: "none",
            transition: "all 0.15s",
          }}
          onMouseEnter={e => {
            (e.currentTarget as HTMLAnchorElement).style.background = b.color;
            (e.currentTarget as HTMLAnchorElement).style.color = "#fff";
          }}
          onMouseLeave={e => {
            (e.currentTarget as HTMLAnchorElement).style.background = `${b.color}0d`;
            (e.currentTarget as HTMLAnchorElement).style.color = b.color;
          }}
        >
          {b.icon}
          {b.label}
        </a>
      ))}
    </div>
  );
}

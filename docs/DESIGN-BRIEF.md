# E-Com Bank - Design Brief

## 1. Design Philosophy

Modern fintech, not corporate banking. Reference points: Linear, Stripe, Mercury, Ramp. Clean, confident, high information density without clutter. The aesthetic should feel like a 2026 product built by a small team, not a 2010 enterprise portal.

Three principles:
1. Calm density - lots of information, but airy and readable.
2. Intentional motion - every animation earns its place; no decoration.
3. Depth through layering - soft shadows, subtle borders, one strong accent per screen.

## 2. Color Palette

### Light Mode

| Token | Hex | Use |
|---|---|---|
| --surface-0 | #ffffff | Cards, main content |
| --surface-1 | #f8fafc | Page background |
| --surface-2 | #f1f5f9 | Hover, subtle fills |
| --border-subtle | rgba(15, 23, 42, 0.06) | Card borders |
| --text-primary | #0f172a | Headings, body |
| --text-secondary | #64748b | Labels, metadata |
| --text-tertiary | #94a3b8 | Disabled, hints |

### Dark Mode

| Token | Hex | Use |
|---|---|---|
| --surface-0 | #0b1220 | Cards |
| --surface-1 | #070c17 | Page background |
| --surface-2 | #141c2e | Hover |
| --border-subtle | rgba(148, 163, 184, 0.08) | Card borders |
| --text-primary | #e2e8f0 | Body |
| --text-secondary | #94a3b8 | Labels |
| --text-tertiary | #475569 | Hints |

### Brand & Semantic

| Token | Light | Dark | Use |
|---|---|---|---|
| --brand-primary | #4f46e5 | #6366f1 | Primary CTAs, active nav |
| --brand-primary-hover | #4338ca | #4e46e5 | Hover state |
| --brand-accent | #06b6d4 | #22d3ee | Highlights, charts |
| --success | #10b981 | #34d399 | COMPLETED, positive deltas |
| --warning | #f59e0b | #fbbf24 | COMPENSATING, teller limit |
| --danger | #f43f5e | #fb7185 | FAILED, negative deltas |
| --info | #3b82f6 | #60a5fa | Neutral badges |

### Signature Gradient

Used in login hero, KPI hero cards, active nav glow.

--gradient-brand: linear-gradient(135deg, #4f46e5 0%, #6366f1 40%, #06b6d4 100%);
--gradient-surface: linear-gradient(180deg, #0f172a 0%, #111a2e 100%);

### On-dark interactive tokens (login hero, 5.7)

The hero canvas is permanently dark in both modes, so these carry dark-column values in light mode: `--focus-ring` (`0 0 0 2px var(--on-dark), 0 0 0 5px var(--brand-primary)` — a brand-primary ring alone is only ~2.2:1 on the glass card, below the 3:1 WCAG 2.4.11 requires), `--danger-on-dark` (`#fb7185`, for the error banner's warning icon — measured 4.2:1 on the `--danger-soft` tint against 3.1:1 for light mode's `#f43f5e`; the banner's text is `--on-dark`, since neither rose clears 4.5:1 on that tint and white measures 11.4:1), and `--gradient-brand-cta` (`#4f46e5` -> `#6366f1` — white text on `--gradient-brand` measures 2.4:1 at its cyan end, so text-bearing brand fills use this shorter ramp).

---

## 3. Typography

Fonts (via Google Fonts):
- UI: Inter - weights 400, 500, 600, 700
- Mono: JetBrains Mono - weights 400, 500 (for account IDs, transaction IDs, amounts)

### Scale

| Role | Size | Weight | Tracking |
|---|---|---|---|
| Page title (h2) | 24px | 600 | -0.02em |
| Section title (h3) | 18px | 600 | -0.01em |
| Card title | 14px | 600 | 0 |
| Body | 14px | 400 | 0 |
| Small / label | 12px | 500 | 0.02em |
| KPI number | 32px | 700 | -0.03em |
| Table cell | 13px | 400/500 | 0 |
| Mono values | 13px | 500 | 0 |

Rule: Numbers in KPIs and amounts always use font-variant-numeric: tabular-nums for alignment.

## 4. Layout & Spacing

### Grid
- Sidebar: 240px expanded / 64px collapsed
- Content max-width: 1440px
- Content padding: 32px desktop / 24px tablet / 16px mobile
- Card gap: 20px
- Inner card padding: 20px

### Radii
- Cards: 14px
- Buttons: 10px
- Inputs: 10px
- Badges: 6px
- Avatar: full circle

### Shadows
- Card resting: 0 1px 2px rgba(15, 23, 42, 0.04), 0 0 0 1px var(--border-subtle)
- Card hover: 0 4px 12px rgba(15, 23, 42, 0.06), 0 0 0 1px var(--border-subtle)
- Elevated: 0 12px 32px rgba(15, 23, 42, 0.12)

### Spacing Scale
4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48 / 64 - use consistently.

## 5. Component Specifications

### 5.1 Sidebar
- Background: linear-gradient(180deg, #0f172a 0%, #111a2e 100%) - permanent dark, both modes
- Logo block: gradient mark EC in 32px rounded square
- Nav items: 40px height, 10px radius, 12px horizontal padding
- Active: background rgba(99, 102, 241, 0.15) + 3px left accent bar gradient #6366f1 to #06b6d4
- Hover: rgba(255,255,255,0.04)
- Group headers: 11px uppercase, letter-spacing 0.08em

### 5.2 Topbar
- Background: --surface-0 with 1px bottom border
- Logo + brand: 16px semibold
- Right cluster: bell (with badge), theme toggle, user chip
- User chip: avatar with 2px gradient ring, role p-tag, logout icon

### 5.3 KPI Cards
- Height: 110px
- Layout: icon (top-left, muted) then label (small caps) then value (32px tabular-nums) then trend (small, colored)
- Left border: 3px gradient var(--brand-primary) to var(--brand-accent)
- Hover: translateY(-2px) + card-hover shadow

### 5.4 Data Tables
- Header: 40px, background --surface-1, uppercase 11px labels
- Row: 48px, hover --surface-2
- Cell padding: 12px 16px
- Numeric: right-aligned, tabular-nums, JetBrains Mono for IDs
- Pagination: pill buttons, active uses --brand-primary

### 5.5 Status Badges
- 6px radius, 4px 10px padding, 11px weight 600 uppercase, letter-spacing 0.04em
- Colors: 12% opacity background + solid text per semantic token

### 5.6 Buttons
- Primary: --brand-primary bg, white text, 10px radius
- Secondary: transparent, --border-subtle border
- Danger: --danger bg
- Warn: --warning bg, dark text
- All: 200ms ease-out

### 5.7 Login Page
- Full-screen gradient: linear-gradient(135deg, #0f172a 0%, #1e1b4b 40%, #312e81 100%)
- Card: 420px wide, glassy - rgba(255,255,255,0.06), backdrop-filter blur(20px), 1px border rgba(255,255,255,0.08)
- Logo: 48px gradient square
- Title: 28px semibold white
- Subtitle: 14px rgba(255,255,255,0.6)
- CTA: full-width, gradient, 44px height
- Background: two radial gradient blobs, filter blur(100px)

### 5.8 Charts
- Line fills: gradient rgba(79,70,229,0.2) to transparent
- Grid: --border-subtle
- Axis labels: 12px --text-secondary
- Tooltip: dark card, 8px radius, mono numbers
- Animation: 600ms easeOutQuart, 40ms stagger

### 5.9 Loading States
- Skeleton screens replace spinners
- Skeleton: linear-gradient(90deg, --surface-2, --surface-1, --surface-2), 1.5s infinite

### 5.10 Empty States
- Centered, icon 48px in muted circle, headline 16px semibold, subtext 13px muted, optional CTA

## 6. Motion
- Hover: 200ms cubic-bezier(0.16, 1, 0.3, 1)
- Page transition: fade-in 200ms
- Card entrance: 40ms stagger, translateY(8px) to 0
- KPI count-up: 800ms ease-out on first load only
- Badge change: 300ms color transition
- Toast: slide from right, 300ms

## 7. Dark Mode Parity
Every token has a light and dark value. Dark mode via .app-dark on <html>. No hard-coded hex outside _tokens.scss.

## 8. Signature Moments
1. Gradient ring avatar (2px border)
2. Animated KPI numbers on first render
3. Glassy Command Center header - backdrop-filter blur(12px) + gradient underline

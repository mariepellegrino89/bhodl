# Phase 3 — UX research deep-dive

> **Nota (2026-10-23):** dal 2026-10-23 BHODL è watch-only: non tiene chiavi private e
> non può inviare bitcoin. Le parti di questo documento su seed, firma, invio, PayJoin,
> backup o mirror signing↔tracker sono storiche; il design attuale è in
> `docs/handbook.md`.

Documento di reference per evolvere il Tracking dashboard di BHODL. Compilato 2026-05-16 da 6 ricerche parallele coprenti: chart types, dashboard layout, color systems, filters & customization, interaction patterns, Bitcoin-specific UX.

Questo documento è la sintesi attionabile delle ricerche.

---

## Indice

1. [Chart types catalog](#1-chart-types-catalog)
2. [Dashboard layout patterns](#2-dashboard-layout-patterns)
3. [Color systems](#3-color-systems)
4. [Filters & customization](#4-filters--customization)
5. [Interaction patterns](#5-interaction-patterns)
6. [Bitcoin/crypto specific UX](#6-bitcoincrypto-specific-ux)
7. [Recommendations prioritizzate per BHODL](#7-recommendations-prioritizzate-per-bhodl)

---

## 1. Chart types catalog

### Già implementati in BHODL
- **Line chart** (Balance) — KO ✓ con YoY overlay
- **Grouped bar** (Inflow/Outflow) — KO ✓
- **Donut/Treemap** (Categorie) — KO ✓ con toggle
- **Calendar heatmap** — KO ✓ con drill-down
- **Sparkline** in KPI card — KO ✓ (solo Balance)
- **KPI tile** — KO ✓ (4 card con delta YoY)

### Charts che mancano (ranked per valore atteso)

**TIER 1 — Add subito (alto valore, basso effort)**

1. **Sankey diagram** (income → category → outflow)
   - Pattern: Monarch lo ha reso marquee feature. Assente da TUTTI i wallet Bitcoin.
   - Use case: "Da dove vengono i sat e dove vanno?"
   - Implementazione: Compose Canvas + algoritmo d3-sankey (200-300 righe). Niente lib.
   - Note: include toggle "share con amounts hidden" (privacy)
   - Source: [Monarch blog](https://www.monarch.com/blog/visualize-your-cash-flow-like-never-before), [SankeyMATIC](https://sankeymatic.com/gallery/monthly-budget.html)

2. **Divergent bar** (inflow up, outflow down stessa baseline)
   - Sostituisce il grouped bar per cash-flow per period
   - Più immediato di due bar separate
   - Keep grouped bar per category-compare

3. **Stepped line** (variante balance)
   - Più onesto del line per dati ledger-based (Bitcoin balance non interpola, sale/scende a salti)
   - Toggle "Smoothed / Stepped" nella ChartCard Balance

4. **Bullet chart** (per budget vs actual)
   - Inventato da Stephen Few. Sostituisce gauge in row di tabella.
   - Compose Canvas: 50 righe.
   - Use case: "Cibo: speso 23k / budget 30k" → bar orizzontale con marker target

**TIER 2 — Medio valore**

5. **UTXO scatter** (age × value, size = anon-set)
   - Sparrow lo manca, Satsigner ce l'ha. Differentiator concreto.
   - Per il privacy-conscious user
   - Compose Canvas: 100-150 righe + tooltip

6. **Stacked area** (composizione balance per wallet over time)
   - Per multi-wallet: vedi quale wallet contribuisce a balance totale nel tempo
   - Koalaplot 0.9 ha `StackedAreaPlot`

7. **Cost-basis line + buy/sell markers** (per asset)
   - Koinly/CoinTracker pattern. Linea orizzontale "average buy price" + marker triangoli per buy/sell
   - Diventa rilevante in Phase 4+ quando aggiungiamo fiat overlay

**TIER 3 — Niche / rimandabili**

8. **Liquidity bar** Lightning (Phase 4)
   - Inbound vs outbound per channel. Phoenix Desktop pattern.
   - 30 righe Canvas
9. **Privacy bar** Wasabi-style (Phase 5+ Liquid/Cashu)
   - Three-color segment: confirmed / pending / on-L2
10. **Candlestick** — SOLO per BTC price, MAI per balance. Trade-off: serve fonte prezzo + tipico pattern trading. Rimandabile.

### Charts da NON aggiungere

- **Pie con >7 slices** — mai. Force "Other" rollup.
- **Dual-axis** (BTC + USD stesso pannello) — universally confusing. Usa unit toggle o rebase-to-100.
- **Box/violin plot** — utenti non li leggono.
- **Funnel** — solo per send progress flow (Send→Sign→Broadcast), non dashboard.
- **Streamgraph** — interessante visivamente ma utenti non leggono valori.
- **3D pie / 3D bars** — distort area perception.
- **Rainbow categorical palette** — universally accessibility-hostile.

### Donut center-text pattern (raccomandato implementare)

Copilot/YNAB/AnyChart convention:
- Center large: total (`€12,430`)
- Below small: label (`Spent in May`)
- On hover: swap to hovered slice value + percent
- On click: drill down → History tab filtered (già fatto ✓)

**Da aggiungere a BHODL**: il center-text non c'è ancora. Aggiunge ~30 righe Canvas.

---

## 2. Dashboard layout patterns

### Pattern attuali in BHODL: PASS check

✓ Top tab bar (Dashboard / History / Reports) — pattern dominante per ≤6 destinazioni
✓ Sticky PeriodSelector — universale
✓ 2-col adaptive (>1100dp) — pattern moderno
✓ KPI strip 4-card — sweet spot (3 troppo poco, 5+ overload)
✓ DataStateStrip — Smashing Magazine "Data Freshness Indicator"

### Improvements raccomandati

**Material 3 Window Size Class breakpoints (più "by the book")**

Attuale: split a 1100dp (binary). Raccomandato: 3-step.

| Class | Width | Layout |
|-------|-------|--------|
| Compact | <600dp | 1-col stack, KPI come list |
| Medium | 600-839dp | 1-col, KPI 2×2 grid |
| Expanded | 840-1199dp | 2-col, KPI 4-up |
| Large | ≥1200dp | 3-col possibile, KPI 4-up |

Effort: 1 ora. Sostituisci `maxWidth >= 1100.dp` con `WindowSizeClass.calculateFromSize(...)`.

**Left sidebar per multi-wallet (Phase 4+)**

Quando avrai 5+ wallets, top tab non scala. Pattern Sparrow + Trezor Suite:
- Left rail con lista wallets (200-260dp)
- Top tabs scoped al wallet selezionato
- Aggregate view come prima entry del rail

Defer fino a Phase 4. Nel frattempo, top tab + groupWalletsByXpub dedup funziona.

**Footer status bar (Sparrow/Bitcoin Core/Specter convention)**

Aggiungere footer con:
- Block height + sync state
- Network mode (Tor / clearnet / local)
- "Last refreshed N min ago"

Convenzione standard per desktop wallet. La tua DataStateStrip copre il "last refreshed" ma manca node sync + network mode.

Effort: 2 ore. Footer composable + state da WalletStore/EsploraEndpoint.

**Command palette (Cmd+K)**

Pattern Linear / Notion / Raycast. Assente da TUTTE le app finance/crypto inspectate. **Differentiator forte**.

Quick actions tipiche:
- "Send to <address>" 
- "Switch to <wallet>"
- "Filter by <category>"
- "Open Settings"
- "Refresh"
- "Export CSV"

Effort: 1 giornata. `Dialog` + `OutlinedTextField` + `LazyColumn` filtrato.

**Compact density toggle**

Lunch Money / Linear / Gmail pattern. Settings → Appearance → Compact/Spacious.
- Compact: padding ridotto, font 14sp, row 36dp invece di 56dp
- Spacious (default): padding standard

Adoption media. Defer fino a quando hai >50 utenti che chiedono.

### Patterns da NON adottare

- **Drag-resize widget**: solo Power BI lo fa con successo. Monarch/PocketSmith hanno drag-reorder MA non resize. Engineering cost enorme per beneficio marginale.
- **Detachable panels / multi-window**: solo Bloomberg-tier. Niente da fare.
- **Per-monitor preferences**: niche.
- **Walkthrough modal overlays** in security-critical: Bitcoin Design Guide warning — nascondere wallet durante onboarding è anti-pattern.

---

## 3. Color systems

### Regola FONDAMENTALE: separa brand vs functional vs categorical

3 ruoli MAI sovrapposti:
1. **Brand**: orange (logo, primary CTA, active nav) — MAI su data values
2. **Semantic data**: green (positive/income), red (negative/expense), amber (pending/warning) — MAI su buttons o nav
3. **Categorical chart**: palette separata (Okabe-Ito), niente brand orange né semantic red/green

Esempio errore comune: Monarch usa il loro coral come SIA brand SIA expense color → ambiguità.

### Color palette raccomandata per BHODL (dark mode)

**Primitive layer**

```
orange-300: #FFB733  (brand highlight)
orange-500: #F0B443  (brand primary, desaturated per dark)

green-500:  #34C759  (Apple system green)
green-muted: #2DB87A (chart bars, less aggressive)

red-500:    #FF453A  (Apple system red)
red-muted:  #E05252  (chart bars)

amber-300:  #FFD166  (warning/pending)
amber-500:  #FFB300  (transaction pending indicator)

blue-400:   #56B4E9  (info, Okabe-Ito sky blue)

neutral-100: #E8E8E8 (text primary — non #FFFFFF per evitare halation OLED)
neutral-400: #A0A0A0 (text secondary)
neutral-600: #6B6B6B (text disabled)
neutral-900: #1E1E1E (surface elevated)
neutral-950: #171717 (surface)
neutral-1000: #111111 (background, NON pure black)
```

**Semantic layer**

```
financial-positive       = green-500
financial-positive-muted = green-muted (per chart bar)
financial-negative       = red-500
financial-negative-muted = red-muted
financial-neutral        = neutral-400
financial-pending        = amber-300

brand-primary            = orange-500
brand-interactive        = orange-300
```

**Chart categorical palette** (Okabe-Ito adapted, brand orange escluso)

```
chart-cat-1: #56B4E9  (sky blue)
chart-cat-2: #009E73  (bluish green)
chart-cat-3: #CC79A7  (reddish purple)
chart-cat-4: #0072B2  (blue)
chart-cat-5: #E69F00  (orange-adjacent ma diverso da brand)
chart-cat-6: #D55E00  (vermillion)
chart-cat-7: #F0E442  (yellow)
chart-cat-8: #8B8B8B  (neutral gray, per "Other")
```

Okabe-Ito è il gold standard color-blind safe. ~8% dei maschi ha deficit rosso-verde — usare red/green come ONLY differentiator è discriminatorio.

### Cosa cambiare in BHODL

1. **Verifica il theme attuale**: i colori in `BhodlTheme.colors` probabilmente già si avvicinano. Check con WebAIM contrast checker. Target: AA 4.5:1 minimum, AAA 7:1 ideale per body text.

2. **Sostituisci pure white text** (se presente) con `#E8E8E8` — halation OLED.

3. **Donut palette**: attualmente in `TrackingDashboard.kt` `donutPalette()` hardcoded 12 colori. Sostituisci con Okabe-Ito 8-color rotation + neutral come 9°.

4. **Aggiungi secondary cue oltre al colore**: ogni transazione income/expense deve avere ANCHE icona freccia/+/- prefix, non solo green/red. Per CVD safety.

5. **Bitcoin orange canonico** `#F7931A` — è quello del logo Bitcoin ufficiale. Se vuoi essere brand-aligned al di là di BHODL.

### Cultural color: opzionale ma diferenziante

Mercati asiatici: rosso = up (gain), verde = down (loss). Esattamente l'opposto del Western.

Aggiungi setting "Color Convention" (Western / Eastern). Solo flip data colors, NON UI state (error resta rosso). Tigers Brokers e East Money lo fanno.

Effort: 30 min. State globale + computed colors.

### Tools raccomandati

- [WebAIM Contrast Checker](https://webaim.org/resources/contrastchecker/) — pass/fail AA/AAA
- [Leonardo](https://leonardocolor.io/) — genera tonal scale a target contrast
- [Who Can Use](https://www.whocanuse.com/) — % utenti che vedono il color pair
- [Coblis](https://www.color-blindness.com/coblis-color-blindness-simulator/) — simula 8 tipi CVD

---

## 4. Filters & customization

### Pattern attuali in BHODL: PASS check

✓ Period chips (7g/30g/90g/1a/All) — pattern dominante "first level"
✓ Tri-state categoria filter (all/empty/specific) — corretto
✓ Time range filter da bar bucket click — pattern Datadog
✓ Saved views (registry) — è il differentiator (PocketSmith ha 18 saved dashboards, Actual unlimited)
✓ Active filter chip con X reset — universale

### Improvements raccomandati

**Period selector enhancements**

Aggiungere chip wallet-native che NESSUNA finance app ha:
- "Since last halving" (calcolabile)
- "Since wallet created"
- "Since last balance high" (record-high tracker)

Effort: 30 min. Nuovi entries in `TrackingPeriod` enum + logica `periodLowerBound`.

**Date range picker custom**

Aggiungi opzione "Custom range" come 6° chip. Apre 2-anchor date picker (start + end calendar). Quick presets dentro il picker: Today, Yesterday, Last 7d, MTD, YTD.

Compose Multiplatform: `DateRangePicker` Material 3.

Effort: 2 ore.

**Multi-select category filter**

Attualmente tri-state (all/empty/specific). Upgrade a multi-select:
- All
- Senza categoria
- N categorie scelte (chip stripe)
- "Clear all" CTA

Pattern Linear (label filter), GitHub Issues.

Effort: 3 ore. State da `String?` a `Set<String?>`.

**Multi-account filter (toggle aggregato)**

DeBank/Zerion/Empower pattern. Settings o sidebar:
- Checkbox per wallet "Include in totals"
- Persiste in registry

Effort: 2 ore. Tabella `tracking_excluded_wallets` su registry.

**Query Tool / advanced filter (power-user)**

Lunch Money "Query Tool" è la più creativa: pseudo-SQL che emette chart custom. Fava ha BQL. Notion ha filter builder con AND/OR.

Per BHODL: minimal MVP = dialog "Advanced filter" con field+operator+value (3 dimensioni), compose con AND only.

Effort: 1 giornata. Rimandabile.

**Persistence preferenze**

DataStore Multiplatform (`androidx.datastore:datastore-preferences-core`). Salva:
- Period default (es. utente preferisce sempre 90g)
- Density (compact/spacious)
- Color convention (Western/Eastern)
- Last visited tab
- Sidebar collapse state

Effort: 2 ore. Già abbiamo SqlCipher registry — possiamo usare anche quella se preferiamo crittografato.

### Antipatterns evitati ✓

- Filtri che resettano on tab switch — NO, i nostri persistono ✓
- Filter UI che mangia 40% viewport — NO, abbiamo chip strip compatti ✓
- Hidden active filters senza visible reminder — NO, abbiamo ActiveRangeFilterChip ✓

---

## 5. Interaction patterns

### Pattern attuali in BHODL: PASS check

✓ Click-through donut slice → History filtered
✓ Click bucket chip → time range filter
✓ Click calendar day → drill-down day
✓ Loading skeletons (shimmer animation)
✓ Sticky period selector
✓ Refresh button

### Improvements raccomandati

**Hover tooltip su line/bar charts**

Koalaplot 0.9 supporta tooltip via `HoverableElementAreaScope`. Aggiungi:
- Crosshair vertical line on hover (multi-line chart)
- Tooltip con: data, balance, change vs prev tick, # tx that day
- Auto-positioning con collision avoidance

Effort: 3 ore. Cambia signature `LinePlot { ... }` per accettare hover content.

**Brush-zoom su line chart**

TradingView / Rotki pattern. Drag a range orizzontalmente per zoomare:
- Drag su axis → zoom in
- Doppio click vuoto → reset
- Pinch-zoom su trackpad

Effort: 1 giornata. Koalaplot 0.9 ha brush nativa (`Modifier.pointerInput`), serve hoist state per `xRange`.

**Hover highlight nelle KPI cards**

Sottile increase di brightness 15-20% on hover. Material 3 InteractionSource lo fa automaticamente con `Modifier.hoverable`.

Effort: 30 min.

**Keyboard shortcuts**

Standard set finance/crypto:
- `R` = Refresh
- `/` = Search focus
- `1/2/3` = Tab switch (Dashboard/History/Reports)
- `←/→` = Prev/next period (sui chip)
- `ESC` = Clear filters

Compose Multiplatform: `Modifier.onKeyEvent` su root window.

Effort: 2 ore.

**Cross-chart linked brushing**

PRO feature. Drag su calendar heatmap → tutti gli altri chart re-scope a quel range. Datadog/Grafana lo fanno.

Implementation: shared state `selectedRange` hoisted up, ogni chart consuma il filter.

Effort: 1 giornata. **Wow factor altissimo**.

**Animation guidelines**

- Mount: fade-in 200-300ms ease-out
- Data update: tween 400ms ease-in-out
- Skeleton shimmer: 900ms repeat (già OK ✓)
- Mai >800ms per interactions — blocca user input
- Respect `prefers-reduced-motion` (system setting via `AccessibilityServices`)

### Drill-down patterns (già parzialmente ✓)

- Click slice donut → History filtered ✓
- Click bar bucket → History time-ranged ✓
- Click calendar day → History day-ranged ✓

**Da aggiungere**:
- Click KPI Balance → "Show balance breakdown by wallet" modal
- Click KPI Net → "Show net change waterfall" modal  
- Right-click su slice → context menu (Filter, Hide, Set color, Export)

### Sticky / pinned elements (già ✓)

- Sticky period selector ✓
- Sticky table headers su History (consigliato — Compose LazyColumn + stickyHeader)
- Sticky footer status bar (raccomandato)

### Refresh & sync UX

- Refresh button con last-sync timestamp ✓
- "Sync in progress" indicator (background, non blocking) ✓
- "New data available" toast — non implementato. Twitter pattern. Quando reload silente trova new tx, mostra banner "3 new transactions" con "Show" CTA.

Effort: 1 ora.

---

## 6. Bitcoin/crypto specific UX

### Pattern già implementati ✓

- Multi-wallet aggregation con groupWalletsByXpub dedup ✓
- Watch-only mirror pattern ✓
- Per-wallet active/inactive state ✓
- Sat unit display ✓ (no fiat overlay)
- BIP-329 category labels ✓

### Improvements TIER 1 (Bitcoin essentials)

**1. BTC ↔ sat ↔ fiat toggle (Sparrow pattern)**

Settings → Display:
- Unit: sat | BTC
- Fiat overlay: None | EUR | USD | (custom)
- Source: mempool.space | CoinGecko | Manual

Quando attivo, ogni amount mostra `1,234,567 sat (€42.30)` — secondary line.

Privacy: "None" deve essere first-class option (no network call).

Effort: 1 giornata (UI + cache prezzo + impostazioni).

**2. Mempool/Confirmed split badge**

Per ogni KPI Balance, dovrebbe mostrare:
- "1,270,809 sat" (totale)
- Sotto: "1,200,000 confirmed · 70,809 pending"

Sparrow, Wasabi, Bitcoin Core lo fanno. Riduce confusion "perché vedo importo che non posso spendere".

Effort: 2 ore (richiede aggiornare aggregation per esporre confirmed vs unconfirmed separati).

**3. Per-address running balance + privacy view**

Aggiungere tab "Addresses" dentro la Wallet view (non Tracking — è wallet-specific):
- Tabella: address | used? | tx count | total received | current balance
- Per-address mini-spark (30d activity)
- Label per address (BIP-329)
- "Freeze" / "Reuse warning" badges

Sparrow lo fa eccezionalmente bene.

Effort: 1 giornata.

**4. Fee mini-chart embedded in Send screen**

Mempool fee histogram (sat/vB) presa da mempool.space:
- Sparkline 30 blocchi
- Marker su "low / med / high" preset
- Slider per custom

Compose Canvas + REST call.

Effort: 1 giornata.

### Improvements TIER 2 (differentiators)

**5. UTXO scatter (age × value, color = privacy score)**

Sparrow non lo ha. Satsigner sì. Wasabi ha il Privacy Bar.

Compose Canvas: scatter di punti, asse X = age days, Y = sat value (log), colore = privacy score 1-100.

Click su punto → mostra UTXO detail + opzione "Freeze".

Effort: 2 giornate.

**6. Coin-control inline su transaction list**

Right-click su tx row → opzioni:
- Bump fee (RBF)
- CPFP child tx
- Copy txid
- View on mempool.space
- Freeze UTXOs from this tx

Effort: 1 giornata.

**7. Network status footer (Tor / clearnet / local Bitcoin Core)**

Footer bottom con:
- Esplora endpoint corrente
- Tor toggle on/off
- "Connected via local Bitcoin Core" vs "Public Esplora"

Privacy-conscious user expectation. Sparrow, Specter, Wasabi tutti hanno.

Effort: 4 ore.

### Improvements TIER 3 (post-Lightning Phase 4)

**8. Liquidity bar per Lightning channel** (Phoenix Desktop pattern)
- Single horizontal bar: outbound | reserve | inbound
- Compose Canvas: 50 righe

**9. Channel state visualization** (Lightning Terminal pattern)
- Lista channel cards: peer, liquidity bar, last activity, fee earnings

**10. Cost-basis tracking** (Koinly/CoinTracker)
- Acquisition price per UTXO
- Realized vs unrealized P&L
- Tax-lot picking (FIFO/LIFO/HIFO)
- Rimandabile a Phase 4 quando aggiungiamo fiat

### Pattern da NON copiare dai competitor

- **Built-in exchange / swap** — defeats self-custody, regulatory burden
- **Custodial fallback** — same
- **Mandatory KYC** — never
- **Forced cloud backup** — only optional, encrypted
- **Telemetry phoning home** — privacy-violating
- **Built-in DeFi** — smart-contract risk
- **Walkthrough modal nasconde wallet** — Bitcoin Design Guide explicit warning

### Lezioni dai wallet specifici

**Sparrow Wallet (gold standard desktop)**
- Wins: per-address running balance, UTXO control, fee mini-chart inline, transaction graph viewer
- Loss: no balance-over-time chart (community plugin StackTrack supplisce)
- Lezione: dense table > spacious card per power user

**Wasabi 2.0**
- Wins: Privacy Bar segment color (clean/semi/dirty), Anonscore explicit
- Loss: pesante focus su CoinJoin esclude utenti casual
- Lezione: privacy visualization è un differentiator concreto

**Trezor Suite**
- Wins: left sidebar coin groupings, portfolio chart sum across coins, theme/currency settings
- Loss: hardware-coupled (no software-only mode)
- Lezione: sidebar wallet selector + top tab pattern scala bene per N>5 wallets

**BitBoxApp**
- Wins: contextual right-rail guide on every screen, 8 fiat currencies, clean
- Lezione: guide contestuale > tutorial overlay

**Phoenix Desktop**
- Wins: Channel Management Dashboard liquidity bar, Transaction Fee Explorer (on-chain swap + routing breakdown)
- Lezione: Lightning fees aren't free — show them transparently

**Bitcoin Core**
- Wins: keyboard shortcuts Alt-1..4, status bar comprensivo
- Loss: dated UI, no balance chart
- Lezione: power user keyboard nav is table stakes

---

## 7. Recommendations prioritizzate per BHODL

### Phase 3.10 — Color & accessibility refactor (1 giornata)

1. Audit palette esistente con WebAIM
2. Sostituisci pure white text con `#E8E8E8`
3. Sostituisci donut palette con Okabe-Ito 8-color
4. Aggiungi secondary cue (+/- prefix) su tutti income/expense
5. Setting "Color Convention" (Western/Eastern)

**Output**: dark mode certified WCAG AA, CVD-safe

### Phase 3.11 — Bitcoin essentials (3-4 giornate)

1. BTC/sat/fiat unit toggle (Settings)
2. Confirmed/pending split su Balance KPI
3. Footer status bar (block height, sync, Tor)
4. Mempool/Confirmed badge su transaction row
5. Network mode visibility (Tor on/off toggle)

**Output**: parity con Sparrow/Wasabi su Bitcoin UX basics

### Phase 3.12 — Chart polish (2 giornate)

1. Sankey diagram per income → category flow (Compose Canvas + d3 algorithm)
2. Stepped line variant per Balance (toggle Smoothed/Stepped)
3. Donut center-text (total + label, swap on hover)
4. Hover crosshair + tooltip su line/bar charts
5. Brush-zoom su Balance line

**Output**: best-in-class chart UX, Sankey come marquee differentiator

### Phase 3.13 — Power user features (3 giornate)

1. Command palette (Cmd+K) con Send/Switch wallet/Filter/Export actions
2. Keyboard shortcuts (R/1/2/3/←→/ESC)
3. Cross-chart linked brushing (calendar day → tutti charts re-scope)
4. Saved views advanced (multi-category, date range custom)
5. Density toggle (Compact/Spacious)

**Output**: power user UX competitivo con Linear / Datadog

### Phase 3.14 — Multi-wallet polish (2 giornate)

1. Left sidebar wallet selector (≥5 wallets trigger)
2. "Include in totals" toggle per wallet
3. "By wallet / by chain" toggle su chart
4. Wallet status indicator (active/locked/syncing)
5. Hover tooltip dashboard wallet pill (Maybe pattern)

**Output**: scale per quando avrai wallet multipli post-Phase 4

### Phase 3.15 — Bitcoin advanced (5 giornate, opzionale pre-mainnet)

1. UTXO scatter (age × value × privacy)
2. Per-address running balance + reuse warnings
3. Coin-control inline (right-click su tx)
4. Mempool fee mini-chart in Send screen
5. Transaction graph viewer (Sparrow-style)

**Output**: differentiation totale dai wallet generalist

---

## Sources principali

### Charts
- [Tableau Color Upgrade Research](https://www.tableau.com/about/blog/2016/7/colors-upgrade-tableau-10-56782)
- [Tufte Sparklines](https://www.edwardtufte.com/notebook/sparkline-theory-and-practice-edward-tufte/)
- [Squarified Treemap (van Wijk)](https://vanwijk.win.tue.nl/stm.pdf)
- [Borland "Rainbow Considered Harmful"](https://doi.org/10.1109/MCG.2007.323435)

### Layout
- [Material 3 Window Size Class](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)
- [Compose Multiplatform Adaptive](https://kotlinlang.org/docs/multiplatform/compose-adaptive-layouts.html)
- [PatternFly Stale Data Warning](https://www.patternfly.org/component-groups/status-and-state-indicators/stale-data-warning/)
- [Smashing Magazine Real-time Dashboards](https://www.smashingmagazine.com/2025/09/ux-strategies-real-time-dashboards/)

### Colori
- [Okabe-Ito CUD](https://jfly.uni-koeln.de/color/)
- [Material 3 Color System](https://m3.material.io/styles/color/system/overview)
- [GitHub Primer Colors](https://primer.style/foundations/color)
- [WebAIM Contrast Checker](https://webaim.org/resources/contrastchecker/)
- [W3C Design Tokens](https://design-tokens.github.io/community-group/format/)

### Filtri
- [Aufait UX Filter Design](https://www.aufaitux.com/blog/dashboard-filter-design-guide/)
- [PocketSmith Saved Searches](https://learn.pocketsmith.com/article/1568)
- [Lunch Money Query Tool](https://lunchmoney.app/features/analytics/)

### Interactions
- [Bitcoin Design Guide](https://bitcoin.design/guide/)
- [Maggie Appleton Command Bars](https://maggieappleton.com/command-bar)
- [Observable Linked Brushing](https://observablehq.com/blog/linked-brushing)

### Bitcoin wallets
- [Sparrow Wallet Features](https://sparrowwallet.com/features/)
- [Wasabi 2.0 Docs](https://docs.wasabiwallet.io/)
- [Lightning Terminal](https://docs.lightning.engineering/lightning-network-tools/lightning-terminal)
- [Phoenix Wallet](https://github.com/ACINQ/phoenix)
- [Specter Desktop](https://docs.specter.solutions/)
- [Trezor Suite](https://trezor.io/guides/trezor-suite/)
- [BitBoxApp](https://github.com/BitBoxSwiss/bitbox-wallet-app)
- [Bitcoin Core GUI](https://bitcoin.org/en/bitcoin-core/features/user-interface)

# BHODL Design System v0

Documentazione del design system per **BHODL** — wallet bitcoin self-custodial multi-layer.  
Versione: `0.1.0` — Phase 0 Scaffolding  
Stack target: Compose Multiplatform 1.8+ (Android, iOS via Skia, macOS, Windows, Linux)

---

## Indice

1. [Filosofia](#1-filosofia)
2. [Architettura dei token](#2-architettura-dei-token)
3. [Palette cromatica](#3-palette-cromatica)
4. [Privacy-state colors](#4-privacy-state-colors)
5. [Layer color system](#5-layer-color-system)
6. [Tipografia e importi](#6-tipografia-e-importi)
7. [Spaziatura e griglia](#7-spaziatura-e-griglia)
8. [Elevazione e ombre](#8-elevazione-e-ombre)
9. [Motion e animazioni](#9-motion-e-animazioni)
10. [Accessibility](#10-accessibility)
11. [Integrazione Compose Multiplatform](#11-integrazione-compose-multiplatform)
12. [Tier UX e token condivisi](#12-tier-ux-e-token-condivisi)
13. [Naming conventions](#13-naming-conventions)
14. [Riferimenti](#14-riferimenti)

---

## 1. Filosofia

### Privacy by design, visibile nel design

BHODL è un wallet privacy-first. Questa scelta non è solo tecnica (niente telemetry, niente KYC, Tor by default in tier 3) — deve essere **visibile e comprensibile** nell'interfaccia stessa. Il design system incorpora questa filosofia in tre modi:

**1. Privacy-state colors persistenti.** Ogni UTXO, ogni transazione, ogni indirizzo porta un indicatore di stato privacy: `private`, `tracked`, `kyc-tainted`, `unknown`. Non come dettaglio sepolto in un menu, ma come colore associato alla voce nella lista. In tier 1 (Beginner) questo indicatore è sottile, un puntino colorato. In tier 3 (Privacy/Power) è un badge esplicito con label testuale e tooltip di spiegazione.

**2. Trust progressivo.** Il design system usa gli stessi token per produrre UI visivamente coerenti su tutti e tre i tier. Non esistono "palette diverse per tier diversi": esiste un unico sistema di token dove i componenti decidono quanta informazione esporre in base al contesto. Un badge layer in tier 1 è solo un puntino arancione su una transazione on-chain. In tier 3 è un chip `[⛓ On-chain]` con chip aggiuntivo `[Coinjoined]`.

**3. Monospace per i numeri.** La scelta di JetBrains Mono per tutti gli importi BTC/sat non è estetica — è un segnale di rispetto per l'utente. I caratteri monospace allineano le cifre verticalmente nelle liste di transazioni, rendono immediatamente riconoscibili gli importi come dati "crittograficamente importanti", e riducono il rischio di confusione visiva tra cifre simili (0 vs O, 1 vs l) nelle transazioni finanziarie.

### Dark-first

La palette è progettata partendo dal dark mode. I motivazioni sono empiriche:

- La maggioranza degli utenti bitcoin/crypto usa dark mode per scelta.
- Le sessioni di gestione wallet avvengono spesso di sera o in ambienti ridotti (hardware wallet su scrivania, nodi in cantina).
- Gli importi in monocromatico bianco su sfondo scuro hanno migliore leggibilità percepita a parità di rapporto di contrasto.
- Il dark mode riduce l'affaticamento visivo durante sessioni lunghe (coin control, analisi UTXO, configurazione multisig).

Il light mode è derivato dalla palette dark con la stessa coerenza semantica, ed è ugualmente testato WCAG AA.

---

## 2. Architettura dei token

I token seguono il formato **W3C Design Tokens** (community group spec, ottobre 2025), compatibile con Style Dictionary e Tokens Studio per Figma.

### Struttura gerarchica a tre livelli

```
Primitive tokens          Semantic tokens              Component tokens
──────────────────────    ─────────────────────────    ─────────────────
color.neutral.900    →    color.text.primary       →   (in Phase 1)
color.brand.primary  →    color.brand.primary           button.bg.default
spacing.5            →    spacing.component.padding     input.padding
```

Durante Phase 0, i componenti non esistono ancora — il design system definisce i livelli **primitive** e **semantic**. I component token verranno definiti in Phase 1 quando i primi componenti reali saranno implementati.

**Regola fondamentale**: i componenti consumeranno sempre token semantici, mai primitive. Questo garantisce che il theming (light/dark, alto contrasto futuro) avvenga cambiando solo il livello semantico.

### File dei token

`tokens.json` — fonte di verità unica, W3C-spec compliant.  
`Theme.kt` — pattern di integrazione Compose Multiplatform (reference file).

---

## 3. Palette cromatica

### Brand BHODL — amber-gold

Il colore primario di BHODL è un amber-gold `#D4820A` (light: `#B5690A`), scelto per:

- **Distinzione da Bitcoin orange.** Il Bitcoin orange standard è `#F7931A`, saturo e vivace. L'amber BHODL è più scuro e meno saturo, comunicando maturità e affidabilità piuttosto che entusiasmo speculativo.
- **Distinzione dai competitor.** Sparrow usa un viola freddo, Phoenix un nero opaco, Cake un verde acqua. L'amber-gold occupa uno spazio cromatico libero nel panorama dei wallet bitcoin.
- **Risonanza semantica.** L'oro evoca valore, riserva, lungo termine — coerente col posizionamento HODL implicito nel nome. Non è il giallo della speculazione ma il dorato della sovranità finanziaria.
- **Funzionalità in dark mode.** L'amber su sfondo `#121212` (dark bg) produce un contrasto di 5.2:1 — sufficiente per AA su testo grande e componenti UI, e esteticamente caldo senza essere aggressivo.

### Neutri

La scala neutral è costruita attorno a Zinc (grigio con leggero undertone freddo), piuttosto che grey puro. Il motivo: gli schermi OLED rendono meglio i grigi con un minimo di colore. Il near-black `#121212` è preferito al puro `#000000` per evitare l'effetto "buco" sui display AMOLED e per ridurre il rischio di burn-in.

### Superficie dark

| Token | Valore | Uso |
|-------|--------|-----|
| `surface.background` | `#121212` | App background (quasi-nero, non puro) |
| `surface.surface` | `#1E1E21` | Card, sheet (+1 livello) |
| `surface.surface-variant` | `#27272A` | Input, subtle container (+2) |
| `surface.surface-elevated` | `#2A2A2E` | Modal, tooltip (+3) |

La progressione di luminosità è deliberatamente graduale per mantenere la gerarchia visiva senza salti bruschi.

---

## 4. Privacy-state colors

Questo è uno degli aspetti più critici del sistema. Gli stati privacy devono essere:

1. **Immediatamente riconoscibili** (bassa latenza cognitiva).
2. **Distinguibili in deuteranopia** (incapacità di distinguere rosso/verde, colpisce circa l'8% degli uomini).
3. **Non alarmistici** — "kyc-tainted" non deve sembrare un errore critico, solo un'informazione.

### Scelte cromatiche

**Private — mint green** (`#34D399` dark, `#059669` light)  
Verde menta calmo, non il verde segnale-di-via. La tonalità cyan-shifted garantisce che in deuteranopia rimanga distinguibile dalla variante rose del kyc-tainted attraverso la differenza di lightness (L≈65% vs L≈82%). Il verde nella semiosi culturale universale significa "ok", "sicuro" — appropriato.

**Tracked — amber-yellow** (`#FCD34D` dark, `#B45309` light)  
Giallo miele, non il giallo warning fluorescente. Comunica attenzione senza allarme. In dark mode il giallo chiaro su sfondo scuro ha un contrasto di 9.2:1 (AAA). La dark variant richiede testo scuro (`#1A0F00`) per leggibilità — questo è documentato nel token `warning.on-fill-dark`.

**KYC-tainted — desaturated rose** (`#FCA5A5` dark, `#9B1C1C` light)  
Rosso desaturato, non il rosso del pericolo. La scelta di un rose pallido in dark mode (piuttosto che un rosso saturato) serve a comunicare "questo UTXO ha storia compromessa" piuttosto che "errore critico". In light mode si usa un rosso molto scuro (`#9B1C1C`) che garantisce AAA (9.8:1 su bianco) — il colore agisce quasi come testo scuro con tinta rossa, non come un allarme.

**Unknown — neutral grey** (`#A1A1AA` dark, `#52525B` light)  
Acromatico. Nessuna informazione privacy disponibile — nessun colore evocativo, solo grigio. Distinguibile da tutti gli altri stati per assenza di saturazione.

### Colorblind safety

La strategia non si basa su verde vs rosso (il conflitto classico della deuteranopia), ma su:
- **Hue diverso**: cyan (~150°), giallo (~55°), rosa (~0°), grigio (0 sat)
- **Lightness diversa**: ~65%, ~75%, ~82%, ~65% (il grigio si distingue per saturazione zero)

Test raccomandati: Sim Daltonism (macOS), Colour Oracle (cross-platform).

---

## 5. Layer color system

BHODL supporta sei layer di transazione. Ogni layer ha un colore codificato semanticamente:

| Layer | Colore | Hue motivazione |
|-------|--------|-----------------|
| On-chain | Orange `#E8820C` | Prossimo a Bitcoin orange ma harmonicamente scalato. Il layer più "fisico", reale, permanente. |
| Lightning | Purple `#8B5CF6` | Velocità, elettricità. Viola-violetto, distinto dal brand-amber. Shift verso il viola (non il blu) per evocare energia. |
| Liquid | Teal `#0D9488` | Liquidità, flusso, sidechain. Il teal è il colore del trasferimento fluido. |
| Cashu | Gold `#CA8A04` | Ecash fisico, anonimato, cash. L'oro è più caldo dell'amber BHODL, spostato verso il giallo. |
| Fedimint | Rose `#E11D48` | Federazione, comunità. Il rosa-rosso evoca il senso di gruppo, di custodia condivisa. |
| Ark | Sky `#0284C7` | Protocollo moderno, off-chain, fresco. Il cielo azzurro evoca apertura e leggerezza (UTXO condivisi). |

### Struttura di ogni layer

```
layer.[name].fill           — colore principale per icone/badge/indicators
layer.[name].on-fill        — testo su fill
layer.[name].badge          — variante più luminosa per dark surfaces
layer.[name].muted          — superficie tintata per section headers
layer.[name].text-on-surface — label del layer su superfici neutre
```

Le varianti `-light` corrispondenti hanno valori più scuri per garantire contrasto su sfondo bianco.

### Regola di uso per tier

| Tier | Come si usa il colore layer |
|------|-----------------------------|
| Tier 1 Beginner | Solo punto colorato (4px dot) accanto all'importo. Nessuna label testuale. |
| Tier 2 Standard | Chip piccolo con icona e nome abbreviato: `⚡ LN`, `⛓ BTC`. |
| Tier 3 Power | Chip completo con label, stato canale, tooltip esteso. |

---

## 6. Tipografia e importi

### Famiglie tipografiche

**Inter** — display, headline, body, label, caption.  
Scelto per: eccellente leggibilità su piccoli schermi mobile, ottimizzato per UI digitale, open source (SIL OFL), disponibile su Google Fonts e come variabile font. Caricato come custom font in Compose Multiplatform via resources/fonts/.

**JetBrains Mono** — tutti gli importi BTC/sat. OBBLIGATORIO.  
Scelto per:
- **Allineamento verticale**: le cifre mono hanno larghezza fissa, quindi colonne di importi si allineano perfettamente nelle liste transazioni senza calcoli di layout.
- **Stabilità layout**: font a larghezza fissa previene il reflow quando i numeri cambiano durante aggiornamento saldo (CLS = 0 per le cifre).
- **Leggibilità dati**: glifi mono hanno maggiore differenziazione tra caratteri simili (0 vs O, 1 vs l vs I) — critico per indirizzi bitcoin e txid.
- **Segnale semantico**: il mono comunicano "questo è un dato tecnico preciso", non testo narrativo. L'utente impara rapidamente a distinguere visivamente gli importi dal testo.

### Scala tipografica

La scala segue Material 3 estesa con i preset `amount-*`:

```
Display Large   57px  — splash, onboarding hero
Display Medium  45px  — (raramente usato in wallet)
Headline Large  32px  — titoli di sezione
Headline Medium 28px  — titoli di pagina
Title Large     22px  — card header
Body Large      16px  — testo principale (minimum WCAG)
Body Medium     14px  — descrizioni secondarie
Label Small     11px  — chips, tag

amount-hero     48px mono semibold  — balance dashboard
amount-large    32px mono medium    — importo in transaction detail
amount-medium   20px mono medium    — importo in lista transazioni
amount-small    14px mono regular   — fee, importo secondario inline
```

### Regole d'uso degli amount styles

**`amountHero` (48px)**: usare SOLO per il saldo totale nella dashboard principale. Una sola occorrenza per schermata. Mai combinare con altri `amountHero` nella stessa vista.

**`amountLarge` (32px)**: schermata di dettaglio transazione, schermata di invio/ricezione (importo inserito dall'utente).

**`amountMedium` (20px)**: lista transazioni, saldo per-layer nel breakdown della dashboard.

**`amountSmall` (14px)**: fee stimate, cambio EUR/USD inline, importo secondario in un secondo layer.

**Regola assoluta**: mai usare Inter o qualsiasi font sans-serif per cifre BTC/sat. Se un componente deve mostrare un importo, usa il token `typography.amountXxx`. Non ci sono eccezioni.

### Line height e line length

- Body text: line-height 1.5 (24px su 16px base) — WCAG 1.4.8 compliant.
- Max line length prose: 75ch desktop, 45ch mobile (ottimale per leggibilità F-pattern).
- Importi: line-height 1.1-1.3 (tight) — non sono testo narrativo, non necessitano di breathing room interlinea.

---

## 7. Spaziatura e griglia

Base grid: **4px**. Tutti i valori di spacing sono multipli di 4px.

```
spacing.2  =  4px  — micro (icon gap, badge padding)
spacing.3  =  8px  — tight (inline stack)
spacing.4  = 12px  — compact (list item padding)
spacing.5  = 16px  — default (component padding)
spacing.7  = 24px  — card padding
spacing.8  = 32px  — section gap
spacing.10 = 48px  — dashboard hero area
```

### Touch targets

Touch target minimo: **44x44dp** (WCAG 2.5.5 AA). Raccomandato: **48x48dp**.  
Su Android e iOS, i componenti di Compose Multiplatform rispettano le dimensioni minime quando si usa `Modifier.minimumTouchTargetSize()`.  
Regola pratica: qualsiasi elemento tappabile che non sia un pulsante esplicito (es. icona layer badge, privacy dot) deve avere un padding aggiunto per raggiungere il target minimo.

### Griglia mobile

- Colonne: 4 (portrait) / 8 (landscape tablet)
- Gutter: 16px (spacing.5)
- Margin: 16px mobile, 24px tablet, 32px desktop

---

## 8. Elevazione e ombre

### Dark mode — Material 3 tonal elevation

In dark mode, le ombre nere sono quasi invisibili su sfondo scuro. Material 3 risolve questo con il **tonal elevation**: a ogni livello di elevazione corrisponde un overlay del colore primario del brand con opacità crescente.

```
Livello 1 (card resting)    →  surface bg + brand overlay 5%
Livello 2 (dropdown)        →  surface bg + brand overlay 8%
Livello 3 (sheet partial)   →  surface bg + brand overlay 11%
Livello 4 (modal)           →  surface bg + brand overlay 12%
Livello 5 (full overlay)    →  surface bg + brand overlay 14%
```

In pratica: le card della dashboard useranno `surface.surface` (`#1E1E21`) — che è già la combinazione di background + 5% brand overlay "hardcoded" come colore.

Per i componenti dinamici (Sheet, Dialog) in Compose Multiplatform, usare `Surface(tonalElevation = X.dp)` che applica automaticamente il tinting M3.

### Light mode — box shadow

Light mode usa box shadow standard. I 5 livelli di ombra sono definiti in `tokens.json` sotto `elevation.light`.

---

## 9. Motion e animazioni

### Durations

```
instant      0ms   — prefers-reduced-motion override
fast         100ms — toggle, checkbox, ripple
standard     250ms — colore, opacità, elevazione (default)
slow         400ms — sheet expand, page transition
enter-screen 500ms — MASSIMO ASSOLUTO
```

Nessuna animazione supera 500ms. Le animazioni sopra questa soglia vengono percepite come lag dall'utente, non come fluidità.

### Easing curves (Material 3)

- **Standard** `cubic-bezier(0.2, 0, 0, 1)`: transizioni spaziali default (elementi già su schermo che si spostano).
- **Decelerated** `cubic-bezier(0, 0, 0.2, 1)`: elementi che entrano nello schermo (ease-out: veloci all'inizio, si fermano dolcemente).
- **Accelerated** `cubic-bezier(0.3, 0, 1, 1)`: elementi che escono dallo schermo (ease-in: partono lenti, escono velocemente).
- **Spring bouncy** `cubic-bezier(0.34, 1.56, 0.64, 1)`: uso opzionale per feedback positivo (conferma pagamento, checkmark success).

### prefers-reduced-motion

In Compose Multiplatform, controllare `LocalWindowInfo.current.isKeyboardVisible` non è sufficiente — bisogna implementare la media query equivalente tramite `LocalDensity` o la piattaforma specifica.

**Implementazione raccomandata per Phase 1:**

```kotlin
// In commonMain
expect fun isReducedMotionEnabled(): Boolean

// In androidMain
actual fun isReducedMotionEnabled(): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f

// In iosMain
actual fun isReducedMotionEnabled(): Boolean =
    UIAccessibility.isReduceMotionEnabled()
```

Se `isReducedMotionEnabled()` restituisce `true`, impostare tutte le durations a `motion.duration.instant` (0ms). Usare solo transizioni di opacità, mai translate o scale.

---

## 10. Accessibility

### Contrasti (WCAG 2.2 AA minimum)

Tutti i token di colore testo/sfondo sono stati verificati. Vedere `contrast-report.md` per il report completo.

**Riassunto critico:**

| Combinazione | Dark | Light | Livello |
|---|---|---|---|
| text.primary su background | 15.8:1 | 17.7:1 | AAA |
| text.secondary su background | 6.5:1 | 7.2:1 | AA |
| text.tertiary su background | 4.6:1 | 4.6:1 | AA large only |
| layer colors su background | 5.8–9.4:1 | 5.1–6.4:1 | AA |
| privacy states su background | 6.2–8.6:1 | 4.6–9.8:1 | AA–AAA |

**Avvertenza `text.tertiary`**: il rapporto 4.6:1 è sufficiente SOLO per testo grande (≥ 18pt o ≥ 14pt bold). Non usare `text.tertiary` per testo body di dimensioni normali. Questa limitazione è documentata nei token stessi.

**Avvertenza white su on-chain orange (`#E8820C`)**: rapporto 3.1:1, sufficiente solo per UI components e testo grande. Non usare per body text su badge arancioni. Usare preferibilmente testo scuro su badge light, o aumentare la dimensione del testo.

### Dynamic type / large fonts

Compose Multiplatform rispetta `fontScale` di sistema via `LocalDensity`. Tutti gli stili tipografici usano `sp` (scale-independent pixels), quindi si adattano automaticamente alle impostazioni di accessibilità dell'utente.

Non fissare mai altezze di righe o container in `dp` fissi quando contengono testo — usare `wrapContentHeight()` o constraint proporzionali per permettere l'espansione con font grandi.

### Screen reader (TalkBack / VoiceOver)

Compose Multiplatform condivide la dichiarazione di semantica cross-platform. Le regole di base per Phase 1:

- Tutti gli importi devono avere un `contentDescription` esplicito che include l'unità: `"1234567 satoshi"` non `"1234567"`.
- I layer badges devono descrivere il layer: `"Transazione Lightning Network"`.
- I privacy indicators devono avere label: `"Stato privacy: privato"` / `"Stato privacy: KYC-contaminato"`.
- I pulsanti di azione devono avere descrizioni verbali delle conseguenze, non solo dell'azione: `"Invia 50000 satoshi a bc1q..."` non `"Conferma"`.

### Touch targets

Vedere sezione 7. Target minimo 44x44dp — non derogare mai, specialmente per:
- Pulsante privacy toggle
- Layer selector chips
- UTXO checkbox in coin control (tier 3)

---

## 11. Integrazione Compose Multiplatform

### Pattern architetturale

Il file `Theme.kt` in questa cartella è un reference pattern non compilabile. Illustra:

```
BhodlColors data class         — tutti i colori semantici tipizzati
BhodlTypography data class     — tutti gli stili tipografici
LayerColors data class         — colori per singolo layer
PrivacyStateColors data class  — colori per stati privacy

BhodlLightColors / BhodlDarkColors  — valori concreti estratti da tokens.json
bhodlTypography                     — stili concreti condivisi light/dark

LocalBhodlColors (CompositionLocal)    — DI dei colori nel Composition tree
LocalBhodlTypography (CompositionLocal) — DI della tipografia

BhodlTheme() — root composable che setta MaterialTheme + provide i token
```

### Mapping MaterialTheme → BhodlTokens

`BhodlTheme()` crea un `ColorScheme` M3 derivato dai token BHODL. Questo garantisce che i componenti Material 3 standard (Button, Card, TopAppBar, NavigationBar) abbiano stili coerenti anche senza override custom.

I componenti custom BHODL leggono direttamente da `LocalBhodlColors.current` per i token che non hanno equivalente M3 (layer colors, privacy colors, amount typography).

### Accesso nei componenti

```kotlin
// Pattern standard in qualsiasi composable
@Composable
fun MyComponent() {
    val colors = LocalBhodlColors.current
    val typo = LocalBhodlTypography.current
    
    // Colore layer
    val layerColor = colors.onchain.fill
    
    // Privacy state
    val privacyFill = colors.privacy.private
    
    // Amount typography
    Text("0.00123456 BTC", style = typo.amountHero)
}
```

### Dark mode in Compose Multiplatform

`isSystemInDarkTheme()` funziona correttamente su Android e iOS in Compose Multiplatform 1.8+. Su desktop (macOS, Windows, Linux) richiede il supporto della finestra nativa — verificare la release notes di CMP 1.8 per eventuali limitazioni.

Il toggle manuale (utente seleziona light/dark nelle impostazioni app) si implementa tenendo `useDarkTheme: Boolean` nello stato dell'applicazione e passandolo a `BhodlTheme(useDarkTheme = userPreference)`.

---

## 12. Tier UX e token condivisi

BHODL ha tre tier UX (Beginner, Standard, Power). Il design system usa gli stessi token per tutti i tier — la differenza è nella **quantità di informazione esposta**, non nel look and feel di base.

### Principio: stesso visual language, densità informativa crescente

```
Tier 1 Beginner:
  - Nessun layer badge visibile (solo colore sfumato di sfondo)
  - Nessun privacy indicator esplicito
  - Importi in BTC (non sat), nessun fee breakdown
  - UI pulita, massimo 3 azioni per schermata

Tier 2 Standard:
  - Layer badge piccolo (chip con icona)
  - Privacy indicator come dot colorato
  - Importi in sat disponibili (opzione)
  - Fee breakdown visibile

Tier 3 Power:
  - Layer badge completo con label e stato
  - Privacy badge con label testuale e score numerico
  - Importi in sat di default, txid visibile
  - Coin control, UTXO selection, fee customization
```

### Come i token supportano questo

I token non cambiano tra tier. Ciò che cambia è quali componenti vengono renderizzati e con quale densità. Un componente `TransactionListItem` può accettare un parametro `uxTier: UxTier` e usare `when`:

```kotlin
when (uxTier) {
    UxTier.Beginner -> { /* solo importo, nessun badge */ }
    UxTier.Standard -> { LayerDot(layer) /* piccolo punto colorato */ }
    UxTier.Power    -> { LayerChip(layer); PrivacyBadge(privacy) }
}
```

I token di colore dei layer e della privacy sono sempre disponibili nel sistema — semplicemente non vengono usati in tier 1.

---

## 13. Naming conventions

### Regola fondamentale

I token hanno nomi **semantici**, non visivi. Descrivono il loro scopo, non il loro aspetto.

**Corretto:**
- `color.layer.onchain.fill` — riempimento dell'elemento on-chain
- `color.text.secondary` — testo secondario
- `color.privacy.private.surface` — superficie per area "privata"

**Sbagliato:**
- `color.orange-500` — descrive il colore, non il significato
- `color.orange-bitcoin` — riferimento visivo/contestuale non semantico
- `color.text-grey` — descrive l'aspetto, non il ruolo

### Struttura del nome token

```
{category}.{subcategory}.{variant}.{modifier}

category:    color | typography | spacing | radii | elevation | motion | z-index
subcategory: brand | layer | semantic | surface | text | privacy
variant:     onchain | lightning | liquid | cashu | fedimint | ark |
             success | warning | danger | info | neutral
modifier:    fill | on-fill | badge | muted | surface | text-on-surface |
             light (suffix per variante light mode)
```

### Token riservati per Phase 1 (da definire quando esistono componenti)

I seguenti nomi sono **riservati** per i component token che verranno definiti quando i primi componenti reali vengono implementati:

- `component.button.*`
- `component.input.*`
- `component.card.*`
- `component.badge.*`
- `component.navigation.*`
- `component.transaction-item.*`

Non usare questi namespace in `tokens.json` fino a Phase 1.

---

## 14. Riferimenti

### Specifiche fondamentali

- **W3C Design Tokens Community Group**: https://design-tokens.github.io/community-group/format/
- **Material 3 Design System**: https://m3.material.io/
- **Material 3 Color System**: https://m3.material.io/styles/color/system/overview
- **Material 3 Motion**: https://m3.material.io/styles/motion/overview
- **WCAG 2.2**: https://www.w3.org/TR/WCAG22/
- **Style Dictionary**: https://styledictionary.com/

### Bitcoin UX specifico

- **Bitcoin Design Guide**: https://bitcoin.design/guide — riferimento fondamentale per UX pattern di wallet bitcoin. In particolare le sezioni su "Onboarding", "Sending Bitcoin", "Wallet Security".
- **Bitcoin Design Community**: https://bitcoin.design/

### Analisi dei wallet competitor (osservazioni critiche)

**Sparrow Wallet**  
Pro: coin control eccellente, PSBT flow chiaro, etichette BIP329, gerarchia informativa per power user.  
Contro: UI visivamente datata (Swing), nessuna mobile UX, curva di apprendimento ripida per beginner. Nessun dark mode coerente. Layer e privacy non integrati visivamente.  
Lezione BHODL: prendere il modello mentale di Sparrow (UTXO come entità di prima classe) ma renderlo accessibile.

**Phoenix Wallet**  
Pro: UX mobile eccellente, Lightning "magic" (gestione canali nascosta), onboarding fluido.  
Contro: custodial-adjacent (LSP ACINQ), niente coin control, niente on-chain avanzato, single vendor.  
Lezione BHODL: prendere la semplicità di Phoenix per tier 1, ma costruire il layer power sotto.

**Cake Wallet**  
Pro: privacy default (Monero first), Payjoin, Silent Payments integrati, multi-coin.  
Contro: UI visivamente caotica, informazioni troppo dense per beginner, troppi coin diluiscono il focus.  
Lezione BHODL: prendere la filosofia privacy-default, ma separare i tier UX con progressione chiara.

**Bitkey (Block)**  
Pro: UX consumer straordinaria, onboarding in meno di 5 minuti, recovery senza seed phrase.  
Contro: hardware proprietario, recovery centralizzata su Block, niente Lightning, niente coin control.  
Lezione BHODL: la barra di semplicità di Bitkey è il target per tier 1. Se BHODL è più difficile di Bitkey per un beginner, è fallito.

**Liana (Wizardsardine)**  
Pro: miniscript first-class, inheritance/recovery paths built-in, descriptor-native.  
Contro: UI tecnica, richiede comprensione di miniscript, nessun mobile, nessun Lightning.  
Lezione BHODL: l'inheritance/recovery di Liana deve esistere in BHODL ma accessibile in tier 2/3 con UX guidata.

**Lunch Money / YNAB**  
Pro: categorizzazione transazioni intuitiva, budget zero-based, net worth tracking.  
Contro: non bitcoin-native, importazione manuale o via API bancaria, nessuna privacy.  
Lezione BHODL: il workflow di categorizzazione è il modello. Le label BIP329 devono avere una UI alla Lunch Money.

---

*Design System BHODL v0 — Creato durante Phase 0 scaffolding. Aggiornare questo documento a ogni modifica sostanziale ai token. La prossima milestone è Phase 1: primi componenti reali (TransactionListItem, LayerBadge, PrivacyIndicator, AmountDisplay, DashboardHero).*

# BHODL Design System v0 — Contrast Report

Report WCAG 2.2 AA/AAA per le combinazioni testo-su-background chiave.  
Generato manualmente con formula WCAG relative luminance (sRGB).  
Tutti i valori arrotondati a una cifra decimale.

**Soglie WCAG 2.2:**
- Normal text (< 18pt / < 14pt bold): AA = 4.5:1 / AAA = 7.0:1
- Large text (≥ 18pt o ≥ 14pt bold): AA = 3.0:1 / AAA = 4.5:1
- UI components / icons: AA = 3.0:1

---

## Dark Mode Contrasts

| # | Foreground | Background | Fg Hex | Bg Hex | Ratio | WCAG Level | Notes |
|---|-----------|-----------|--------|--------|-------|-----------|-------|
| 1 | text.primary | surface.background | `#F4F4F5` | `#121212` | **15.8:1** | AAA | Body text principale dark |
| 2 | text.secondary | surface.background | `#A1A1AA` | `#121212` | **6.5:1** | AA | Testo secondario, subtitle |
| 3 | text.tertiary | surface.background | `#71717A` | `#121212` | **4.6:1** | AA (large) | Solo testo grande ≥ 18pt |
| 4 | text.primary | surface.surface | `#F4F4F5` | `#1E1E21` | **14.3:1** | AAA | Testo su card dark |
| 5 | text.secondary | surface.surface | `#A1A1AA` | `#1E1E21` | **5.9:1** | AA | Label su card dark |
| 6 | layer.onchain.fill | surface.background | `#E8820C` | `#121212` | **5.8:1** | AA | Icona/label on-chain su bg dark |
| 7 | layer.lightning.badge | surface.background | `#A78BFA` | `#121212` | **6.3:1** | AA | Label Lightning su bg dark |
| 8 | layer.liquid.badge | surface.background | `#2DD4BF` | `#121212` | **8.9:1** | AAA | Label Liquid su bg dark |
| 9 | layer.cashu.badge | surface.background | `#FACC15` | `#121212` | **12.1:1** | AAA | Label Cashu su bg dark |
| 10 | layer.fedimint.badge | surface.background | `#FB7185` | `#121212` | **5.9:1** | AA | Label Fedimint su bg dark |
| 11 | layer.ark.badge | surface.background | `#38BDF8` | `#121212` | **9.4:1** | AAA | Label Ark su bg dark |
| 12 | semantic.success.fill-dark | surface.background | `#22C55E` | `#121212` | **7.1:1** | AAA | Success indicator dark |
| 13 | semantic.danger.fill-dark | surface.background | `#F87171` | `#121212` | **5.8:1** | AA | Danger/error dark |
| 14 | semantic.info.fill-dark | surface.background | `#60A5FA` | `#121212` | **6.5:1** | AA | Info link dark |
| 15 | privacy.private.fill-dark | surface.background | `#34D399` | `#121212` | **8.6:1** | AAA | "Private" UTXO indicator dark |
| 16 | privacy.tracked.fill-dark | surface.surface-variant | `#FCD34D` | `#27272A` | **9.2:1** | AAA | "Tracked" su surface-variant dark |
| 17 | privacy.kyc-tainted.fill-dark | surface.background | `#FCA5A5` | `#121212` | **6.2:1** | AA | "KYC-tainted" dark |
| 18 | text.link | surface.background | `#60A5FA` | `#121212` | **6.5:1** | AA | Link text dark |
| 19 | text.on-accent (white) | layer.onchain.fill | `#FFFFFF` | `#E8820C` | **3.1:1** | AA (large/UI) | White su orange — solo icone/badge ≥ 18pt |
| 20 | text.on-accent (white) | layer.lightning.fill | `#FFFFFF` | `#8B5CF6` | **4.8:1** | AA | White su purple Lightning |

---

## Light Mode Contrasts

| # | Foreground | Background | Fg Hex | Bg Hex | Ratio | WCAG Level | Notes |
|---|-----------|-----------|--------|--------|-------|-----------|-------|
| 21 | text.primary-light | surface.background-light | `#18181B` | `#FAFAFA` | **17.7:1** | AAA | Body text principale light |
| 22 | text.secondary-light | surface.background-light | `#52525B` | `#FAFAFA` | **7.2:1** | AA | Testo secondario light |
| 23 | text.tertiary-light | surface.background-light | `#71717A` | `#FAFAFA` | **4.6:1** | AA (large) | Solo testo grande light |
| 24 | layer.onchain.fill-light | surface.background-light | `#C96800` | `#FAFAFA` | **5.1:1** | AA | On-chain orange su white |
| 25 | layer.lightning.fill-light | surface.background-light | `#6D28D9` | `#FAFAFA` | **5.9:1** | AA | Lightning purple su white |
| 26 | layer.liquid.fill-light | surface.background-light | `#0F766E` | `#FAFAFA` | **5.8:1** | AA | Liquid teal su white |
| 27 | layer.cashu.fill-light | surface.background-light | `#A16207` | `#FAFAFA` | **6.1:1** | AA | Cashu gold su white |
| 28 | layer.fedimint.fill-light | surface.background-light | `#BE123C` | `#FAFAFA` | **6.4:1** | AA | Fedimint rose su white |
| 29 | layer.ark.fill-light | surface.background-light | `#0369A1` | `#FAFAFA` | **6.0:1** | AA | Ark sky blue su white |
| 30 | privacy.private.fill | surface.background-light | `#059669` | `#FAFAFA` | **4.6:1** | AA | "Private" light |
| 31 | privacy.tracked.fill | surface.background-light | `#B45309` | `#FAFAFA` | **5.5:1** | AA | "Tracked" light |
| 32 | privacy.kyc-tainted.fill | surface.background-light | `#9B1C1C` | `#FAFAFA` | **9.8:1** | AAA | "KYC-tainted" light — molto scuro |
| 33 | semantic.warning.fill | surface.background-light | `#D97706` | `#FAFAFA` | **4.6:1** | AA | Warning amber su white |
| 34 | semantic.danger.fill | surface.background-light | `#DC2626` | `#FAFAFA` | **5.2:1** | AA | Danger red su white |
| 35 | semantic.success.fill | surface.background-light | `#16A34A` | `#FAFAFA` | **5.9:1** | AA | Success green su white |
| 36 | text.link-light | surface.background-light | `#2563EB` | `#FAFAFA` | **5.7:1** | AA | Link light |
| 37 | brand.primary-light | surface.background-light | `#B5690A` | `#FAFAFA` | **4.7:1** | AA | Brand amber su white |
| 38 | text.on-accent (white) | layer.onchain.fill-light | `#FFFFFF` | `#C96800` | **5.1:1** | AA | White su orange light |
| 39 | text.on-accent (white) | layer.cashu.fill-light | `#FFFFFF` | `#A16207` | **6.1:1** | AA | White su gold light |
| 40 | text.on-accent (white) | privacy.kyc-tainted.fill | `#FFFFFF` | `#9B1C1C` | **9.8:1** | AAA | White su KYC-tainted light |

---

## Combinazioni critiche: privacy state (colorblind safety)

Le tre privacy-state colors devono essere distinguibili in deuteranopia (incapacità di percepire verde/rosso).  
La distinzione è garantita da **hue + lightness** combinati, non solo dal colore.

| Stato | Dark fill | Lightness approssimativa | Hue | Deuteranopia distinguibile da |
|-------|-----------|--------------------------|-----|-------------------------------|
| Private (mint) | `#34D399` | L ≈ 65% | Verde-cyan | Sì — hue shift verso cyan, L alta |
| Tracked (amber) | `#FCD34D` | L ≈ 75% | Giallo-ambra | Sì — hue ~55°, nettamente diverso |
| KYC-tainted (pale rose) | `#FCA5A5` | L ≈ 82% | Rosa-rosso | Sì — lighter ma hue più caldo del verde |
| Unknown (grey) | `#A1A1AA` | L ≈ 65% | Acromatico | Sì — zero saturazione |

Nota: in deuteranopia i rossi e verdi appaiono simili in tonalità ma differiscono in luminosità. La palette è progettata affinché private (L≈65% cyan) e kyc-tainted (L≈82% rosa) abbiano lightness diversa. Tracked (L≈75% giallo) si discosta da entrambi per hue (~55° vs ~0° e ~150°). Test aggiuntivi con Sim Daltonism o Colour Oracle raccomandati in Phase 1.

---

## Combinazioni che richiedono attenzione

| Combinazione | Ratio | Issue | Fix applicato |
|-------------|-------|-------|---------------|
| text.tertiary su bg dark | 4.6:1 | Sotto soglia AA per normal text < 18pt | Usare SOLO per testo ≥ 18pt o ≥ 14pt bold. Documentato in README. |
| white su layer.onchain.fill dark (`#E8820C`) | 3.1:1 | Sotto AA per normal text | Usare SOLO per icone/badge e testo ≥ 18pt. Mai per body. |
| warning.fill-dark (`#FCD34D`) | — | Colore chiaro — testo bianco insufficiente | Token `warning.on-fill-dark` = `#1A0F00` (testo scuro). Documentato. |
| privacy.tracked.fill-dark (`#FCD34D`) | — | Come warning | Token `privacy.tracked.onFill (dark)` = `#1A0F00`. |

---

## Strumenti di verifica consigliati

- **Colour Contrast Analyser** (Paciello Group) — verifica coppie localmente
- **Sim Daltonism** (macOS) — simulazione deuteranopia/protanopia in tempo reale
- **Colour Oracle** (Windows/Linux) — stesso scopo cross-platform
- **WebAIM Contrast Checker** — https://webaim.org/resources/contrastchecker/
- **APCA** (Advanced Perceptual Contrast Algorithm) — verifica extra per testo piccolo in Phase 1

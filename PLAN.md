# BHODL — Piano Funzionale e Tecnologico

> Wallet bitcoin self-custodial multi-layer con tracking nativo di net worth e spese.
> Documento di partenza, maggio 2026. Da rifinire iterativamente.
> **Brand**: BHODL · **Licenza**: Open Source · **Lancio**: Globale
>
> **Dal 2026-10-23 BHODL è watch-only** (vedi la decisione qui sotto): le parti del
> piano che presuppongono firma, invio o custodia di chiavi sono superate e marcate
> come tali, non cancellate.

---

## Decisione 2026-10-23 — BHODL diventa watch-only

**Decisione.** BHODL non tiene più chiavi private e non può inviare bitcoin. È un
wallet watch-only con tracker di net worth e spese integrato; la firma resta nel wallet
di firma dell'utente (hardware wallet, Sparrow, telefono…).

**Rimosso dal codice**: generazione e restore del seed BIP-39, PIN per wallet,
`WalletHandle` / `OnchainAccount`, costruzione / firma / broadcast di PSBT, schermate
Send / ReviewSend / SendSuccess, coin control e freeze, stime fee, RBF / CPFP, PayJoin
(BIP-78), backup cifrato del seed (age) e restore, "mostra frase di recupero",
"promuovi watch a signing", accoppiamento mirror signing↔tracker, migrazione pre-3.0 del
wallet singolo, schermata debug watch, controlli e promemoria di backup nel Security
Hub. Moduli Rust eliminati: `account.rs`, `mnemonic.rs`, `backup.rs`, `payjoin.rs`,
`send_policy.rs` (helper condivisi in `common.rs`). Dipendenze rimosse: `age`,
`secrecy`; `bip39` solo dev-dependency.

**Cosa resta**:
- tre tipi di wallet watch-only: xpub (BIP-86/84/49, zpub/ypub SLIP-132 accettati),
  descriptor HD (fingerprint + path + xpub, Sparrow/Specter), lista di indirizzi;
  mainnet, signet, testnet4, regtest (Tor consigliato su mainnet);
- per wallet: Transazioni, Ricevi (derivazione indirizzi, etichetta prima
  dell'indirizzo), Indirizzi, UTXO in sola lettura, impostazioni (rinomina, etichette
  BIP-329, export del descriptor pubblico, rimozione), rubrica locale;
- tutto il tracker (net worth, investimenti e lotti, asset manuali, budget, bollette,
  regole, obiettivi, forecast, report, Bitcoin Lens);
- un solo segreto, il **PIN del dispositivo**: Argon2id avvolge la chiave SQLCipher che cifra
  `registry.sqlite` e ogni `watch-state/<uuid>.sqlite`. Un codice di recupero facoltativo
  avvolge la stessa chiave e permette di reimpostare un PIN dimenticato
  (`docs/pin-recovery.md`).

**Aggiornamento da build precedenti**: dopo lo sblocco, una schermata una tantum
(LegacyCleanup) elenca i signing wallet ancora su disco e li cancella, chiavi incluse,
solo dopo che l'utente conferma di avere le frasi di recupero; "Not now" li lascia. I
wallet watch-only che li rispecchiavano restano come wallet normali.

**Perché.** Tenere chiavi richiede un livello di audit, di UX di sicurezza e di
responsabilità che il progetto non può sostenere ora; il valore distintivo è il
tracker. Chi spende ha già un wallet di firma migliore per quel compito.

**Effetto sul resto del piano.** Le feature di firma e invio (§4.1 coin control,
RBF/CPFP, PSBT, hardware signing; §4.2 invii privati; §4.3 invii Lightning; §4.7; §4.8
backup del seed; parte di §4.10) e le fasi di roadmap che le presuppongono (§10) sono
**superate**. Lightning, Liquid, Cashu / Fedimint e Ark restano in roadmap ma **da
ripensare come tracking in sola lettura** di saldi tenuti altrove.

---

## 0. Sintesi esecutiva (TL;DR)

Un **wallet bitcoin self-custodial multi-layer** (on-chain + Lightning + Liquid + Cashu/Fedimint + Ark in roadmap) con **tracking nativo del net worth e categorizzazione delle spese** integrato — non un'app esterna, ma un'esperienza unificata.

**Tre tesi di prodotto**:
1. La privacy non si configura, è il default invisibile (Silent Payments + Payjoin v2 + BIP324).
2. Ogni satoshi ha una storia: lot di acquisizione, categoria, merchant, label — propagata su tutta la chain di transazioni del wallet (estensione di BIP329).
3. Self-custody non significa rinunciare a Mint/YNAB: il wallet conosce le tue spese meglio di una banca, perché le firmi tu.

**Spazio di mercato**: nessun wallet self-custodial bitcoin-first ha tracking finanziario nativo. I tool finanziari open-source (Ghostfolio, Rotki, Lunch Money, Maybe/Sure) non hanno wallet integrato. I tax tracker (Koinly, CoinTracker) sono cloud-only e account-level, non per-UTXO.

---

## 1. Vision e posizionamento

### 1.1 Mission statement

> Un wallet che ti aiuta a essere sovrano dei tuoi sat **e** consapevole di come li spendi e accumuli — senza compromessi sulla privacy, senza dipendere da terze parti.

### 1.2 Pillar concettuali

| Pillar | Significato concreto |
|---|---|
| **Sovereign** | Self-custody totale; nessun KYC; nessuna telemetria; opzione full-noded e wallet-only |
| **Multi-layer** | L1 + LN + Liquid + Cashu come "wallets primari"; Ark/Fedimint/RGB/Taproot Assets come "tasche" attivabili |
| **Private by default** | Silent Payments receive, Payjoin v2 send, no address reuse, BIP324, opzionale Tor/I2P |
| **Self-aware** | Net worth, cost basis per-UTXO, categorizzazione spese, budget, savings rate — tutto in-app |
| **Composable** | Standard-first (BIP329, BOLT12, BIP353, NWC, descriptors): export/import senza lock-in |
| **Inheritance-ready** | Miniscript timelock recovery come Liana; export descrittori first-class |

### 1.3 Target audience (chi serviamo)

**Strategia confermata**: pubblico base **mainstream consumer**, con **sezione Privacy Tools Advanced** completa per chi vuole spingere al massimo. Tre tier UX:

- **Tier 1 — Beginner (default)**: zero jargon, default sicuri, niente coin control esposto, LN "easy mode" (Breez SDK Liquid path).
- **Tier 2 — Standard**: aggiunge categorizzazione spese consapevole, budget, multi-currency.
- **Tier 3 — Privacy/Power Tools** (sezione opt-in): coin control completo, miniscript editor, Silent Payments scanning su nodo proprio, Tor toggle, LDK Node "full self-custody" path, UTXO graph, Wabisabi coordinator, SeedSigner DIY.

Il toggle è esplicito ma persistente; nessuna feature critica viene "promossa" senza consenso.

Personas:

- **Mainstream Marco** (60% utenza target): sa cos'è bitcoin, vuole spendere/risparmiare in self-custody senza diventare un cypherpunk.
- **Bitcoiner stacker Sara**: DCA, vuole cost basis e net worth senza cloud tracker.
- **Family steward Federico**: multisig 2-of-3 con inheritance.
- **Privacy power-user Pavel**: vuole Sparrow in tasca, coin control, miniscript.
- **Merchant solo Maria**: incassa in LN, accounting integrato.

Non target (per ora): trader frequenti, DeFi multi-chain, NFT-collector — quei use case non sono bitcoin-first.

### 1.4 Posizionamento competitivo (mappa mentale)

```
                  Privacy-first
                        ▲
                        │
       Cake Wallet ─────┼────── Sparrow
       Bull Bitcoin     │       Wasabi
                        │
       Bitkey ──────────┼────── Liana, Nunchuk
       (UX consumer)    │       (multisig adv.)
                        │
                  Tracking-aware
                        │
   Strike ──────────────┼────── (vuoto: noi)
   Cash App             │       NUOVO POSIZIONAMENTO
   (custodial track)    │
                        │
             Self-custodial + tracking
                        ▼
```

Vogliamo occupare il quadrante **self-custodial + tracking + privacy by default**, oggi vuoto.

---

## 2. Personas e user journey

### 2.1 Persona A — "Stacker Sara"

- 30 anni, sviluppatrice, DCA €200/mese su Kraken, withdraw mensile a wallet, hardware wallet COLDCARD.
- **Pain**: usa CoinTracker per cost basis, ma odia caricare xpub in cloud; non sa quanto spende mensilmente in Lightning perché Phoenix non categorizza.
- **Job-to-be-done**: "Voglio vedere il mio net worth aggiornato, il cost basis del mio stack e il tasso di risparmio mensile in sat — senza affidarmi a CoinTracker."
- **Aha moment**: connette il suo COLDCARD via QR, importa xpub Kraken come watch-only, vede dashboard sats-per-day + cost basis per-lot.

### 2.2 Persona B — "Spender Marco"

- 28 anni, consulente, paga caffè e cene in LN via Wallet of Satoshi, riceve stipendio parte in BTC.
- **Pain**: WoS è custodial, niente categorie, niente budget, niente net worth. YNAB non parla bitcoin.
- **Job-to-be-done**: "Voglio Monzo/YNAB ma in sats, con self-custody."
- **Aha moment**: dopo aver pagato 5 caffè, l'app suggerisce "queste 5 transazioni a `bluebird@cafe.com` sono ricorrenti — etichetto come Food/Coffee?". Click → categoria + budget creato.

### 2.3 Persona C — "Family Federico"

- 45 anni, padre di due figli, vuole proteggere il fondo famiglia.
- **Pain**: Liana fa inheritance ma è desktop e nessuno in famiglia capisce miniscript.
- **Job-to-be-done**: "Voglio un setup 2-of-3 dove se mi succede qualcosa mia moglie può accedere dopo 3 mesi, e voglio vedere il portfolio mensile."
- **Aha moment**: setup guidato che genera descriptor + spiega in linguaggio naturale "se non firmi entro 90 giorni, la chiave di Anna diventa attivabile". Recovery drill annuale auto-suggerito.

### 2.4 Persona D — "Privacy Pavel"

- Power user, ha 4 hardware wallet, usa Sparrow + Wasabi.
- **Pain**: Sparrow è desktop-only, niente mobile. Wasabi coordinator centralizzato.
- **Job-to-be-done**: "Voglio Sparrow in tasca, con coin control, miniscript editor, SeedQR scan, BBQr import, e Silent Payments scanning su mio nodo."
- **Aha moment**: collegate il proprio nodo personale, scansione SP attiva, coin control pieno con UTXO graph viewer mobile.

### 2.5 Persona E — "Merchant Maria"

- Solo entrepreneur, gestisce un food truck, accetta LN tramite BTCPay personale.
- **Pain**: scarica CSV mensile da BTCPay, lo apre in Excel, categorizza a mano per il commercialista.
- **Job-to-be-done**: "Le mie incoming Lightning vengono già con metadata (orderId, posData) — voglio che siano già categorizzate."
- **Aha moment**: l'app collega la sua BTCPay instance via API, importa metadata invoice, auto-categorizza per item type, genera report fiscale.

---

## 3. Differenziatori chiave (i 10 "perché esistiamo")

1. **Cost basis per-UTXO con propagazione attraverso change e coin selection**. Quando spendi 0.001 BTC dal tuo stack, l'app sa esattamente quale lot è stato consumato (FIFO, LIFO, HIFO, Spec ID) e il P&L realized.
2. **Categorizzazione spese tipo Monzo per BTC/LN** con learning rules su dominio LN address, address re-use, BTCPay subtypes, LNURL `text/identifier`.
3. **Net worth multi-layer in self-custody**: BTC L1 + LN balance (channels reserve-aware) + Liquid (L-BTC + USDt + altri asset) + Cashu mints + Ark VTXOs + Fedimint balances + asset off-chain manuali (oro, immobili) + fiat opzionale via open banking. Tutto in un'unica view.
4. **Budget in sats con peg fiat opzionale**: definisci "€500/mese in cibo" e l'app converte in sats con prezzo del giorno, oppure fissa "1.000.000 sat/mese in cibo" e mostra equivalente fiat live.
5. **Privacy default invisibile**: Silent Payments come receive primario (BIP352), Payjoin v2 send automatico con merchant compatibili (BIP77), BIP324 transport, niente address reuse mai.
6. **Inheritance miniscript con UX consumer**: setup wizard tipo Bitkey (no seed visibile) con miniscript timelock sotto il cofano (Liana-style); recovery drill annuale auto-notificato.
7. **MuSig2 / FROST consumer**: 2-of-2 wife/husband o 2-of-3 family con singola firma aggregata indistinguibile da single-sig (taproot privacy + footprint minimo).
8. **BIP329 esteso (proposta upstream)**: campi `category`, `mcc`, `merchant_id`, `acquired_at`, `acquired_price`, `lot_source` come extension non-breaking. Round-trip via QR/NFC/cloud-sync E2E-encrypted.
9. **NWC come canale per sub-budget**: app esterne (podcast 2.0, e-commerce) richiedono permessi su un sub-budget categoria-aware del wallet ("max 50k sat/settimana per podcast").
10. **Tax export 1099-DA-ready** con specific identification; report fiscali per US, EU (MiCA), e formati custom per commercialista (CSV, OFX, JSON-LD).

---

## 4. Funzionalità — breakdown completo

Le feature sono raggruppate per area funzionale; priorità (P0 = MVP, P1 = post-MVP 6 mesi, P2 = 6-12 mesi, P3 = 12+ mesi).

### 4.1 Wallet core (on-chain Bitcoin)

> **Superato 2026-10-23 (watch-only)**: resta solo 4.1.3 (watch-only via xpub / descriptor), esteso alle liste di indirizzi; tutte le voci di firma, coin control, fee, PSBT, multisig e hardware non si applicano più a BHODL.

| # | Feature | Priorità |
|---|---|---|
| 4.1.1 | Single-sig SegWit / Taproot (BIP86 default) | P0 |
| 4.1.2 | Multisig N-of-M nativo via descriptor (BIP388 wallet policies) | P0 |
| 4.1.3 | Watch-only via xpub / descriptor import | P0 |
| 4.1.4 | Coin control (UTXO selection esplicita, lock UTXO, freeze) | P0 |
| 4.1.5 | Branch-and-Bound + Knapsack coin selection con privacy heuristics | P0 |
| 4.1.6 | RBF + CPFP unificati con bump-fee math su parent+child | P0 |
| 4.1.7 | PSBT v2 (BIP370) full support | P0 |
| 4.1.8 | Hardware airgap: SD + USB + QR (BBQr + UR animati) + NFC | P0 |
| 4.1.9 | MuSig2 (BIP327) per 2-of-2 / n-of-n taproot | P1 |
| 4.1.10 | Miniscript / Tapscript editor con templates ("inheritance", "decaying multisig") | P1 |
| 4.1.11 | Inheritance timelock recovery (Liana-style) con UX wizard | P1 |
| 4.1.12 | TRUC (v3) transaction policy awareness | P1 |
| 4.1.13 | FROST integration (Frostsnap o ChillDKG) per 2-of-3 / m-of-n | P3 |
| 4.1.14 | Recovery drill annuale auto-notificato (test send→self con backup) | P1 |
| 4.1.15 | Output descriptor backup obbligatorio + multi-format (PDF, QR, file) | P0 |

### 4.2 Privacy

> **Superato in parte 2026-10-23**: gli invii (SP send, Payjoin, privacy score pre-broadcast, coinjoin) escono dallo scope; restano rilevanti Tor (fatto, SOCKS5) e, da ripensare per un watch-only, BIP324 e la ricezione Silent Payments.

| # | Feature | Priorità |
|---|---|---|
| 4.2.1 | Silent Payments BIP352 receive (default) | P0 |
| 4.2.2 | Silent Payments BIP352 send | P0 |
| 4.2.3 | Payjoin BIP77 v2 send automatico se merchant supporta | P0 |
| 4.2.4 | Payjoin BIP78 receive (merchant-mode opzionale) | P1 |
| 4.2.5 | BIP324 v2 P2P transport encryption (default se nodo proprio) | P0 |
| 4.2.6 | Tor / I2P (orport) toggle per backend | P1 |
| 4.2.7 | Privacy score pre-broadcast con suggestion concrete | P1 |
| 4.2.8 | Coin label-cluster avoidance durante consolidation | P1 |
| 4.2.9 | UTXO graph visualizer con cluster coloring | P2 |
| 4.2.10 | Coinjoin opzionale via WabiSabi coordinator selectabile | P2 |

### 4.3 Lightning Network

> **Da ripensare 2026-10-23**: BHODL non custodisce fondi, quindi i due path (Breez / LDK Node) con invio, ricezione e gestione canali sono superati. Resta l'obiettivo di 4.3.12: mostrare saldi ed eventi Lightning nel tracker, in sola lettura, tramite le interfacce view-only che ogni wallet offre.

**Decisione architetturale**: il wallet espone **due path Lightning selezionabili** dall'utente in fase di setup (e cambiabili dopo):

- **Path A — "Easy mode"** (default per tier 1 Beginner): **Breez SDK Liquid**. Niente channel management, niente liquidity issues, submarine swap automatici via Boltz, BOLT12 + BIP353 + LNURL già pronti. Trust assumption: Liquid Federation. UX simile a Phoenix.
- **Path B — "Full self-custody"** (per tier 3 / power user): **LDK Node + LSPS1/LSPS2 + LSP partner configurabile** (Voltage, Olympus, Megalith, c=, oppure self-host). Channel management astratto ma reale. Splicing, async payments, trampoline. UX simile a Zeus / Bitkit.

Entrambi i path coesistono nello stesso codebase. L'utente può migrare A→B (settles channels via swap) o B→A. Entrambi vedono la **stessa view unificata** lato tracking/categories.

| # | Feature | Path A (Breez) | Path B (LDK Node) | Priorità |
|---|---|---|---|---|
| 4.3.1 | Send: BOLT11 + BOLT12 + LN Address + BIP353 | ✅ | ✅ | P0 |
| 4.3.2 | Receive: BOLT11 dyn + BOLT12 reusable + LN Address | ✅ | ✅ | P0 |
| 4.3.3 | LSPS1/LSPS2 just-in-time channels | n/a (Breez gestisce) | ✅ | P0 |
| 4.3.4 | Splicing in/out | n/a | ✅ | P1 |
| 4.3.5 | Async payments | ✅ (Liquid swap deferred) | ✅ | P1 |
| 4.3.6 | Trampoline routing | ✅ (built-in) | ✅ | P1 |
| 4.3.7 | Hold invoices (escrow) | ❌ | ✅ | P2 |
| 4.3.8 | Submarine swap Boltz (LN↔onchain) | ✅ (auto) | ✅ (manuale + auto) | P0 |
| 4.3.9 | LSPS4 continuous JIT | n/a | ✅ | P2 |
| 4.3.10 | Channel backup SCB | n/a | ✅ + cloud-sync E2E opz. | P0 |
| 4.3.11 | Migrazione path A↔B | ✅ via swap | ✅ via swap | P1 |
| 4.3.12 | Path-agnostic tracking & categorization | ✅ | ✅ | P0 |

### 4.4 Multi-asset & altri layer

> **Da ripensare 2026-10-23**: questi layer restano in roadmap solo come tracking in sola lettura (saldi e storico), non come wallet che spendono.

| # | Feature | Priorità |
|---|---|---|
| 4.4.1 | Liquid Network: L-BTC nativo via LWK | P0 |
| 4.4.2 | Liquid: USDt-Liquid (stablecoin nativo) | P0 |
| 4.4.3 | Liquid: AMP assets / EURx / DePix / BMN | P1 |
| 4.4.4 | Cashu eCash via CDK (single mint configurabile) | P1 |
| 4.4.5 | Cashu auto-melt verso LN oltre soglia (es. 100k sat) | P1 |
| 4.4.6 | Fedimint via fedimint-client (federazione configurabile) | P2 |
| 4.4.7 | Ark / Arkade come "tasca virtual" (post stable release) | P2 |
| 4.4.8 | Taproot Assets (LN-native USDT, RWA) | P2 |
| 4.4.9 | RGB v0.12 stable (asset issuance & client-side validation) | P3 |
| 4.4.10 | Mercury Layer / Statechains (private send) | P3 |

### 4.5 Tracking finanziario

| # | Feature | Priorità |
|---|---|---|
| 4.5.1 | Net worth dashboard (BTC + LN + Liquid + manuali + fiat opz.) | P0 |
| 4.5.2 | Cost basis lot tracking per-UTXO (FIFO/LIFO/HIFO/Spec ID) | P0 |
| 4.5.3 | Spending categorization manuale (BIP329 esteso) | P0 |
| 4.5.4 | Auto-categorization rules (LN address dominio → categoria) | P1 |
| 4.5.5 | Budget mensile per categoria (sats o fiat-pegged) | P1 |
| 4.5.6 | Recurring detection (paga ogni mese a stesso destinatario) | P1 |
| 4.5.7 | Sats-per-day spending velocity & savings rate | P1 |
| 4.5.8 | DCA performance (cost basis medio vs prezzo attuale) — fatto 2026-10-24: costo medio ponderato, prezzo del giorno modificabile per transazione | P1 |
| 4.5.9 | Cash flow chart (in/out per mese) | P1 |
| 4.5.10 | Esportazione fiscale: 1099-DA, CSV per Koinly/CoinTracker | P1 |
| 4.5.11 | Stack target & progress (% completion verso obiettivo) | P2 |
| 4.5.12 | Asset off-chain manuali (oro, immobili, fondi) | P2 |
| 4.5.13 | Open banking fiat side (GoCardless EU / Plaid US) | P2 |
| 4.5.14 | Exchange API import (Kraken, Coinbase, Bitstamp) | P2 |
| 4.5.15 | Lightning channel events tax-aware (apertura/chiusura/splicing) | P2 |
| 4.5.16 | Multi-currency display (EUR/USD/GBP/CHF/JPY) con prezzo storico | P0 |

### 4.6 Onboarding & UX

| # | Feature | Priorità |
|---|---|---|
| 4.6.1 | Beginner mode (default) vs Advanced mode (toggle in settings) | P0 |
| 4.6.2 | Onboarding minimal: solo send/receive/balance al primo lancio | P0 |
| 4.6.3 | Recovery setup wizard (BIP39 / Codex32 / no-seed multisig) | P0 |
| 4.6.4 | Inline education contextual (cos'è change, cos'è una fee, cos'è un canale LN) | P1 |
| 4.6.5 | Scenari preconfigurati: "Stacker", "Daily spender", "Family vault" | P1 |
| 4.6.6 | Light/dark mode + accessibility (screen reader, large fonts) | P0 |
| 4.6.7 | Multilingua (i18n: EN, IT, ES, FR, DE, PT, JA, ZH, RU al rilascio) | P1 |
| 4.6.8 | Shortcuts e command palette (advanced mode) | P2 |

### 4.7 Hardware integration

> **Superato 2026-10-23**: BHODL non firma. L'integrazione si riduce a importare l'xpub / descriptor esportato dal dispositivo (eventuale import via QR / file da valutare).

| # | Feature | Priorità |
|---|---|---|
| 4.7.1 | COLDCARD Mk4 / Q (USB + SD + NFC + BBQr) | P0 |
| 4.7.2 | Foundation Passport (SD + camera) | P0 |
| 4.7.3 | Blockstream Jade / Jade Plus (USB + BT + QR + miniscript) | P0 |
| 4.7.4 | BitBox02 (USB + miniscript Tapscript + BIP329) | P0 |
| 4.7.5 | Ledger Nano X/S+ / Stax (USB + BT + MuSig2 + miniscript) | P0 |
| 4.7.6 | Trezor Model T / Safe 5 (USB + SLIP39 Shamir) | P0 |
| 4.7.7 | SeedSigner (QR-only DIY) | P1 |
| 4.7.8 | Frostsnap (FROST-native) | P2 |
| 4.7.9 | Bitkey integrazione esterna (NFC + screen) | P2 |

### 4.8 Backup & recovery

> **Superato in parte 2026-10-23**: niente seed, quindi niente backup del seed (4.8.1–4.8.3, 4.8.6), SCB, inheritance o recovery drill. Restano l'export del descriptor pubblico (4.8.4) e l'export delle etichette BIP-329 (4.8.5).

| # | Feature | Priorità |
|---|---|---|
| 4.8.1 | BIP39 seed phrase classico (con warning sulle limitazioni) | P0 |
| 4.8.2 | Codex32 (BIP93) backup con error-correcting checksum | P1 |
| 4.8.3 | SeedQR / CompactSeedQR | P0 |
| 4.8.4 | Output descriptor backup (multi-format) | P0 |
| 4.8.5 | BIP329 labels backup (encrypted file + cloud-sync opzionale) | P0 |
| 4.8.6 | Shamir Secret Sharing (BIP93) — opzionale, advanced | P2 |
| 4.8.7 | Lightning Channel State Backup (SCB) automatico | P0 |
| 4.8.8 | Encrypted cloud backup E2E (configurabile: iCloud, Drive, self-hosted) | P1 |
| 4.8.9 | Inheritance timelock setup wizard | P1 |
| 4.8.10 | Recovery drill annuale (test simulato) | P1 |

### 4.9 Sync & multi-device

| # | Feature | Priorità |
|---|---|---|
| 4.9.1 | Wallet portabile via descriptor + label export (BIP329) | P0 |
| 4.9.2 | E2E encrypted sync labels & metadata cross-device (Nostr o IPFS) | P1 |
| 4.9.3 | Multi-device watch-only (mobile + desktop con stesso xpub) | P1 |
| 4.9.4 | Family/group wallet con label condivise (Nunchuk-style) | P2 |
| 4.9.5 | NWC server lato wallet (espone funzioni a app esterne) | P1 |
| 4.9.6 | NWC client (interagisci con wallet altrui via permessi) | P2 |

### 4.10 Merchant & advanced

> **Superato 2026-10-23**: le voci di ricezione Lightning / LNURL presuppongono un wallet che riceve fondi propri; restano eventualmente l'import di metadati (4.10.5) come fonte per il tracker.

| # | Feature | Priorità |
|---|---|---|
| 4.10.1 | LNURL-pay endpoint self-hosted (Lightning Address) | P1 |
| 4.10.2 | BOLT12 reusable offers per ricezione | P0 |
| 4.10.3 | BIP353 nome `user@domain` setup guidato | P1 |
| 4.10.4 | LNURL-pay con metadata estesi (categoria, MCC, order_id) — proposta nostra | P2 |
| 4.10.5 | BTCPay Server connection (import invoice metadata) | P2 |
| 4.10.6 | POS mode mobile (riceve QR, conferma su display) | P2 |
| 4.10.7 | Proof of Reserves BIP-322 (signing) | P1 |
| 4.10.8 | Proof of Liabilities (audit trail per family/business) | P3 |

---

## 5. Architettura tecnica (high-level)

### 5.1 Diagramma a strati

```
┌─────────────────────────────────────────────────────────────────────┐
│                          UI LAYER (Tauri/RN)                         │
│  Screens: Wallet · Send · Receive · Activity · Categories · Reports  │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │  IPC / FFI
┌──────────────────────────────────▼──────────────────────────────────┐
│                  APPLICATION LAYER (Rust core)                       │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌────────────┐ ┌───────────┐  │
│  │ Wallet  │ │ Tracker │ │ Privacy  │ │  Backup    │ │ Hardware  │  │
│  │ service │ │ service │ │ service  │ │  service   │ │ service   │  │
│  └────┬────┘ └────┬────┘ └─────┬────┘ └──────┬─────┘ └─────┬─────┘  │
└───────┼───────────┼─────────────┼────────────┼─────────────┼────────┘
        │           │             │            │             │
┌───────▼───────────▼─────────────▼────────────▼─────────────▼────────┐
│                    DOMAIN LAYER (libraries)                          │
│  BDK · rust-bitcoin · rust-miniscript · LDK Node · LWK · CDK · PDK   │
│  rust-payjoin · bdk-sp · boltz-rust · rust-bip324 · libsecp256k1     │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
┌──────────────────────────────────▼──────────────────────────────────┐
│                  INFRASTRUCTURE LAYER                                │
│  ┌──────────┐ ┌───────────┐ ┌──────────────┐ ┌──────────┐ ┌──────┐ │
│  │  SQLite  │ │ Esplora / │ │ Mempool.space│ │ LSP      │ │ Tor  │ │
│  │ encrypted│ │ Electrum  │ │ price oracle │ │ partner  │ │ /I2P │ │
│  └──────────┘ └───────────┘ └──────────────┘ └──────────┘ └──────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 Moduli core (Rust crates interne)

```
bhodl/
├── crates/
│   ├── core/                    # tipi condivisi, errori, primitive
│   ├── wallet-onchain/          # BDK wrapper, descriptor mgmt, coin control
│   ├── wallet-lightning/        # LDK Node wrapper, LSP integration
│   ├── wallet-liquid/           # LWK wrapper
│   ├── wallet-cashu/            # CDK wrapper
│   ├── wallet-ark/              # P2 placeholder per Arkade SDK
│   ├── privacy/                 # Silent Payments + Payjoin + BIP324
│   ├── tracker/                 # cost basis, categorizzazione, budget
│   ├── labels/                  # BIP329 esteso, sync, encryption
│   ├── price-oracle/            # Mempool/Kraken/Coinbase fallback chain
│   ├── hardware/                # PSBT v2, BBQr, UR, hardware drivers
│   ├── backup/                  # SCB, Codex32, descriptor export, SeedQR
│   ├── nwc/                     # NIP-47 server + client
│   ├── swap/                    # Boltz integration
│   ├── tax/                     # 1099-DA, lot tracking, CSV/JSON-LD export
│   ├── sync/                    # Nostr-based E2E sync labels
│   └── ipc/                     # Tauri commands / FFI bindings (uniffi)
├── apps/
│   ├── desktop/                 # Tauri app
│   ├── mobile/                  # iOS + Android (decisione tooling pending)
│   └── cli/                     # power-user CLI (future)
├── docs/                        # spec extension proposals (BIP329-ext, ecc.)
└── PLAN.md                      # questo file
```

### 5.3 Modello dati (high-level)

```
Account (descriptor-based, descrive wallet logico)
├── Layer (onchain | lightning | liquid | cashu | fedimint | ark)
├── Balance (current + historical snapshots)
├── Transactions
│   ├── TxId / PaymentHash / VtxoId / EcashToken
│   ├── Direction (in | out | internal)
│   ├── Amount (sats), Fee (sats)
│   ├── Counterparty (LN address | onchain addr | mint pubkey)
│   ├── Timestamp + Block height
│   ├── Labels (BIP329 + extension)
│   │   ├── Category (food, transport, ...)
│   │   ├── Merchant (LN address / domain / verified BIP353 id)
│   │   ├── Order ID (BTCPay / merchant)
│   │   ├── MCC code (proposta nostra, mapped a ISO 18245)
│   │   ├── Tags (#hashtag)
│   │   └── Notes (free text)
│   ├── PrivacyMetadata (silent_payment | payjoin | coinjoin)
│   └── TaxLot
│       ├── Lot ID
│       ├── Acquired at (timestamp + price)
│       ├── Source (DCA Kraken | merchant payment | mining)
│       └── Disposal events (for spent UTXO)
├── UTXOs (only for onchain)
│   └── Each UTXO has lot inheritance from parent (cost basis propagation)
├── Channels (only for lightning)
│   └── Backup state (SCB)
└── Mints (only for cashu)
    └── Trust score, balance, last seen
```

### 5.4 Storage

- **SQLite encrypted** (sqlcipher or similar) come primary store. AGPL-incompatible? Verificare; alternativa SQLite + libsodium custom layer.
- **Labels store**: tabella separata pronta per export BIP329 JSONL.
- **Encryption at rest**: chiave derivata da PIN/biometric + secret salt.
- **No cloud by default**: opzionale E2E encrypted backup.

### 5.5 Network

- **Wallet on-chain**: connessione preferita a nodo Bitcoin Core proprio (RPC over Tor); fallback Esplora pubblico (Mempool.space, Blockstream).
- **Compact Block Filters (BIP158)**: fallback per privacy-respecting scanning quando non c'è full node.
- **Silent Payments scanner**: indexer dedicato (Frigate-style) o full node con bdk-sp.
- **Lightning**: LDK Node + connessione a peer + LSP partner.
- **Liquid**: Esplora pubblico Liquid o nodo Elements proprio.
- **Cashu**: HTTP a mint scelta utente.
- **NWC**: Nostr relay configurabile (default un set di relay rispettati: damus.io, nos.lol, relay.snort.social).

---

## 6. Stack tecnologico (decisioni iniziali)

### 6.1 Core: Rust

| Componente | Libreria | Licenza | Versione |
|---|---|---|---|
| Wallet on-chain | `bdk_wallet` 1.x | MIT/Apache-2.0 | 1.x stable |
| Bitcoin primitives | `bitcoin` (rust-bitcoin) | CC0/MIT | latest |
| Miniscript | `miniscript` | CC0 | latest |
| Lightning | `lightning` + `lightning-liquidity` (LDK Node) | MIT/Apache-2.0 | latest |
| Lightning fallback rapido | `breez-sdk-liquid` | MIT | v0.12+ |
| Liquid | `lwk` | MIT | latest |
| Cashu | `cdk` | MIT | v0.16+ |
| Swap | `boltz-client` (Rust) | MIT | latest |
| Payjoin | `rust-payjoin` (PDK) | MIT | latest |
| Silent Payments | `bdk-sp` | MIT | sperimentale |
| BIP324 | `bip324` (rust-bitcoin org) | MIT | latest |
| Crypto base | `secp256k1` Rust + libsecp256k1 | MIT | latest |
| Nostr (NWC + sync) | `nostr-sdk` | MIT | latest |
| FFI mobile | `uniffi-rs` | MPL-2.0 | latest |

### 6.2 UI / app layer

**Decisione (maggio 2026)**: **Kotlin Multiplatform + Compose Multiplatform** con Rust core via **UniFFI** (`uniffi-kotlin-multiplatform-bindings`).

**Motivazioni decisive**:
1. **Bitkey è il precedente esatto**: wallet bitcoin self-custody Rust + KMP + Compose Multiplatform, 95% codice condiviso, in produzione globale ([engineering.block.xyz](https://engineering.block.xyz/blog/how-bitkey-uses-cross-platform-development)). Block ha esplicitamente scartato il native split.
2. **BDK espone già bindings Kotlin/Swift via UniFFI** — riusa lo stesso pattern.
3. **Sicurezza massima**: zero WebView, zero JS engine, zero JSON serialization layer per la crypto. Le chiavi vivono solo nel core Rust con typed-FFI UniFFI.
4. **Compose Multiplatform iOS Stable** dal 1.8 (mag 2025), production-ready 2026. Native-feel su Android (Compose nativo); su iOS usa Skia con gesture/scroll/text-selection iOS-native dal 1.8.
5. **5 piattaforme da una codebase**: Android, iOS, macOS, Windows, Linux.

**Trade-off accettati**:
- Team needs Kotlin skills (vs Dart/TS).
- Bundle iOS +9 MB vs SwiftUI puro.
- Windows desktop è JVM-based (~30-50 MB), accettabile per wallet desktop.

**Stack frontend dettagliato**:

| Layer | Tecnologia | Note |
|---|---|---|
| Linguaggio app | Kotlin 2.2+ | con Swift export sperimentale per moduli specifici |
| UI framework | Compose Multiplatform 1.8+ | iOS stable, desktop production |
| FFI Rust→Kotlin | `uniffi-kotlin-multiplatform-bindings` | toolchain BDK già canonica |
| Native interop iOS | Kotlin/Native + cinterop | per Apple-specific APIs (Keychain, Secure Enclave, BiometricID) |
| Native interop Android | JNI + AndroidX | Keystore, BiometricPrompt, NFC |
| Native interop desktop | Java Foreign Memory API (jextract) | per OS keyring |
| Build | Gradle KMP | + Cargo workspace per Rust core |
| Test | Kotest + cargo test | + UI snapshot test (Paparazzi/Roborazzi) |

**Fallback se KMP risulta blocking**: **Flutter + flutter_rust_bridge v2** (Cake Wallet è il precedente bitcoin), seconda scelta. Decisione di rollback solo dopo prototype phase 0.

### 6.3 Backend services (opzionali, self-hostable)

- **Mempool.space self-hosted**: prezzi storici, blockchain explorer, fee oracle.
- **Esplora self-hosted**: backend per UTXO scan watch-only.
- **Electrum personal server (`electrs`)** o **mempool electrs**: per power users.
- **LSP partner**: Voltage, Olympus, Megalith, c=, oppure self-hosted con `lnd` o `cln`.
- **Sync relay (Nostr)**: configurabile, default un set di relay pubblici noti.

### 6.4 Tooling sviluppo

- **Linguaggio**: Rust 1.85+ (stable channel) + TypeScript 5.x (UI desktop).
- **Build**: Cargo workspaces + Tauri CLI + flutter/RN tooling.
- **Test**: cargo test + integration test su regtest (bitcoind + clnd + arkd).
- **CI**: GitHub Actions o self-hosted (linux + macos + windows + iOS + android).
- **Lint**: clippy strict + rustfmt + biome (TS).
- **Docs**: rustdoc + mdbook per public docs.

---

## 7. UX & design system

### 7.1 Principi

1. **Progressive disclosure**: l'utente vede solo ciò che gli serve oggi.
2. **Modal Beginner / Advanced**: switch reale, persistente, telemetria locale (mai remota) per suggerire upgrade.
3. **No jargon nel beginner mode**: "Send Bitcoin", non "Broadcast PSBT". "Saving wallet" non "cold storage". "Quick spend" non "Lightning".
4. **Privacy invisibile**: features privacy attive di default, segnalate solo se utente le disattiva.
5. **Feedback immediato**: ogni azione ha loading state + result toast + undo dove possibile.
6. **Native feel**: rispetto alle convenzioni iOS/Android/macOS/Windows.

### 7.2 Information architecture (top-level navigation)

```
[Wallet]    [Activity]    [Categories]   [More]
  |             |              |            |
  ├ Balance     ├ Transactions ├ Budget     ├ Settings
  ├ Send        ├ Search       ├ Reports    ├ Hardware
  ├ Receive     ├ Filter       ├ Cost basis ├ Recovery
  └ Charts      └ Tags         └ Tax export └ Privacy
```

### 7.3 Design system

- **Component library**: build custom su shadcn/ui (web) + Material 3 / SwiftUI / Compose (mobile).
- **Tipografia**: monospaziale per importi (Inter Mono o JetBrains Mono).
- **Palette**: dark/light. Color coding per layer (orange = onchain, purple = lightning, teal = liquid, gold = cashu).
- **Iconografia**: lucide-icons o tabler-icons (open source).
- **Animazioni**: parsimoniose; loading state per ogni rete call.

### 7.4 Empty states & onboarding

- Send empty: "Scansiona un QR, incolla un indirizzo, o tocca un contatto."
- Receive empty: con un click → BOLT12 reusable offer + Silent Payment + on-chain QR.
- Categories empty: "Quando paghi, le tue spese appariranno qui. Vedrai categorie suggerite automaticamente."

### 7.5 Microcopy (esempi)

- Backup screen: "La tua frase di recupero protegge tutti i tuoi sat. Conservala offline. Senza di essa, e senza il tuo descriptor, perdi i fondi."
- Send confirm: "Stai inviando 50.000 sat (€31,42 al tasso attuale) a Bluebird Cafe. Categoria: Food. Privacy: Payjoin attivo."
- Inheritance setup: "Se non firmi una transazione per 90 giorni, la chiave di Anna potrà accedere ai fondi. Puoi cambiare questa regola in qualsiasi momento."

---

## 8. Privacy model

### 8.1 Default privacy settings

| Setting | Default |
|---|---|
| Receive: Silent Payments | ON |
| Receive: BIP47 reusable | OFF (BIP47 in declino) |
| Send: Payjoin v2 attempt | ON (fallback regular se merchant non supporta) |
| Address reuse warning | ON |
| BIP324 transport | ON (se collegato a nodo proprio o terzo che supporta) |
| Tor (orport) | OFF (toggleable) |
| Telemetria | OFF (sempre, non toggleable - non c'è proprio) |
| Cloud backup labels | OFF (toggleable) |
| Price oracle | Mempool.space (self-host suggerito) |
| LSP routing | ON (LSP rispettati dichiarati) |
| Coinjoin | OFF (advanced opt-in) |
| Block explorer redirects | usato esclusivamente Mempool.space self-host se disponibile |

### 8.2 Tracking dei dati personali

- **Zero telemetry**: nessun ping, nessun analytics, niente Sentry remoto.
- **Crash reports**: salvati localmente, opt-in opzionale per upload encrypted via Tor.
- **No KYC**: mai, su nessun layer.
- **Open banking** (P2): se usato, le credenziali stanno via OAuth lato banca, non in nostri server. Zero proxy.

### 8.3 Privacy score

Per ogni transazione, l'app valuta:
- Address reuse: penalità.
- Coin label-cluster mix (KYC + non-KYC nello stesso input set): penalità alta.
- Round amount (es. esatto 1.000.000 sat): penalità (privacy leak heuristic).
- Change handling: bonus se change su address SP/taproot pulito.
- Payjoin attivo: bonus.
- Pre-confirmed parent: penalità (heuristic Bitcoin Core wallet).

Score 0-100 con suggerimenti concreti pre-broadcast.

---

## 9. Sicurezza & key management

### 9.1 Key storage

- **Mobile**: iOS Keychain con Secure Enclave; Android Keystore con StrongBox. Master seed mai in plain memory oltre il tempo necessario (zeroize).
- **Desktop**: OS keyring (libsecret/keychain-osx/Windows Credential Manager). Encrypted seed file fallback.
- **Hardware-only mode**: master seed solo su hardware esterno, app è coordinator-only (descriptor watch-only).
- **Multisig**: ogni share su device diverso; coordinator (l'app) non vede mai più di una share.

### 9.2 PIN/biometric

- PIN 6-12 cifre obbligatorio.
- Biometric secondario opzionale (TouchID, FaceID, fingerprint).
- Auto-lock dopo 1/5/15 minuti inattività (configurabile).
- Erase wipe dopo 10 PIN errati (opzionale).
- Duress PIN (decoy wallet, opzionale, P2).

### 9.3 Audit & supply chain

- **Reproducible builds** richiesti (Bitcoin-style guix builds).
- **Signed releases** con chiavi WoT distribuite.
- **Static analysis**: cargo audit, dependabot, security scanner.
- **Open source**: tutto il codice MIT o Apache-2.0 (no AGPL nelle dipendenze critiche).
- **Ricerca esterna**: bug bounty su HackerOne post-launch.
- **Crypto review**: terza parte per moduli sensitive (key derivation, descriptor parsing, miniscript).

### 9.4 Safe defaults for advanced features

- Miniscript editor: validatore + simulator + warning su pattern non-standard.
- MuSig2: warning su loss-of-share scenarios.
- Inheritance: drill drill drill.

---

## 10. Roadmap & milestone

> **2026-10-23 — roadmap originale (maggio 2026), non più quella attiva.** Le voci marcate
> *(superato)* presuppongono firma o custodia e non si faranno in BHODL; quelle marcate
> *(da ripensare)* restano ma come tracking in sola lettura. Lo stato reale è nel README e
> nel `docs/handbook.md` §8.

### Phase 0 — Fondamenta (mese 1-2)

- Setup monorepo Rust + Tauri scaffold + Flutter scaffold.
- Decisioni stack frontend (entro fine fase 0).
- Libreria `core` con tipi base.
- Bitcoin testnet + signet + regtest harness.
- CI multi-OS.
- Design system v0 (figma + storybook).

### Phase 1 — MVP wallet on-chain (mese 3-5)

- Single-sig SegWit/Taproot. *(superato 2026-10-23)*
- PSBT v2 import/export. *(superato 2026-10-23)*
- Coin control base. *(superato 2026-10-23)*
- Hardware integration: COLDCARD + Jade + BitBox02 (i 3 più diffusi). *(superato 2026-10-23)*
- BIP329 labels base.
- Output descriptor backup.
- Esplora connection.
- App desktop + mobile alpha.

### Phase 2 — Privacy & Lightning (mese 6-8)

- Silent Payments BIP352 send + receive. *(superato 2026-10-23)*
- Payjoin BIP77 send. *(superato 2026-10-23)*
- BIP324 transport.
- Lightning via Breez SDK Liquid (path A). *(da ripensare: sola lettura)*
- Submarine swap Boltz. *(superato 2026-10-23)*
- LSPS1/LSPS2. *(superato 2026-10-23)*

### Phase 3 — Tracking core (mese 9-11)

- Cost basis lot per-UTXO.
- Categorizzazione manuale.
- Budget categorie.
- Net worth dashboard multi-layer.
- Tax export 1099-DA + CSV.
- Price oracle Mempool.space + multi-fiat.

### Phase 4 — Multisig & Inheritance (mese 12-15)

- Multisig N-of-M nativo. *(superato 2026-10-23)*
- Miniscript editor. *(superato 2026-10-23)*
- MuSig2. *(superato 2026-10-23)*
- Inheritance wizard. *(superato 2026-10-23)*
- Recovery drill. *(superato 2026-10-23)*
- Hardware integration estesa (Passport, Ledger, Trezor, SeedSigner). *(superato 2026-10-23)*

### Phase 5 — Auto-categorize & advanced UX (mese 16-18)

- Auto-categorization rules.
- Recurring detection.
- Sats-per-day, savings rate, DCA P&L.
- NWC server lato wallet. *(superato 2026-10-23)*
- E2E sync labels via Nostr.
- Spending Policies (sub-budget per app esterne).

### Phase 6 — Multi-asset (mese 19-22)

- Liquid + USDt nativo. *(da ripensare: sola lettura)*
- Cashu (CDK) tasca eCash. *(da ripensare: sola lettura)*
- Auto-melt soglia. *(superato 2026-10-23)*
- Fedimint. *(da ripensare: sola lettura)*

### Phase 7 — Frontier (mese 23+)

- Ark/Arkade. *(da ripensare: sola lettura)*
- Taproot Assets.
- BTCPay merchant connection.
- Open banking fiat side.
- LNURL-pay self-hosted endpoint. *(superato 2026-10-23)*
- BIP353 nome setup.
- Proof of Reserves BIP-322. *(superato 2026-10-23)*
- LDK Node path B. *(superato 2026-10-23)*
- RGB v0.12 stable se mercato lo richiede.

### Beta & launch

- **Closed beta**: mese 9 (post tracking core).
- **Public beta**: mese 15 (post inheritance).
- **v1.0**: mese 22.
- **v1.0 + multi-asset**: mese 24.

---

## 11. Rischi & mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| Silent Payments non maturo per receive scanning su mobile (cost) | Media | Alto | Path A: Breez SDK Liquid + LN Address come receive primario; SP come opt-in advanced fino a v1.5 |
| BIP329 estensione non accettata upstream | Media | Medio | Custom field opzionale; export rimane retrocompatibile |
| LDK Node troppo complesso per MVP | Alta | Medio | Path A Breez SDK Liquid in MVP, LDK Node come Path B advanced |
| Hardware vendor cambiamenti API | Media | Basso | Abstraction layer + integration test per vendor |
| Regulatori EU/MiCA classificano come "VASP" | Media | Alto | Self-custody puro + zero KYC + no fiat on-ramp diretto = wallet, non VASP |
| Dipendenza da Boltz (single point per swap) | Media | Medio | Architettura swap pluggable; aggiungere Mercury/Bull come alternative |
| Scope creep (vogliamo troppo) | Alta | Alto | P0/P1/P2/P3 disciplinato; commit a MVP scope |
| Performance Rust→FFI→UI mobile | Media | Medio | Benchmark presto su scenari worst-case (sync 10k tx) |
| Cost basis legal complexity (US/EU) | Alta | Alto | Disclaimer + delega export a tool fiscali specializzati; non siamo tax advisor |
| Privacy claims non tenute per qualche bug | Bassa | Critico | Audit terza parte; bug bounty; minimo dato necessario |

---

## 12. Riferimenti chiave (selezione)

### Spec & BIPs
- [BIP21 — URI scheme](https://github.com/bitcoin/bips/blob/master/bip-0021.mediawiki)
- [BIP78 — Payjoin v1](https://github.com/bitcoin/bips/blob/master/bip-0078.mediawiki)
- [BIP86 — Single-key P2TR descriptors](https://github.com/bitcoin/bips/blob/master/bip-0086.mediawiki)
- [BIP93 — Codex32](https://github.com/bitcoin/bips/blob/master/bip-0093.mediawiki)
- [BIP322 — Generic message signing](https://github.com/bitcoin/bips/blob/master/bip-0322.mediawiki)
- [BIP324 — V2 P2P transport](https://github.com/bitcoin/bips/blob/master/bip-0324.mediawiki)
- [BIP327 — MuSig2](https://github.com/bitcoin/bips/blob/master/bip-0327.mediawiki)
- [BIP329 — Wallet labels export](https://github.com/bitcoin/bips/blob/master/bip-0329.mediawiki) · [bip329.org](https://bip329.org)
- [BIP352 — Silent Payments](https://github.com/bitcoin/bips/blob/master/bip-0352.mediawiki)
- [BIP353 — DNS payment instructions](https://github.com/bitcoin/bips/blob/master/bip-0353.mediawiki)
- [BIP370 — PSBT v2](https://github.com/bitcoin/bips/blob/master/bip-0370.mediawiki)
- [BIP388 — Wallet policies](https://github.com/bitcoin/bips/blob/master/bip-0388.mediawiki)
- [Output descriptors](https://github.com/bitcoin/bitcoin/blob/master/doc/descriptors.md)
- [BOLT11 — Invoice encoding](https://github.com/lightning/bolts/blob/master/11-payment-encoding.md)
- [BOLT12 — Offer encoding](https://github.com/lightning/bolts/blob/master/12-offer-encoding.md)
- [BLIPs index](https://github.com/lightning/blips)
- [LSPS specs](https://github.com/BitcoinAndLightningLayerSpecs/lsp)
- [LNURL LUDs](https://github.com/lnurl/luds)
- [NIP-47 NWC](https://github.com/nostr-protocol/nips/blob/master/47.md) · [nwc.dev](https://nwc.dev)

### Librerie
- [BDK](https://github.com/bitcoindevkit/bdk) · [bdk_wallet](https://github.com/bitcoindevkit/bdk_wallet)
- [LDK](https://github.com/lightningdevkit) · [LDK Node](https://github.com/lightningdevkit/ldk-node)
- [Breez SDK Liquid](https://github.com/breez/breez-sdk-liquid)
- [LWK](https://github.com/Blockstream/lwk)
- [CDK Cashu](https://github.com/cashubtc/cdk)
- [Fedimint](https://github.com/fedimint/fedimint)
- [Boltz client](https://github.com/BoltzExchange/boltz-client)
- [rust-payjoin](https://github.com/payjoin/rust-payjoin)
- [bdk-sp](https://github.com/bitcoindevkit) (sperimentale Silent Payments)
- [rust-bitcoin/bip324](https://github.com/rust-bitcoin/bip324)
- [Ark / Arkade](https://github.com/ark-network/ark)
- [Mercury Layer](https://github.com/commerceblock/mercurylayer)
- [Mempool.space API](https://mempool.space/docs/api/rest)

### Wallet di riferimento (per UX/feature inspiration)
- [Sparrow](https://sparrowwallet.com) — gold standard coin control + BIP329
- [Liana](https://wizardsardine.com/liana/) — miniscript inheritance UX
- [Cake Wallet](https://cakewallet.com) — Payjoin v2 + Silent Payments mobile
- [Bull Bitcoin Mobile](https://bullbitcoin.com) — Payjoin BIP77, swap nativo
- [Bitkey](https://bitkey.world) — UX no-seed consumer
- [Theya](https://theya.us) — multisig collaborativo + reporting
- [Phoenix](https://phoenix.acinq.co) — LN async + splicing
- [AQUA](https://aquawallet.io) — multi-layer view (BTC + Liquid + USDT)
- [Strike](https://strike.me) — cost basis lots editing (custodial reference)

### Ispirazione finance tracking
- [Lunch Money](https://lunchmoney.app)
- [YNAB](https://ynab.com) (zero-based budgeting)
- [Monzo categorization model](https://monzo.com)
- [Rotki](https://rotki.com) (privacy-first portfolio)
- [Ghostfolio](https://ghostfol.io) (open source self-host)
- [Bitcoin Design Guide — Personal finance](https://bitcoin.design/guide/designing-products/personal-finance/)

---

## 13. Prossimi passi (da concordare)

1. Decisione **codename / brand**.
2. Setup **monorepo scaffolding** (Cargo workspace + Gradle KMP).
3. Bozza di **design system v0** (Compose Multiplatform tokens + Figma mirror).
4. Identificazione **2-3 LSP partner candidati** per LDK Node Path B.
5. Identificazione **vendor hardware target MVP** (3 di 7).
6. Decisione **licenza** (MIT consigliato per core, da confermare).
7. Apertura **GitHub repo** (privato/pubblico).
8. **Spike tecnico**: prototipo Compose Multiplatform + UniFFI bindings su BDK + LDK Node, su 3 piattaforme (Android + iOS + macOS) per validare la decisione frontend prima di committare.

## 14. Redesign dell'interfaccia desktop (ottobre 2026)

Proposte grafiche su artifact separati, una pagina per sezione con quattro
alternative (A rifinita, B terminale denso, C card visive, D bitcoin-first).
Ordine di lavoro: una sezione alla volta, scelta l'alternativa con l'utente,
poi implementazione + test + giro di controllo sugli screenshot (tema scuro
e chiaro).

1. **Portafoglio** — fatto (branch `feat/portfolio-class-cards`): card di
   classe, card di dettaglio per 19 categorie, dettagli per tipo (registry v6),
   vista Schede ⇄ Tabella.
2. **Home**, 3. **Transazioni**, 4. **Budget e bollette**, 5. **Obiettivi**,
   6. **Report**, 7. **Wallet**, 8. **Impostazioni** — proposte pronte, in
   attesa della scelta.
9. **Aiuto contestuale ovunque** (trasversale, richiesto 2026-10-25): un
   punto di domanda con tooltip accanto a ogni numero, indicatore, campo e
   impostazione che non si spiega da solo (cosa misura, come è calcolato, da
   dove arrivano i dati). Un solo componente riusabile (`HelpTip`),
   raggiungibile da tastiera e letto dagli screen reader; testi EN/IT nelle
   risorse. Si applica a ogni sezione mentre viene rifatta, partendo dal
   Portafoglio, e poi alle schermate non toccate dal redesign (wizard,
   hub sicurezza, dialoghi).

---

*Documento vivo. Versione 0.2 — maggio 2026.*

**Changelog**:
- v0.4 (2026-10-25): §14 piano del redesign desktop e aiuto contestuale (tooltip) ovunque.
- v0.3 (2026-10-23): BHODL diventa watch-only — nuova sezione "Decisione 2026-10-23"; note di superamento in §4.1–4.4, 4.7, 4.8, 4.10; voci di roadmap §10 marcate come superate o da ripensare.
- v0.2 (2026-05-04): pubblico target → mainstream + 3 tier; Lightning → dual path A/B; frontend → KMP + Compose Multiplatform; aggiornate sezioni 1.3, 4.3, 6.2 e le open questions (sezione poi rimossa).
- v0.1 (2026-05-04): prima stesura completa.

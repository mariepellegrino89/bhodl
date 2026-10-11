# BHODL — Analisi UI/UX vs best practice e concorrenti (2026-10-10)

**Base dell'analisi**

- **172 screenshot reali** dell'app (`main` @ `cbbdc23`), generati con l'harness dei test Compose e dati finti realistici.
  - Wallet signet con 15 tx, 6 UTXO (1 congelata) e 5 contatti.
  - Tracker con 3 wallet, 81 transazioni, budget, bill, 5 goal, 4 investimenti con lotti e vendite, asset manuali EUR/CHF e passività.
  - Temi light, dark e neon; finestre 1440×900 e 1024×720; versione italiana.
- **Ricerca su best practice e concorrenti**, con fonti: [`research-ux-competitors-2026-10.md`](research-ux-competitors-2026-10.md).
  - Bitcoin Design Guide, WCAG 2.2, Material 3.
  - 10 wallet: Sparrow, Electrum, Nunchuk, Liana, Wasabi, Blockstream App, Phoenix, Bull, BlueWallet, Bitkey.
  - 12 tracker: Kubera, Monarch, Copilot, YNAB, Lunch Money, Empower, Actual, Firefly III, Wealthfolio, Ghostfolio, Finary, ProjectionLab.
  - Tracker bitcoin-native.
- **Il confronto rispetta le decisioni di design già prese.**
  - Stile denso alla Bloomberg/Sparrow, con tabelle alla Kubera.
  - BTC come unità di misura; la volatilità in BTC è voluta e non va smussata.
  - Fiat solo sulle superfici operative.
  - Primer-flat come tema di default, neon solo opt-in.
  - IA del tracker a sezioni (Phase 9).

**Legenda della verifica**

- ✅ = visto da me sugli screenshot.
- 🔎 = segnalato durante le catture, da confermare.

Gli screenshot sono stati generati in una cartella temporanea e non sono committati.

---

## 1. Sintesi

1. **Il tracker è sulla strada giusta, il wallet no.**
   - **Tracker:** è già un'app desktop densa e tabellare, coerente con la direzione scelta. Tabelle allineate a destra con cifre monospace, barra di stato con rete, Tor/Clearnet e server, IA a sezioni, palette dei comandi Ctrl+K.
   - **Wallet (Dashboard, Send, History, Settings):** è ancora una colonna stretta centrata in stile mobile. Su 1440 px usa circa il 35% della larghezza, e History mostra 4 transazioni per schermata.
   - **Due linguaggi visivi nella stessa app.** È il problema di design più grande ed è proprio dove Sparrow, il riferimento dichiarato, è più forte.
2. **Ci sono bug di correttezza nei numeri.** In un prodotto finanziario vengono prima di qualunque lavoro estetico (§2).
3. **I18n rotta in entrambe le direzioni.**
   - Italiano nell'app in inglese: "Ultimo sync", chip "7g/30g/90g/1a", "Categoria", "Ultima", "Entrate/Uscite".
   - Inglese nell'app in italiano: la home del tracker.
4. **I formati dei numeri non sono uniformi.**
   - Separatori delle migliaia presenti solo in alcune tabelle ("4230118 sat" contro "4,230,118 sat").
   - BTC e sat mescolati nella stessa pagina.
   - Le entrate sono arancioni in History e verdi nel tracker.
5. **Rispetto ai concorrenti BHODL ha già vantaggi reali che nessun altro combina.**
   - Patrimonio netto in BTC come unità principale: solo Kubera lo fa, e senza sats.
   - Lotti con FIFO/HIFO/Specific-ID collegabili alle UTXO: è un buco di mercato.
   - Etichette BIP329 collegate alle categorie del tracker, regole, PayJoin, Tor e lockout del PIN.
   - **Il lavoro da fare è di forma e coerenza, non di funzionalità.**

---

## 2. Bug da correggere subito (P0, correttezza)

| # | Problema | Dove | Verifica |
|---|---|---|---|
| B1 | Il numero grande "Net worth" della home tracker è il **totale degli asset** (2.64288819 BTC) e ignora le passività. Il net worth vero, mostrato in Portfolio, è 2.46188819 BTC. | `30-tracker-home-*`, `34-tracker-portfolio-tall-*` | ✅ |
| B2 | La riga "Total (EUR)" degli investimenti mostra ₿0.11745293, ma la somma dei 4 investimenti è 0.2421 BTC (Asset portfolio: 0.24210850). La colonna QTY del totale mostra "2". | `34-tracker-portfolio-tall-dark` | ✅ |
| B3 | Budget, "Income breakdown": le categorie di **spesa** (Dining, Rent, Groceries…) compaiono tra le entrate con varianza −100%. | `32-tracker-budget-dark` | ✅ |
| B4 | I budget mensili sono confrontati con la spesa dell'intero periodo selezionato. Con 90g, Rent risulta al 198% e Groceries al 206%. Il budget va riportato alla scala del periodo, oppure il periodo del budget va bloccato al mese. | `32-tracker-budget-dark` | ✅ |
| B5 | Il fiat sotto il net worth mostra €0.00 in dark e €154,397 in light con gli stessi dati. Anche la variazione "+€0.00" è sbagliata. | `30-tracker-home-dark` vs `-light` | ✅ (€0.00 visto) 🔎 causa |
| B6 | Passività "Mortgage share" mostra in rosso il testo "Remaining 30% share" al posto dell'importo. Le bill mostrano la descrizione dove andrebbe l'importo. | `34-…portfolio-tall`, `32-…budget-tall` | ✅ (mortgage) 🔎 (bill) |
| B7 | L'asset in CHF è formattato "F3,200.00". | `34-…portfolio-tall` | ✅ |
| B8 | Portfolio conta "BTC Wallets (4)", la home "3 wallets". | `34-…portfolio-tall`, `30-…home` | ✅ |
| B9 | Gli investimenti mostrano "no rate" come costo e "—" in P&L BTC su tutte le righe, anche se il costo in EUR c'è. Con il tasso storico disponibile, il P&L in BTC (il messaggio centrale di Bitcoin Lens) non viene mostrato. | `34-…portfolio-tall`, `38-…no-rate-*` | ✅ |
| B10 | Unlock in lockout: "Wrong PIN. Wait 5s" è grigio e non nel colore d'errore, il pulsante Unlock resta attivo durante l'attesa, e ci sono due "indietro" (← e Back). | `09-auth-unlock-lockout-countdown-*` | ✅ |
| B11 | Send: il campo indirizzo taglia l'**inizio** dell'indirizzo, così la parte che l'utente controlla non si vede. Gli interruttori "Send MAX" e "Coin control" spenti sono quasi invisibili in dark. | `12-send-filled-dark` | ✅ |
| B12 | Due selettori di periodo nella home tracker: globale "7g/30g/90g/1a/All" e del grafico "7D/30D/90D/1Y/All". La decisione di Phase 9 prevedeva un periodo unico. | `30-tracker-home-*` | ✅ |
| B13 | "Refresh balances" è testo libero sotto la tabella wallet, e ci sono card vuote tagliate in fondo alla home. | `30-tracker-home-dark` | ✅ |
| B14 | Overflow: etichette dentro le ciambelle, "Totale" del Sankey coperto, KPI "Inflow win…" troncato, terzo destinatario del batch tagliato, indentazione dell'help Esplora. | vari | 🔎 |
| B15 | I dialog in tema light usano lo sfondo lavanda di default di Material invece dei colori dell'app. | dialog `*-light` | 🔎 |

> B1, B2, B3, B4, B5 e B9 sono **errori di calcolo o di stato**, non di grafica: vanno trattati come bug funzionali, con test.

---

## 3. Dove BHODL è rispetto a best practice e concorrenti

### 3.1 Punti di forza già presenti

| Area | BHODL oggi | Confronto |
|---|---|---|
| Net worth in BTC come unità | Hero e tabelle in BTC/sat, fiat secondario | Solo Kubera ha lo stesso posizionamento ("₿80→₿125 vs $770K→$6.23M") e non usa i sats. |
| Lotti e metodo di costo | FIFO/HIFO/Specific-ID con scelta dei lotti nella vendita | Nessun concorrente mostra quali lotti vengono consumati con ciascun metodo. Monarch ha i lotti ma non il metodo; Koinly non ha un vero Spec-ID. |
| Barra di stato | Rete, Tor/Clearnet, server, numero di wallet, ultimo sync | Equivalente a quella di Sparrow, considerata best in class. |
| Sicurezza | Lockout per scope, nuovo PIN per seed ed export, wipe con "DELETE", blocco per inattività, limite fee e conferma fee alta, PayJoin con riepilogo | Allineata alla security page del BDG. La ricerca segna "high-fee guard" come mancante: **esiste già** (audit 2026-10). |
| Coin control | Selezione manuale e badge "FROZEN · not spendable" | Pari a Sparrow ed Electrum. |
| Etichette | BIP329 import/export e categorie | Pari a Sparrow e BlueWallet 8.0.2. |
| Stati vuoti | Le card non spariscono, zero-state con CTA (Phase 9) | Conforme a Carbon/BDG. |

### 3.2 Lacune rispetto ai migliori

| Area | Riferimento | Gap in BHODL |
|---|---|---|
| Layout del wallet desktop | Sparrow: un tab per wallet e sotto-tab Transactions / Send / Receive / Addresses / UTXOs / Settings, tutto a tutta larghezza | Il wallet è una colonna mobile centrata con pulsanti grandi ("History", "Address book", "Settings" come pulsanti-pagina). |
| Lista transazioni | BDG activity; Sparrow tabella | Card alte con importo, txid e azioni: 4 tx per schermata, nessuna colonna, ordinamento o filtro. |
| Fee | Sparrow: fee chart + Recent Blocks; BDG: preset con **totale in sat e tempo stimato** | Solo 3 chip in sat/vB, senza totale stimato né ETA. |
| Diagramma della transazione | Sparrow: diagramma input→output | La review mostra elenchi, non il flusso. |
| Indirizzi | Sparrow 2.4 e BlueWallet: indirizzi a blocchi di 4 caratteri in monospace | Indirizzo su una riga in font proporzionale, troncato all'inizio. |
| Unità | Sparrow 2.5.5: unità auto (sats sotto 1 BTC, BTC sopra); BIP177 come toggle | Sat e BTC mescolati senza regola; separatori assenti in metà schermate. |
| Privacy mode | Sparrow: nasconde anche tooltip e menu; Bitkey: anche lo storico | Da verificare che il "Hide balances" copra assi dei grafici, tooltip, palette ed export. |
| Ricezione | Liana v15: etichetta prima dell'indirizzo; BDG: avviso di riuso | La categoria e l'etichetta vanno chieste **prima**, così i fondi in arrivo sono già categorizzati. È il ponte naturale tra wallet e tracker. |
| Security hub | Bitkey Security Hub, Blockstream Security tab | Manca una schermata unica con stato backup, PIN, auto-lock, Tor, ultimo export etichette. |
| Accessibilità | WCAG 2.2 AA: contrasto 4.5:1, target 24 px, focus visibile | Interruttori spenti invisibili; righe dense sotto i 24 px; nessuna verifica con screen reader (`jdk.accessibility` + NVDA). |
| Colori guadagno/perdita | Okabe-Ito, ±/▲▼ ridondanti, toggle "convenzione asiatica" | I token Okabe-Ito sono già nel tema, ma le entrate sono arancioni in History e verdi nel tracker. Nel BDG le uscite del wallet sono **neutre**, non rosse. |
| Regole | Monarch: "crea regola dalla modifica"; Actual: anteprima e stage | Da confrontare con il rules engine attuale: manca il prompt "Fallo sempre per questo payee?". |
| Budget | Monarch Flex, YNAB targets; NN/g: bullet bar invece di gauge e ciambelle | Barre presenti; mancano il periodo mensile coerente (B4) e la spiegazione del perché un budget in sats cambia con il prezzo. |
| Report | Monarch Sankey cliccabile; NN/g: vista tabellare per ogni grafico | Sankey presente; manca il toggle grafico/tabella, utile anche per accessibilità e tastiera. |
| Mobile readiness | Canonical layouts M3 (`NavigationSuiteScaffold`, `ListDetailPaneScaffold`) | Le tabelle dense non hanno una **priorità di colonne** per gli schermi stretti. |

### 3.3 Dove la ricerca sfida la direzione scelta

- **Densità contro accessibilità.**
  - Le righe da 24 px confliggono con il target WCAG 2.5.8.
  - **Proposta:** densità "Compact" da 32 px di default, "Ultra-dense" da 24 px opt-in.
- **Solo dark.**
  - Sparrow da 2.5.5 segue il tema di sistema.
  - Gli screenshot light di BHODL sono buoni: tenerli di prima classe (System di default).
- **Neon.**
  - Verde e rosso saturi su nero spesso falliscono il contrasto non testuale e collassano per chi ha deficit cromatici.
  - Va bene opt-in, ma va verificata la palette.
- **Principianti.**
  - Il BDG chiede divulgazione progressiva: coin selection automatica di default, preset prima della fee personalizzata, backup saltabile ma ricordato.
  - BHODL lo fa già nel Send. Da mantenere quando si passa al layout denso: grafico fee e diagramma restano richiudibili.
- **BIP177 (₿ = sats).** È ancora Draft e contestato. Consigliato come opzione, non come default.
- **Fiat operativo.**
  - Bollette, costo fiscale e P&L EUR sono fiat per natura.
  - Vanno presentati come superfici "operative/fiscali" esplicite, senza che il fiat torni nel KPI principale.
  - È coerente con la decisione già presa.

---

## 4. Raccomandazioni in ordine di priorità

### P0 — prima di qualunque nuova feature

1. **Correggere B1–B15**, con un test per ognuno dei bug di calcolo (B1–B5, B9).
2. **Formattazione dei numeri in un unico componente.**
   - `Money`/`Amount` con separatori secondo la lingua e cifre tabulari.
   - Numero fisso di decimali per colonna: sats interi oppure BTC a 8 decimali, mai mescolati nella stessa tabella.
   - Unità nell'intestazione di colonna, non ripetuta in ogni cella.
   - Una lint rule o un test che vieti `"$x sat"` costruito a mano.
3. **I18n completa.**
   - Estrarre tutte le stringhe hardcoded (tracker, report, palette).
   - Aggiungere un test che fallisce se in modalità EN compaiono stringhe IT, e viceversa: confronto delle chiavi tra le due lingue più una scansione degli hardcoded.
4. **Un solo selettore di periodo** nella home tracker (B12), come deciso in Phase 9.

### P1 — wallet desktop (il salto più visibile)

5. **Rifare il wallet nel linguaggio del tracker**, cioè a tutta larghezza.
   - Sotto-tab in stile Sparrow: *Transazioni · Invia · Ricevi · Indirizzi · UTXO · Impostazioni wallet*.
   - Il saldo diventa header, i pulsanti grandi spariscono.
   - **Transazioni:** tabella ordinabile con data, etichetta/categoria, importo allineato a destra, stato, conferme. Pannello di dettaglio laterale (`ListDetailPaneScaffold`) invece di una pagina separata.
6. **Invio.**
   - Preset fee che mostrano **totale in sat, ETA e equivalente fiat operativo**, con fee personalizzata in un pannello richiudibile.
   - Diagramma input→output nella review.
   - Indirizzo in monospace a blocchi da 4, mai troncato all'inizio.
7. **Ricezione con etichetta prima dell'indirizzo.**
   - Prima si scelgono etichetta e categoria del tracker, poi si mostra l'indirizzo nuovo.
   - Indirizzo a blocchi, avviso di riuso.
8. **Stati delle transazioni.**
   - Ciclo In attesa → In conferma (1–5) → Confermata (senza badge).
   - "Sostituita" raggruppata sotto la sostituta con chip "Fee aumentata".
   - "Accelera" (RBF/CPFP) inline con il costo esatto.
   - Uscite neutre, entrate con segno "+", il rosso solo per gli errori (BDG).

### P1 — tracker

9. **Portfolio come foglio alla Kubera** (foglio → sezione → riga).
   - Colonne: valore in ₿/sats, variazione sul periodo selezionato, % del net worth, ultimo aggiornamento.
   - La variazione per riga segue il periodo del grafico (Copilot).
10. **Vista della scelta dei lotti** al momento della vendita.
    - FIFO, HIFO e Spec-ID a confronto, ciascuno con il proprio P&L, prima di confermare.
    - In prospettiva, collegamento lotto↔UTXO.
    - È il differenziatore più forte trovato dalla ricerca.
11. **Regole: "crea regola dalla modifica"**, con anteprima ("si applica a N transazioni esistenti") e ordine esplicito.
12. **Budget.**
    - Periodo mensile coerente (B4).
    - Nota inline "perché il budget in sats si è mosso" (il prezzo, non la spesa), coerente con la tesi della volatilità come segnale.
    - Chip "Sotto budget / A rischio" sempre con testo.

### P2 — rifinitura e preparazione

13. **Privacy mode davvero globale.**
    - Ctrl+Shift+H e un'icona nella barra di stato.
    - Copre assi, tooltip, palette ed export.
    - Opzione "avvia nascosto".
14. **Security Hub.** Backup verificato e data, PIN, auto-lock, Tor, wallet watch-only e di firma, ultimo export BIP329. Promemoria distanziati, mai durante un invio.
15. **Accessibilità.**
    - Densità Compact di default.
    - Contrasto degli interruttori spenti.
    - Stato di focus distinto dalla selezione nelle tabelle.
    - Toggle grafico↔tabella per ogni grafico.
    - Verifica con NVDA.
16. **Unità.** Modalità auto in stile Sparrow come default, BIP177 come opzione, doppia visualizzazione (sats + BTC al passaggio del mouse) durante l'onboarding.
17. **Mobile readiness.**
    - Definire **adesso** la priorità delle colonne di ogni tabella (cosa resta a 2–3 colonne).
    - Mappare rail → bottom bar con `NavigationSuiteScaffold`.
    - Impostare una `minSize` della finestra desktop.

---

## 5. Decisioni prese (2026-10-10)

1. **Wallet a sotto-tab in stile Sparrow**, a tutta larghezza (Transazioni · Invia · Ricevi · Indirizzi · UTXO · Impostazioni wallet).
2. **Tema di default:** segue il sistema (light/dark); Dark e Neon restano selezionabili.
3. **Unità di default:** sempre sats, configurabile (sats / BTC / Auto); notazione BIP177 come opzione.
4. **Densità di default:** Compact con righe da 32 px; Ultra-dense da 24 px come opzione.

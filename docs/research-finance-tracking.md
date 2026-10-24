# Ricerca — logiche funzionali di un'app di finance tracking

> **Nota (2026-10-23):** dal 2026-10-23 BHODL è watch-only: non tiene chiavi private e
> non può inviare bitcoin. Le parti di questo documento su seed, firma, invio, PayJoin,
> backup o mirror signing↔tracker sono storiche; il design attuale è in
> `docs/handbook.md`.

> Documento di ricerca, 2026-05-20. Sintesi dello stato dell'arte
> applicato a BHODL. Le sezioni "→ BHODL" sono ipotesi/proposte,
> non decisioni: le decisioni si formalizzano in `PLAN.md`.

---

## 0. TL;DR (cosa ti porta a casa)

Le finance app si dividono in **sette scuole di pensiero**, ortogonali fra loro:

| Scuola | Esempi | Domanda che risolve |
|---|---|---|
| Categoria-budget | Mint, Copilot, Simplifi | "Dove sono andati i miei soldi questo mese?" |
| Zero-based / envelope | YNAB, Actual Budget | "Ogni euro che entra ha già un lavoro?" |
| Flex budgeting | Monarch | "Sto rispettando il margine flessibile globale?" |
| Double-entry | Beancount, Firefly III, Ledger | "I libri tornano? Posso fidarmi dei numeri?" |
| Net-worth-first | Kubera, Empower, Maybe | "Quanto sono ricco e in che direzione vado?" |
| Portfolio-first | Ghostfolio, Snowball | "Come performano i miei investimenti vs benchmark?" |
| Privacy-first crypto-aware | Rotki, Sparrow | "Tutto questo senza dare i miei dati a terzi" |

Sopra queste scuole vivono **cinque oggetti dati canonici**: Accounts, Transactions, Categories/Tags, Budgets, Goals. Tutti gli altri concetti (sinking funds, bucket NW, watch wallets) sono varianti di questi cinque.

Il modello dati che resta in piedi al variare della scuola è il **double-entry** (anche se nascosto sotto un'UI categoria-only): ogni movimento ha due gambe — una fonte e una destinazione — e ogni Account ha esattamente un saldo derivato. Mint/Copilot/Monarch lo fingono male e quasi tutti soffrono di double-counting sui transfer tra account.

**Per BHODL** la mossa interessante è prendere il double-entry come ossatura (i transfer sat→sat sono già double-entry per costruzione: txid + utxo), strato sopra metterci la grammatica YNAB (envelope/zero-based) come opt-in per chi vuole governance attiva, e mantenere il modello Kubera/Maybe come default per gli utenti "voglio sapere quanto sono ricco senza budgetare". I bucket di Phase 7.2.4 sono la base per il modello Kubera. Le buste/categorie restano per chi vuole quel livello in più.

---

## 1. Le scuole di pensiero

### 1.1 Categoria-budget (Mint, Copilot, Simplifi, vecchio standard)
Tre stadi: (1) aggreghi gli account via Plaid/CSV/manuale, (2) ogni transazione riceve una **category** (uno-a-uno, gerarchia tipicamente 2 livelli), (3) per ogni categoria imposti un **budget mensile** e l'app ti mostra l'avanzamento.

Vantaggi: bassa frizione, l'utente non deve fare niente all'inizio del mese. Limite cronico: i transfer tra account vengono spesso contati come spesa+entrata (double-counting), perché il modello dati è single-entry travestito. Mint e Personal Capital sono i casi più noti.

Copilot ([Copilot Help — Categories FAQ](https://help.copilot.money/en/articles/10216528-categories-faq)) si distingue per la categorizzazione ML "che impara dalle correzioni" (93% di accuratezza al primo pass), e per il **Rebalancing** automatico: ridistribuisce i budget mensili in base al consumo effettivo.

### 1.2 Zero-based / envelope (YNAB, Actual Budget, EveryDollar)
Filosofia: **ogni euro ha un lavoro prima di essere speso**. YNAB ([The YNAB Method](https://www.ynab.com/ynab-method)) codifica quattro regole:

1. **Give every dollar a job** — appena entra income, lo assegni a una categoria.
2. **Embrace true expenses** — divide le spese annuali (assicurazione, regali, tasse) in dodicesimi mensili (sinking funds di fatto).
3. **Roll with the punches** — se sfori una categoria, sposti soldi da un'altra (no rigid budget).
4. **Age your money** — obiettivo finale: vivere del salario del mese scorso.

Actual Budget ([Envelope Budgeting](https://actualbudget.org/docs/getting-started/envelope-budgeting/)) lo chiama "zero-sum budgeting": ogni categoria è un'envelope, i soldi non assegnati restano in "To Be Budgeted".

Punti chiave del modello:
- Le categorie sono **buckets di soldi**, non solo etichette retrospettive — possono accumulare residui mese su mese.
- I **goal templates** (Actual) permettono di definire regole tipo "10% del net income va in Savings" che l'app auto-allocata.

### 1.3 Flex budgeting (Monarch, Lunch Money)
Compromesso fra (1) e (1.2). Le spese si dividono in tre macro-buckets: **fixed** (affitto, abbonamenti), **flexible** (cibo, intrattenimento), **non-monthly** (regali, ferie). Solo il flexible bucket ha un numero target unico ([Monarch — Flex Budgeting](https://help.monarch.com/hc/en-us/articles/32125337244052-Using-Flex-Budgeting)). L'utente si concentra su una cifra (es. "ho ancora €600 di flex questo mese") invece che su 30 categorie.

Lunch Money ([Budget Categories](https://lunchmoney.app/features/budgeting/)) lavora con budget settimanali, bisettimanali, bi-mensili o custom per chi ha stipendi irregolari.

### 1.4 Double-entry (Beancount, Ledger, hledger, Firefly III)
Modello accounting "vero" applicato al personal finance. Ogni transazione ha almeno due righe (debit + credit) che bilanciano a zero. Cinque tipi root: **Assets**, **Liabilities**, **Income**, **Expenses**, **Equity** ([Beancount Concepts](https://thebeanledger.com/posts/concepts/)).

Naming convention Beancount ([Beancount account management](https://beancount.io/blog/2026/02/08/beancount-account-management-complete-guide)):
```
Assets:US:BofA:Checking
Liabilities:CA:RBC:CreditCard
Income:US:Acme:Salary
Expenses:Food:Groceries
Equity:Opening-Balances
```
Profondità tipica: 2-3 livelli per expenses, 3-4 per asset strutturati (es. `Assets:US:Vanguard:401k:VTSAX`), oltre 5 raramente utile.

Firefly III ([Introduction and features](https://docs.firefly-iii.org/explanation/firefly-iii/about/introduction/)) prende lo stesso modello e ci mette sopra: budgets, categories, tags, **piggy banks** (= sinking funds), bills (= recurring), rules engine.

Vantaggio del double-entry: matematicamente impossibile fare double-counting sui transfer, perché un transfer è semplicemente "Assets:Checking → Assets:Savings", non genera niente in `Expenses` o `Income`. L'errore di Mint diventa impossibile per costruzione.

### 1.5 Net-worth-first (Kubera, Empower, Maybe Finance)
Filosofia: il budget mensile è meno importante della **direzione del net worth nel tempo**. Kubera ([Kubera homepage](https://www.kubera.com/)) si presenta esplicitamente come "the balance sheet for those who manage their own wealth" — segna l'andamento del patrimonio non le spese, quindi non implementa né budget né categorie sulle transazioni.

Concetti core:
- **Assets vs Liabilities** come unica dimensione orizzontale.
- Categorie di asset: liquid cash, brokerage, retirement (401k/IRA), real estate, vehicles, private investments, crypto, alternatives (arte, domain, collectibles).
- **Allocazione percentuale** per asset class + benchmark vs gruppo di pari ([Kubera review](https://www.kubera.com/blog/tracking-net-worth)).
- Riconciliazione manuale per asset illiquidi (real estate via Zillow-like, auto via VinAudit).

Maybe Finance ([Maybe GitHub](https://github.com/maybe-finance/maybe)) — recentemente open-sourced sotto AGPL — porta lo stesso modello con la riconciliazione "smart" che NON crea una adjustment transaction (semplicemente sovrascrive il saldo storico) — utile per asset il cui valore non viene da una somma di transazioni ma da un esterno (es. "la casa vale €350k oggi").

### 1.6 Portfolio-first (Ghostfolio, Snowball)
Per chi ha 80%+ del patrimonio in investimenti. Tracking di **lots** (= unità d'acquisto con prezzo e data), **cost basis** (FIFO/LIFO/HIFO/specific-ID), **P&L** realizzato vs unrealized, **dividendi**, **corporate actions** (split, merger, spin-off), **rebalancing** rispetto a target allocation.

Ghostfolio ([Ghostfolio docs](https://github.com/ghostfolio/ghostfolio)): self-hosted, supporta stocks/ETF/crypto, FIRE calculator integrato, ROAI (Return on Average Investment) come metrica unica nel tempo, allocazione per asset class / currency / sector / region.

### 1.7 Privacy-first locale (Rotki, Sparrow, Maybe self-hosted)
Sottoinsieme che esiste in tutte e sei le scuole sopra ma con vincolo: **dati locali, encryption obbligatoria, no cloud sync** (o sync opt-in via servizio cifrato e-to-e).

Rotki ([Rotki review](https://cryptoadventure.com/rotki-review-2026-privacy-first-local-portfolio-tracking-and-crypto-accounting/)): SQLCipher per encryption at rest, "privacy mode" che oscura gli importi nelle history note (shoulder-surfing protection), event decoding readable dei tx on-chain. È il riferimento crypto-aware.

→ **BHODL** vive qui di default: SQLCipher con device PIN già implementato, privacy mask già in Phase 7.2.

---

## 2. Il modello dati canonico

I cinque oggetti che ogni app delle scuole 1.1–1.6 ha, anche se nascosti.

### 2.1 Accounts (= dove vivono i soldi)
Tassonomia minima dall'industria, derivabile da Fidelity / Plaid / Beancount:

**Assets**
- `Cash`: checking, savings, money market, fisico ("contanti in tasca")
- `Crypto`: hot wallet, cold storage, exchange custodial → per BHODL i watch-only sono di questo tipo
- `Brokerage`: taxable investment (azioni, ETF, fondi)
- `Retirement`: 401k, IRA, Roth IRA, equivalenti UE/IT (PIP, fondo pensione, TFR)
- `Real estate`: primary residence, rental, land
- `Vehicles`: auto, moto, barche
- `Collectibles / alternatives`: gioielli, orologi, arte, domain, business equity
- `Loans-to-others`: soldi prestati che ti devono ritornare

**Liabilities**
- `Credit cards`: revolving short-term
- `Loans`: personal, auto, student
- `Mortgages`: home, second home
- `Tax owed`: ratei tassa non ancora pagati
- `Other debts`: family loans, BNPL

**Income** (Beancount-style, rispondono a "da dove arriva")
- `Salary / wages` (active)
- `Business / freelance` (active)
- `Dividends / interest / capital gains` (portfolio)
- `Rental` (passive)
- `Royalties / affiliate` (passive)

**Expenses** (categorie spese; vedi 2.4)

**Equity**
- `Opening balances` (per quando importi un saldo iniziale senza storico)
- `Adjustments` (le riconciliazioni manuali per non sporcare le altre)

→ **BHODL oggi** ha solo `Assets:Crypto:Watch` (i wallet) e `Liabilities:Other` (le manual liability). Il resto manca. Phase 7.2.4 ha aggiunto `bucket_kind` ai goal ma è semantica sopra i wallet, non un account separato.

### 2.2 Account hierarchy + naming
Pattern Beancount: `Type:Country:Institution:Account:SubAccount` ([BeanFlow account naming](https://beanflow.ai/academy/account-naming)). 2-3 livelli sono il sweet spot. UI moderne (Monarch/Copilot) collassano la gerarchia in **Groups + Accounts** (due livelli) per leggibilità — sotto al cofano restano potenzialmente più profonde.

Per BHODL conviene riservare il modello a 3 livelli max:
```
Assets:Bitcoin:Cold
Assets:Bitcoin:Hot Mobile
Assets:Investments:Broker
Liabilities:Mortgage:Home
```

### 2.3 Transactions
La struttura minima — qualunque scuola — ha questi campi:
- `id` interno (univoco)
- `external_id` / `txid` (per dedup quando viene da bank feed o blockchain)
- `timestamp` (data effettiva, separata da `posted_date`)
- `amount` (in unità minore intera — sat per BTC, centesimi per fiat; **mai float**, [database design](https://www.geeksforgeeks.org/dbms/how-to-design-a-database-for-financial-applications/))
- `currency` (ISO 4217 o "BTC", "SAT")
- `description` / `memo` raw dalla fonte
- `payee` / `merchant` normalizzato
- `category_id` (one-to-one)
- `tags[]` (many-to-many, vedi 2.4)
- `account_id` (la gamba primaria)
- `transfer_account_id` (la gamba secondaria se è un transfer)
- `status` (pending / cleared / reconciled / void)
- `split_id` (per gestire split — vedi 2.3.1)
- `attachments[]` (receipt, foto, PDF — vedi 3.15)

**2.3.1 Split transactions**: una transazione (es. ricevuta Costco $500) divisa in più categorie ($300 groceries + $150 household + $50 gift). YNAB ([Split Transactions guide](https://support.ynab.com/en_us/split-transactions-a-guide-SJLEKwY0q)) e Lunch Money lo modellano come N child-transactions con stesso parent_id che sommano al totale.

**2.3.2 Transfer detection**: il problema #1 di Mint/Personal Capital. Soluzione canonica: una categoria speciale `Transfer` esclusa dai totali budget e spesa, oppure (meglio) un campo `transfer_account_id` che lega le due gambe del transfer. Monarch ([Transfers and Credit Card Payments](https://help.monarch.com/hc/en-us/articles/360048393292-Transfers-and-Credit-Card-Payments)) detecta i transfer automaticamente quando trova due tx di importo opposto in finestra di pochi giorni fra account dell'utente.

**2.3.3 Status / clearing**: pending vs cleared vs reconciled. YNAB usa il flag `cleared` (vedi nello statement della banca) e `reconciled` (utente ha confermato il saldo a una data X). Beancount lo modella con il flag `*` (cleared) vs `!` (incomplete).

### 2.4 Categories vs Tags
**Categorie**: gerarchiche (2-3 livelli), one-per-transaction, **budget-able** (puoi assegnare un budget mensile alla categoria).

**Tag**: piatti, many-to-many, **non budget-able**, dimensione ortogonale ("Vacanza Sicilia 2026", "Pet expenses", "Lavoro da casa") che taglia trasversalmente le categorie ([Copilot — Tags vs Categories](https://help.copilot.money/en/articles/9554367-tags-vs-categories)).

Plaid taxonomy ([Plaid update](https://plaid.com/blog/transactions-categorization-taxonomy/)) usa due livelli: 16 primary categories × ~104 detailed sub-categories. Esempi di primary: `INCOME`, `TRANSFER_IN`, `TRANSFER_OUT`, `LOAN_PAYMENTS`, `BANK_FEES`, `ENTERTAINMENT`, `FOOD_AND_DRINK`, `GENERAL_MERCHANDISE`, `HOME_IMPROVEMENT`, `MEDICAL`, `PERSONAL_CARE`, `RENT_AND_UTILITIES`, `TRANSPORTATION`, `TRAVEL`, `GENERAL_SERVICES`, `GOVERNMENT_AND_NON_PROFIT`.

→ **BHODL oggi** ha categorie piatte (1 livello) sui PaymentEvent. Tag-set assente. Per Phase 7.x serve almeno gerarchia 2 livelli + tag piatti, in linea con Plaid.

### 2.5 Buckets / pools
Concetto introdotto da Monarch (Fixed/Flex/Non-monthly), formalizzato da Phase 7.2.4 in BHODL. Vivi sopra le categorie: ogni categoria è assegnabile a un bucket. I bucket non rimpiazzano le categorie — le **aggregano** semanticamente per il dashboard.

In BHODL i bucket di 7.2.4 sono **bucket di asset** (= dove sta il valore), che è un uso diverso da Monarch (= che tipo di spesa). Sono **mutuamente compatibili**:
- Bucket di asset = "quale slice del net worth"
- Bucket di spesa = "che tipo di outflow" (fixed/flex/non-monthly)

→ Possiamo introdurli entrambi quando serviranno, lasciando i nomi disambiguati.

### 2.6 Goals
Tre famiglie distinte ma spesso confuse:

**(a) Sinking funds** — soldi accumulati per spese future **conosciute e datate**. Es. "$1000 a dicembre per regali, accantono $83/mese da gennaio" ([Discover — sinking fund](https://www.discover.com/online-banking/banking-topics/what-is-a-sinking-fund/)). Sono di fatto categorie YNAB con "true expenses" + scheduled transactions: la categoria accumula mese su mese fino al target → la spesa viene dal saldo accumulato.

**(b) Savings goals (general)** — accumulo verso un obiettivo **senza data fissa** o con data lontana. Es. "Casa €100k", "Emergency fund 6 mesi di spese". Differiscono dai sinking fund per la rilassatezza sulla data.

**(c) Allocation goals** — "voglio che X% del mio net worth sia in Y". Phase 7.2.4 di BHODL è questo. Kubera implementa benchmark di allocation contro un gruppo di pari anonimizzato.

**Framework SMART** ([WECU SMART goals](https://www.wecu.com/setting-smart-financial-goals/)) si applica a tutti e tre: Specific, Measurable, Achievable, Relevant, Time-bound. La forma minima salvabile è `{name, target_amount, target_date, current_amount, bucket_or_account, status}`.

→ **BHODL oggi** in Phase 7.2.4 ha (c) coperto. Per (a) servono "schedule + categoria che accumula", per (b) basta togliere il target_date dalla (a). Una struttura unica `goals` con `kind` enum {sinking, savings, allocation} è economica e copre tutto.

### 2.7 Budgets
Tre flavour:
- **Hard budget mensile per categoria** (Mint stile, Copilot)
- **Envelope con rollover** (YNAB, Actual)
- **Flex budget aggregato** (Monarch — un solo numero da rispettare)

Implementazione minima: tabella `budgets(category_id, period, amount_minor)` dove `period` è `monthly` / `weekly` / `quarterly` / `yearly` / `custom`. Per YNAB-style aggiungere `rollover_strategy` (carry, reset, return-to-pool).

### 2.8 Net worth = Σ Assets − Σ Liabilities
Equazione fondamentale. Tutte le scuole 1.5–1.7 ci girano intorno; le scuole 1.1–1.3 spesso non la calcolano. Beancount la deriva da: `Net Worth = Assets balance + Liabilities balance` (Liabilities sono già negative nel double-entry).

Snapshot storico: il pattern standard è una tabella `net_worth_snapshots(date, total_asset, total_liability, net)` aggiornata giornalmente (o serializzata on-the-fly dai tx + saldi storici, come fa BHODL con `computeNetWorthSeries`).

---

## 3. Flussi funzionali end-to-end

Vista user-journey, non più data model. Ogni sotto-sezione include "→ BHODL" con il mapping al codice esistente o gap.

### 3.1 Onboarding (primo avvio)
Pattern moderno ([Plaid onboarding](https://plaid.com/resources/fintech/fintech-onboarding-process/), [Appcues fintech](https://www.appcues.com/blog/fintech-onboarding-examples)):

1. Welcome + value proposition (1 schermo)
2. Account creation + opt-in (2FA, biometrics) — BHODL: device PIN gate
3. **Primo account aggiunto** — banco linking via Plaid, oppure manuale ("inserisci saldo corrente")
4. **Goal-based personalization** (Albert pattern): "qual è la tua priorità? saving, debt payoff, retire early?" → app suggerisce i template di categoria + i widget del dashboard appropriati
5. Progressive disclosure: le feature avanzate (budget, goals, investments) si scoprono dopo la prima settimana, non subito

→ **BHODL** oggi: welcome + PIN + (wizard wallet OR add watch). Manca il "goal-based personalization" — chiedere "fondo emergenza? DCA? long-term hold?" per pre-popolare i goal.

### 3.2 Cattura transazioni
Quattro canali:
- **Auto-import** via Plaid / Open Banking PSD2 / API exchange (per crypto): l'ideale per UX, ma richiede pipeline cloud e KYC. Esclude la modalità self-hosted privacy-first.
- **CSV import**: lo zoccolo duro del self-hosted (Firefly III, Beancount, Actual). Manuale ma controllabile.
- **Manual entry**: per asset illiquidi e per chi vuole disciplina YNAB-style.
- **Receipt scan / OCR**: Smart Receipts, Wave, Copilot Money. Estrae merchant + amount + date + tax dalla foto della ricevuta ([BILL receipt scanner roundup](https://www.bill.com/blog/best-receipt-scanning-app)).

→ **BHODL** oggi: ha auto-import dai watch wallet via Esplora (canale "blockchain feed"), zero per investimenti / liabilities / cash. CSV import manca. Receipt OCR manca.

### 3.3 Categorizzazione
Tre stack tipici:

**(a) Rules engine** — l'utente scrive regole "if merchant contains 'Amazon' then category = Shopping". Funziona bene per 60-80% dei tx in modo deterministico ([Expense Sorted blog](https://www.expensesorted.com/blog/105_ai_powered_bank_transaction_categorization_with_machine_learning)). Modello dati: `rules(condition_field, condition_op, condition_value, action_field, action_value, priority)`.

**(b) Fuzzy matching** — gestisce le varianti di merchant ("AMZN MKTPLACE", "AMAZON.IT", "AMZN*123"). Tipicamente cosine similarity sul nome o ML pretrained.

**(c) ML / LLM-assisted** — Copilot Money ([Money with Katie review](https://moneywithkatie.com/copilot-review-a-budgeting-app-that-finally-gets-it-right/)) usa un modello che impara dalle correzioni. 93% al primo pass, ~99% dopo 2-3 settimane di uso. Plaid ha recentemente rilasciato un modello AI-enhanced ([Plaid AI categorization](https://plaid.com/blog/ai-enhanced-transaction-categorization/)) +10% accuracy primary, +20% detailed.

Stack consigliato: rules engine come primo passaggio (deterministico, auditabile) + ML come fallback per il "noise" residuo.

→ **BHODL** oggi: ha categories piatte ma nessun engine. La correzione manuale resta locale al singolo tx. Quando un utente correggerà un Amazon → "Shopping" 5 volte, l'app dovrebbe imparare. Phase 7.x da pianificare.

### 3.4 Reconciliation
Operazione end-of-period dove l'utente conferma che il saldo dell'account in app = saldo della banca. Pattern YNAB / Beancount:

1. App calcola il "saldo corrente atteso" sommando tx confermati.
2. Utente inserisce il saldo "vero" dalla banca.
3. Se ≠ → app evidenzia i tx pending / non riconciliati e cerca duplicati / dimenticanze.
4. Quando =, l'app marca tutti i tx come `reconciled` (immutabili dopo questo punto, almeno per il flusso default).

Modern app (Monarch, Maybe) ([Maybe self-hosting](https://selfhostsetup.com/posts/maybe-finance-personal-finance-tracker/)) permettono la **reconciliation "soft"**: l'utente sovrascrive il saldo senza generare una `Equity:Adjustments` transaction, utile per asset illiquidi tipo casa.

→ **BHODL** ha già il pattern "saldo vero" via Esplora — il saldo on-chain è autoritativo e l'app si riconcilia automaticamente. Niente da fare per i wallet. Per investments + manual cash servirà il flusso "snapshot manuale" senza adjustment tx.

### 3.5 Budget setup + ongoing assignment
La differenza fra le scuole emerge qui.

**Categoria-budget**: setup una volta all'anno ("$500 cibo/mese"), poi guardi le barre.

**YNAB**: ogni mese **assegni manualmente** l'income alle categorie ("Give every dollar a job"). L'app suggerisce un ordine basato su scheduled transactions + targets ([YNAB Planning](https://support.ynab.com/en_us/categories/planning-HJgZB2C69)). I residui rollover sul mese successivo.

**Flex (Monarch)**: assegni budget solo a Fixed + Non-monthly, il Flex resta un unico numero target. Non assegni euro a "ristoranti vs spesa vs benzina" — guardi il totale Flex.

**Actual goal templates** ([Actual blog](https://actualbudget.org/blog/2023-12-15-automate-your-budget-with-goal-templates/)): puoi scrivere regole come "10% of income → Savings" o "$100/month until $1200 → Vacation" e l'app automatizza l'assignment.

→ **BHODL** Phase 4.3 ha budget per-categoria già. Per Phase 8+ valuterebbe vale la pena aggiungere YNAB-style assign OR il Monarch-flex.

### 3.6 Recurring transactions / Bills / Subscriptions
Tre concetti distinti che vivono insieme:

**Recurring transaction**: un tx che si ripete con frequenza fissa (stipendio, bolletta). Modello: `recurring(template_tx, frequency, next_date, end_date?)`.

**Bill**: come recurring, ma con un **dovuto** e uno **status di pagamento**. Es. "Netflix €11.99 ogni 15 del mese, pagato a maggio".

**Subscription tracking**: caso speciale del bill, con auto-detect ([Rocket Money](https://www.rocketmoney.com/), Copilot) — l'app trova i tx ricorrenti e li propone come subscription. Bonus features: alert quando il prezzo aumenta, suggerimento "non l'hai usato da 3 mesi, cancella?".

Cash forecasting ([Quicken Simplifi](https://support.simplifi.quicken.com/en/articles/3597876-managing-recurring-transactions-on-the-mobile-app)): proietta il saldo al giorno X combinando saldo attuale + recurring entrati - recurring usciti.

→ **BHODL** Phase 4.4 ha bills già. Auto-detect subscription manca. Cash forecasting manca (ma sui watch wallet sarebbe approssimato perché LN non è ancora tracciato).

### 3.7 Net worth tracking
Pattern Kubera/Maybe:
- Daily snapshot (or computed on-the-fly) di `total_assets - total_liabilities`.
- Trend chart: linea su 1Y/5Y/MAX (Phase 7.1 BHODL già fatto).
- Breakdown per asset class (pie / treemap / Kubera waterfall): che fetta hai in cash, crypto, brokerage, real estate.
- **Allocation drift detection**: se il target era 30% crypto e ora sei al 45%, alert ([Vanguard rebalancing](https://investor.vanguard.com/investor-resources-education/portfolio-management/rebalancing-your-portfolio)) — di solito threshold ±5pp.

Personal balance sheet ([Britannica — personal balance sheet](https://www.britannica.com/money/personal-balance-sheet)) come template testuale per chi vuole il "report annuale": Asset breakdown + Liability breakdown + Net Worth in fondo, esportabile a PDF.

→ **BHODL** Phase 7.1: net worth hero + sparkline + period chips ✓. NetWorthBreakdownTable ✓ ma solo wallet+liabilities. Investments breakdown è scheduled Phase 7.3. Allocation drift detection è proprio quello che 7.2.4 sblocca (un goal allocation con bucket=investments dice "voglio X%, sono a Y%").

### 3.8 Investment tracking
**Cost basis** ([Janus Henderson cost basis](https://www.janushenderson.com/en-us/investor/resources/planning/tax/understanding-cost-basis/)):
- **FIFO** (default in molti broker IT/US): vendi prima i lot più vecchi
- **LIFO**: vendi prima i lot più nuovi — meno comune
- **HIFO**: vendi prima i lot con prezzo più alto (minimizza gain tassabile)
- **Specific identification**: scegli lot per lot — il più flessibile, IRS US lo permette per crypto da gennaio 2025

Per BTC l'IRS dal 2025 impone **per-wallet basis** ([CoinTracker — cost basis](https://support.cointracker.io/hc/en-us/articles/4413071343889-Cost-basis-tracking-for-US-customers)): ogni wallet ha la sua queue FIFO/HIFO indipendente. Phase 7.2 di BHODL sarebbe perfetta per implementarlo nativo.

**Lot tracking**: ogni acquisto è un lot `{date, qty, price_per_unit, fees, currency}`. Ogni vendita "consuma" lot secondo la strategia scelta. P&L = `(sale_price - lot.price_per_unit) * qty_consumed - fees`.

**Corporate actions** ([Wealthsimple — corporate actions](https://help.wealthsimple.com/hc/en-ca/articles/43045850436635-Understanding-corporate-actions)):
- Stock split 2:1 → quantità ×2, prezzo /2, valore invariato
- Reverse split 1:10 → opposto
- Dividend: nuovo `Income:Dividends` tx, opzionalmente reinvested (DRIP) come nuovo lot
- Merger / acquisition: lot vecchio chiuso, nuovo aperto con basis carryover

**Crypto-specifici** ([Bleap — crypto tax](https://www.bleap.finance/en-us/blog/guide-to-crypto-taxes), [Koinly](https://koinly.io/guides/crypto-taxes/)):
- **Airdrop**: ordinary income al fair-value alla data di ricezione + apre nuovo lot
- **Staking reward**: come airdrop
- **Lending interest**: ordinary income, no nuovo lot (la principal resta sua)
- **Swap A→B**: capital gain/loss su A (sell event), nuovo lot su B
- **Send to own wallet**: non taxable, ma in BHODL deve essere modellato come transfer per non sporcare il P&L

**Rebalancing** ([Morningstar — rebalance](https://www.morningstar.com/personal-finance/how-rebalance-your-portfolio-before-2026-2)):
- Threshold-based (±5pp dal target) o time-based (semestrale/annuale)
- Rule of 110: % stock = 110 − età (semplice ma popolare)
- App moderne suggeriscono "vendi €X di IWDA e compra €Y di BTC per tornare al target"

→ **BHODL** Phase 5.1 ha investments già (cost basis + manual/fetched current value). Cost basis method è hardcoded — manca la scelta. Lots non sono ancora separati (un investment row = un unico cost basis aggregato). Corporate actions assenti. Crypto income (airdrop/staking) modellabile via PaymentEvent ma manca la classificazione. Rebalancing detection collegabile direttamente ai goal di Phase 7.2.4 (allocation goal con target_pct).

### 3.9 Goals tracking (sinking / savings / allocation)
Vedi 2.6 per il modello. Flusso:

1. Setup: nome, target, bucket source, deadline opzionale, color.
2. Funding manuale (YNAB-style, "muovi €100 al goal X questo mese") OR auto-funding (Actual goal template, "10% di ogni income va qui").
3. Progress = current / target × 100%.
4. Forecast: "al ritmo attuale arriverai il YYYY-MM-DD" — proietta in base ai funding storici.
5. Quando spendi dal bucket → il goal scala. UI: animazione "goal +€50" / "goal -€20".

→ **BHODL** Phase 7.2.4: bucket binding fatto. Manca: deadline, forecast "arriverai il", funding manuale esplicito (oggi è solo "current_sat" che viene dal bucket).

### 3.10 Reports / Analytics
KPI cards canoniche per il dashboard mensile ([upsolve.ai — financial dashboard](https://upsolve.ai/blog/financial-dashboard), [Klipfolio income-expenses](https://www.klipfolio.com/resources/kpi-examples/financial/income-and-expenses)):

- **Net worth** (con Δ vs mese precedente)
- **Cash flow del mese** (income − expense)
- **Savings rate** (income − expense) / income × 100%
- **Burn rate** (expense / month, utile per calcolare runway)
- **Top 5 categorie di spesa**
- **% budget consumato globale**

Grafici frequenti:
- Linea net worth nel tempo (BHODL Phase 7.1 ✓)
- Waterfall: Δ net worth mese su mese diviso per asset class (+cash, +investment, −liab, +real estate)
- Donut / Treemap: spesa per categoria
- Lollipop / bar chart orizzontale: spesa per categoria ordinata (BHODL Phase 4.7 ✓)
- Calendar heatmap: attività per giorno (BHODL Phase 3.8 ✓)
- Inflow/Outflow stacked bar mensile
- YoY comparison overlay (BHODL Phase 3.8 ✓)
- Sankey: income → category breakdown
- Sparkline su ogni KPI card (BHODL Phase 7.1 ✓)

**Forecasting** ([SavePoint — cash flow forecasting](https://savepointfinance.com/blog/cash-flow-forecasting-personal-finance)): proiezione 60-90 giorni con saldo running. What-if: "se compro casa da €200k, il mio cash crolla a X il mese Y" — Kubera lo fa, Monarch no, Maybe in roadmap.

**Anomaly detection** ([HighRadius — anomaly detection](https://www.highradius.com/resources/Blog/transaction-data-anomaly-detection/)):
- Spike di spesa categoria X (es. ristoranti +200% rispetto al baseline 6 mesi)
- Transazioni duplicate (fuzzy match: stesso amount + payee in 24h)
- Bills mancanti (Netflix pagato per 11 mesi, manca a maggio → probabile dimenticanza o cancellazione)
- Transfer ricevuto ma uscita mancante (il mirror non è arrivato)

**FIRE calculator** ([WalletBurst FIRE](https://walletburst.com/tools/fire-calculator/), [Bankrate FIRE](https://www.bankrate.com/investing/how-to-calculate-your-fire-number/)):
- FIRE Number = 25 × annual expenses (4% rule)
- Anni a FIRE = funzione di savings rate (50% saving rate → ~17 anni; 20% saving → ~37 anni)
- Conviene mostrarlo come metrica derivata sulla dashboard

→ **BHODL** ha già: net worth, sparkline, waterfall implicito, donut, lollipop, calendar heatmap, YoY. Mancano: savings rate, burn rate, FIRE number, forecasting, anomaly detection.

### 3.11 Multi-currency
Tre approcci:

**(a) Base currency conversion**: ogni tx in valuta nativa, l'app converte a base currency (EUR) usando il rate **alla data del tx** ([Lunch Money multi-currency](https://lunchmoney.app/features/multicurrency/)). Semplice ma perde il P&L FX.

**(b) FX gain/loss attribution**: la differenza fra il rate alla compra e alla vendita di un asset estero genera un FX P&L separato dal P&L "vero" sull'asset. Standard in business accounting, raro in personal — Gravity, DualEntry lo fanno.

**(c) Treat-as-asset**: ogni valuta è un asset, e i transfer EUR→USD sono un "buy USD with EUR" che apre un lot. Beancount lo fa nativamente. Più rigoroso ma più complesso UX.

→ **BHODL** caso semplice: BTC è la base, fiat conversion via PriceCache già. Investments hanno `cost_basis_currency` ma la conversione cross-currency è stata rimandata a Phase 5.2.

### 3.12 Household / joint accounts
Pattern Monarch ([Best Budgeting App for Couples](https://www.monarch.com/for-couples)): due login separati, account marcati come "joint", split account "personal" non condivisi. Honeydue: choose which accounts to share. Tandem: tutto-condiviso.

Per BHODL questa è una decisione di posizionamento: la prima v punta probabilmente all'utente singolo (self-custodial), ma la possibilità di importare uno xpub partner come watch-only è già architetturalmente lì.

### 3.13 Privacy mode / shoulder surfing
Toggle che oscura tutte le cifre nelle UI ([ExpenseManager privacy mode](https://expensemanager.pro/blog/privacy-mode-expense-tracker-why-it-matters.html)). Lasciare visibili categorie e date, mask solo gli importi (••• o blur). Plum, Toolkit-for-YNAB, Rotki lo hanno.

→ **BHODL** Phase 7.2 ha già `PrivacyMaskState` + il toggle. ✓

### 3.14 Anomaly / alerts (vedi 3.10)
Pattern moderno: notification push o in-app banner per:
- Bill imminente (3 giorni)
- Saldo sotto threshold
- Spesa anomala (categoria > 1.5×σ del baseline)
- Subscription price increase
- Goal raggiunto / a rischio

→ **BHODL** desktop. Notifications platform-native (Compose Desktop ne ha bisogno via JNI o tray API). Non urgente.

### 3.15 Receipt / attachment
Modello: `attachments(tx_id, type, path, ocr_extracted_json?)`. Storage: locale (e.g. `~/.bhodl/attachments/`), opzionalmente cifrato con la stessa key del DB.

OCR ([Smart Receipts](https://smartreceipts.co/)): estrae merchant, date, amount, tax. Implementabile lato client con Tesseract o (su iOS/macOS) Vision framework. Su Linux/Windows Desktop, Tesseract via JNI.

→ **BHODL** non ce l'ha. Lavoro Phase 8+. Su mobile native quando arriverà sarà più semplice.

---

## 4. Pattern UX

### 4.1 Dashboard composition
Pattern Kubera / Empower / Maybe / Copilot — l'ordine top-down:
1. **Hero**: net worth + Δ + sparkline (BHODL ✓)
2. **KPI strip**: income, expense, savings rate, burn rate, top category (BHODL parziale)
3. **Trust signals**: data freshness, sync status (BHODL ✓)
4. **Net worth breakdown table** o **donut allocation** (BHODL parziale)
5. **Bills + Goals**: cosa è dovuto, cosa è in corso (BHODL ✓)
6. **Spending breakdown** for the period (BHODL ✓)
7. **Recent transactions** list (BHODL via History tab)
8. **Insights / anomalies** in chip o card piccola (manca)

### 4.2 Drill-down chain
Click su category in donut → lista tx di quella categoria → click su tx → dettaglio + receipt + edit. BHODL ha già `onCategoryDrillDown` ✓.

### 4.3 Inline rules
Quando l'utente edita la categoria di un tx, l'app chiede: "Apply to all transactions from this merchant?" → crea automatic rule. Monarch e Copilot lo fanno. Riduce friction massicciamente.

### 4.4 Monthly review loop
Il workflow "buon utente YNAB":
1. Importi i tx del mese (oggi → auto via bank feed, BHODL → auto via Esplora)
2. Categorizzi quelli unconfirmed
3. Riconcili (saldo app = saldo banca)
4. Apri il report del mese: hai sforato? le sinking funds sono ai target?
5. Pianifichi il mese dopo: assigni l'income (YNAB) o controlli che i budget siano realistici

Buon trigger per BHODL: notification "fine mese: rivedi il tuo budget" il giorno 1 del mese.

### 4.5 Color semantics
Standard convergente di mercato: verde positivo (income, goal progress), rosso negativo (over-budget, debito che cresce), giallo warning (on track to over-budget, ~80% del budget consumato). BHODL ha già la convenzione "Western financial" coerente.

---

## 5. Implicazioni per BHODL

### 5.1 Cosa BHODL già fa giusto
- **Modello double-entry implicito** via blockchain (no double-counting per costruzione sui transfer on-chain)
- **Privacy-first locale** (SQLCipher, device PIN, privacy mask)
- **Net worth-first dashboard** (Phase 7.1 + 7.2)
- **Categorizzazione opzionale** (categorie già nei PaymentEvent)
- **Budget per categoria** (Phase 4.3)
- **Bills** (Phase 4.4)
- **Goals con allocation bucket** (Phase 7.2.4, appena chiusa)
- **Investments con cost basis aggregato** (Phase 5.1)
- **Manual liabilities** (Phase 4.6)
- **Material Symbols + Bloomberg/Sparrow data-dense UX** (vedi `ux-review-2026-10.md`)

### 5.2 Gap evidenti rispetto allo stato dell'arte
Ordinati per impatto stimato sull'utente target ("Stacker Sara" + "Spender Marco" personas):

**Tier 1 — features che mancano e che gli utenti si aspettano dal day-1**
- **Cash account manuale**: per "soldi in tasca" e "soldi sul conto corrente". Niente blockchain, solo `Assets:Cash:Wallet`. Modello account più ricco di solo watch wallet (sez. 2.1).
- **Categorie gerarchiche** (2 livelli, in linea Plaid): oggi è piatto.
- **Tag set** (multi-dim ortogonale a categories).
- **Split transactions** (una tx → N categorie).
- **Transfer detection** fra wallet/account (oggi BHODL già lo sa per blockchain interni, ma quando arriveranno cash account ed exchange watch servirà).
- **Rules engine** "if payee = X then category = Y" — sostituisce la correzione tx-by-tx.
- **Savings rate + Burn rate** sulla KPI strip.

**Tier 2 — features di completezza professionale**
- **Lots separati per investments** (vs aggregate cost basis attuale): unlock cost-basis methods (FIFO/HIFO/specific-ID).
- **Cost basis method picker** per-account (per-wallet enforced per crypto, FIFO/specific-ID per stocks).
- **Sinking funds** distinti da savings goals (deadline obbligatoria, auto-funding rules).
- **Cash flow forecasting** (60-90 giorni proiezione).
- **FIRE calculator** integrato (free metric, alto valore percepito).
- **Anomaly detection** locale (no ML in cloud — solo σ-based su baseline 6 mesi).
- **Recurring detection** (auto-suggerisce bills dai PaymentEvent ricorrenti).
- **Allocation drift alert** (target X%, attuale Y%, suggerisce rebalance).

**Tier 3 — features long-tail / power-user**
- **CSV import + export** (formato Beancount-compatible è premium per privacy crowd)
- **Receipt OCR** (più ostico desktop, naturale su mobile native quando arriverà)
- **Multi-currency cross-conversion** (Phase 5.2 già pianificata)
- **Corporate actions** (split, merger) per stock holdings
- **DRIP / dividend reinvest** per ETF a distribuzione vs accumulazione
- **Crypto event taxonomy** (airdrop / staking / swap / send-to-own) con tassazione locale
- **Household / multi-user** (out of scope MVP)

### 5.3 Decisioni di posizionamento già implicite nel progetto
Da Phase 7+ e da `PLAN.md`:
- **Bitcoin-first** ma non Bitcoin-only: il Tracker deve avere senso anche con 50% del patrimonio in stock/cash/casa.
- **Self-custodial, no KYC, no cloud sync** → categorial-budget + portfolio-first hanno UX che presuppongono cloud, non possiamo replicarli pari-pari.
- **Tier UX three-level**: Beginner / Standard / Privacy-Power-Tools (da `PLAN.md`). YNAB-style envelope è feature Standard, FIRE/rebalancing sono Power-Tools.
- **Material Symbols + data-dense Bloomberg/Sparrow** (vedi `ux-review-2026-10.md`) — questo orienta il dashboard verso Kubera/Maybe più che Copilot.

### 5.4 Roadmap implicita (proposta non vincolante)

Phase 7.3 (Investments + Budget reframe, già citato in `NetWorthBreakdownTable.kt:43`) potrebbe coprire:
1. Categorie gerarchiche 2 livelli (Plaid-compatible)
2. Tag set
3. Split transactions
4. Investments → integrazione nel NetWorthBreakdownTable
5. Allocation drift alert sui goal di 7.2.4

Phase 7.4 (Cash & Manual accounts):
1. Account type taxonomy completa (sez 2.1)
2. `Assets:Cash:*`, `Assets:Investments:*`, `Liabilities:*` come account distinti
3. Manual transfer fra account (con detection)

Phase 7.5 (Rules + automation):
1. Rules engine (categorize, auto-tag)
2. Recurring detection
3. Inline "apply to all from this merchant"

Phase 7.6 (Forecast + Insights):
1. Cash flow 60-90 giorni
2. Savings rate / Burn rate KPIs
3. FIRE calculator
4. Anomaly chips ("spese ristoranti +200%")

Phase 7.7 (Investments deep dive):
1. Lots separati + cost basis method picker (per-wallet enforced per BTC)
2. Sinking funds + auto-funding
3. Crypto event taxonomy

Phase 8 (Export + Receipts):
1. CSV / Beancount export
2. Receipt attachment (storage cifrato locale)
3. OCR (mobile)

---

## 6. Letture di riferimento (curated)

### Metodologie
- [YNAB Method overview](https://www.ynab.com/ynab-method) — fonte primaria zero-based
- [Actual Budget — Envelope](https://actualbudget.org/docs/getting-started/envelope-budgeting/) — modern open-source envelope
- [Monarch — Flex Budgeting](https://help.monarch.com/hc/en-us/articles/32125337244052-Using-Flex-Budgeting) — alternativa al per-category
- [Discover — Sinking Funds](https://www.discover.com/online-banking/banking-topics/what-is-a-sinking-fund/) — diff sinking vs savings
- [WECU — SMART financial goals](https://www.wecu.com/setting-smart-financial-goals/) — framework SMART
- [Chase — 50/30/20 rule](https://www.chase.com/personal/banking/education/budgeting-saving/50-20-30-budget-rule) — proporzione classica

### Data model
- [Beancount account management](https://beancount.io/blog/2026/02/08/beancount-account-management-complete-guide) — naming convention double-entry
- [Plaid taxonomy update](https://plaid.com/blog/transactions-categorization-taxonomy/) — 16 primary × 104 detailed categories
- [Plaid AI categorization](https://plaid.com/blog/ai-enhanced-transaction-categorization/) — ML categorization 2026
- [Copilot — Tags vs Categories](https://help.copilot.money/en/articles/9554367-tags-vs-categories)
- [GeeksforGeeks — financial DB design](https://www.geeksforgeeks.org/dbms/how-to-design-a-database-for-financial-applications/) — fixed-point money, double-entry journal pattern

### App reference
- [YNAB Method](https://www.ynab.com/ynab-method)
- [Monarch Money — overview](https://www.monarch.com/features/budgeting)
- [Copilot Money — Money With Katie review](https://moneywithkatie.com/copilot-review-a-budgeting-app-that-finally-gets-it-right/)
- [Lunch Money features](https://lunchmoney.app/features)
- [Maybe Finance GitHub](https://github.com/maybe-finance/maybe) — open source PFM
- [Kubera — homepage](https://www.kubera.com/) — net-worth-first
- [Ghostfolio GitHub](https://github.com/ghostfolio/ghostfolio) — open source portfolio
- [Rotki GitHub](https://github.com/rotki/rotki) — privacy-first crypto
- [Firefly III intro](https://docs.firefly-iii.org/explanation/firefly-iii/about/introduction/) — self-hosted PFM
- [Actual Budget docs](https://actualbudget.org/docs/budgeting/)

### Investments & crypto
- [Janus Henderson — Cost basis](https://www.janushenderson.com/en-us/investor/resources/planning/tax/understanding-cost-basis/)
- [CoinTracker — Per-Wallet basis 2025](https://support.cointracker.io/hc/en-us/articles/4413071343889-Cost-basis-tracking-for-US-customers)
- [Stockle — FIFO/LIFO/HIFO](https://stockle.medium.com/understanding-cost-basis-methods-in-investing-fifo-lifo-hifo-lcfo-average-cost-and-specific-ca72c90c6278)
- [Koinly — Crypto tax guide 2026](https://koinly.io/guides/crypto-taxes/)
- [Wealthsimple — Corporate actions](https://help.wealthsimple.com/hc/en-ca/articles/43045850436635-Understanding-corporate-actions)
- [Vanguard — Rebalancing](https://investor.vanguard.com/investor-resources-education/portfolio-management/rebalancing-your-portfolio)

### UX & dashboard
- [Appcues — fintech onboarding](https://www.appcues.com/blog/fintech-onboarding-examples)
- [Plaid — fintech onboarding](https://plaid.com/resources/fintech/fintech-onboarding-process/)
- [Kubera — financial dashboard](https://www.kubera.com/blog/financial-dashboard)
- [upsolve.ai — financial dashboard examples](https://upsolve.ai/blog/financial-dashboard)
- [Expense Sorted — categorization ML](https://www.expensesorted.com/blog/105_ai_powered_bank_transaction_categorization_with_machine_learning)
- [HighRadius — anomaly detection](https://www.highradius.com/resources/Blog/transaction-data-anomaly-detection/)
- [BILL — receipt scanner apps](https://www.bill.com/blog/best-receipt-scanning-app)
- [SavePoint — cash flow forecasting](https://savepointfinance.com/blog/cash-flow-forecasting-personal-finance)
- [WalletBurst — FIRE calculator](https://walletburst.com/tools/fire-calculator/)

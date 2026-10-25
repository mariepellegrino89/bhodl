# BHODL — Test Book (manual QA)

> Tutti gli scenari da provare manualmente. Ognuno ha precondizioni,
> passi numerati, e l'esito atteso. Spunta `[x]` quando passa, annota
> `FAIL: <motivo>` quando rompe.
>
> **Dal 2026-10-23 BHODL è watch-only**: niente seed, niente PIN per
> wallet, niente invio. Le sezioni 0–20 sono state riscritte per il
> nuovo flusso (Welcome → device PIN → aggiunta xpub → Tracker → wallet
> workspace). Gli scenari su creazione / restore da seed, unlock per
> wallet, Send, fee, RBF, CPFP, PayJoin, backup cifrato, recovery
> phrase, promote watch, migrazione pre-3.0 e debug watch screen sono
> stati rimossi; restano nella storia git.
>
> I test di rete girano su **signet** con endpoint `blockstream.info/signet`
> (o endpoint personalizzato via Settings). Serve un wallet di firma
> esterno su signet (Sparrow, hardware wallet…) da cui esportare l'xpub
> e con cui pagare gli indirizzi derivati da BHODL; in alternativa il
> faucet (rate limit 1/24h per address).

## Stato automazione (2026-10-23)

Ogni scenario (riga numerata di una tabella, o `### NN.M` nelle sezioni
21–29) porta un marker:

- `[auto]` — verificato da un test automatico, citato accanto
  (`Classe.`metodo``). Senza suffisso = `./gradlew :shared:jvmTest
  :apps:desktop:jvmTest`, che gira su ogni PR; `(cargo)` = `cargo test
  --workspace` (anche su ogni PR); `(integrationTest)` = job Docker
  regtest della CI; `nightly` = workflow `Tor (live)` (ora solo manuale, `workflow_dispatch`).
- `[partial]` — la logica è automatizzata, resta manuale il passo
  indicato (resa visiva, wiring in `Main.kt`/`App.kt`, riavvio reale).
- `[ ]` — manuale.

| Area | Scenari | `[auto]` | `[partial]` | Manuali |
|------|--------:|---------:|------------:|--------:|
| 0–20 Wallet watch-only: primo avvio, device PIN, wallet workspace, settings, wipe, LegacyCleanup, integrazione | 122 | 70 | 0 | 52 |
| 21–39 Tracker / Bitcoin Lens | 124 | 65 | 53 | 6 |
| 40–53 Security review, UX Phase 9, fix e UX review 2026 | conteggi non ricalcolati dopo il passaggio watch-only (righe su firma / invio rimosse) | | | |

Cosa resta manuale, e perché:

- **Rete / fondi reali** (§0, §5, 46.2): pagamento da un wallet di firma
  esterno o dal faucet signet, sync verso un Esplora pubblico, conferme
  on-chain. La parte deterministica gira nei test regtest
  (`integrationTest`, `cargo test --test regtest -- --ignored`).
- **Resa visiva / layout** (§16, 41.x): ordine delle sezioni, colori,
  scroll a finestra ridotta.
- **Dialog nativi e clipboard di sistema** (4.6–4.7, 14b.10, 14b.12):
  "Save as" / "Open" del sistema operativo.
- **Ciclo di vita del processo** (1.1, 1.9, 3.1, 4.10, 15.5, 16c.3–16c.4,
  17b.x, 44.4): boot guard su `~/.bhodl`, riavvio dell'app,
  `key(localeOverride)` in `App()`. `App()`/`main()` non hanno seam per
  la data dir, quindi non sono pilotabili da un test UI senza toccare la
  home reale.
- **Device-PIN / registry** (`DevicePinSetupScreen`,
  `DevicePinUnlockScreen`, `LegacyCleanupScreen`): richiedono il
  registry SQLCipher reale; la logica (validazione PIN, lockout,
  rilevamento e pulizia dei dati legacy) è coperta da unit test.
- **Solo Linux/macOS o coverage** (40.11 permessi POSIX, 17.6–17.7 soglie
  kover / llvm-cov).

## 0. Setup ambiente

| #     | Test                                           | Pass |
|-------|------------------------------------------------|------|
| 0.1   | `./gradlew :apps:desktop:run` apre la finestra BHODL senza errori sul terminale. | [ ] |
| 0.2   | `~/.bhodl/` è vuota (test "fresh install"). Se esiste, la rinomino o uso "Erase ALL local wallets…" da Welcome / Factory reset da Settings. | [ ] |
| 0.3   | Network reachability: `curl https://blockstream.info/signet/api/blocks/tip/height` ritorna un numero. | [ ] |
| 0.4   | Wallet di firma signet pronto (es. Sparrow su signet): annota l'xpub di account (`tpub` / `vpub`), il tipo di script e il primo indirizzo di ricezione. | [ ] |

---

## 1. Primo avvio — Welcome, device PIN, primo wallet

**Precondizione**: `~/.bhodl/` vuota (`0.2`).

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 1.1   | App parte.                                                            | Atterra su Welcome (nessun registry su disco).                                  | [ ] |
| 1.2   | Leggi Welcome.                                                        | Testo: BHODL è un tracker Bitcoin watch-only, i wallet si aggiungono da xpub / descriptor / indirizzi, non tiene chiavi private e non può inviare bitcoin. Pulsante "Get started" + TextButton "Erase ALL local wallets…". Nessun pulsante create / restore. | [auto] WelcomeScreenTest.`tap get started invokes the callback and nothing else` |
| 1.3   | Tap "Get started".                                                    | Naviga a "Create your device PIN": spiega che il PIN cifra lista wallet e dati del tracker, minimo 6 cifre, non recuperabile. Cursore nel primo campo. | [ ] |
| 1.4   | PIN "12345", conferma "12345".                                        | Errore "PIN is too short (5 / 6 minimum)."; nessun file creato.                  | [auto] PinValidatorTest |
| 1.5   | PIN "abcdef".                                                         | Errore "PIN must contain only digits."                                          | [auto] PinValidatorTest |
| 1.6   | PIN "123456", conferma "654321".                                      | Errore "Device PINs do not match."                                              | [auto] PinValidatorTest |
| 1.7   | Un solo campo compilato.                                              | "Create device PIN" disabilitato finché entrambi i campi non sono pieni.        | [ ] |
| 1.8   | PIN "123456", conferma "123456" → "Create device PIN".                | "Opening…" per ~1 s, poi naviga ad "Add watch-only wallet". In `~/.bhodl/` ci sono `registry.sqlite` e `registry.kdf.json`, nessun file `wallet.*`. | [ ] |
| 1.9   | Chiudi l'app su "Add watch-only wallet" senza aggiungere nulla, riapri. | Atterra su DevicePinUnlock (il registry esiste), non su Welcome.                | [ ] |
| 1.10  | Su "Add watch-only wallet": label "Signet test", Script type uguale al wallet di firma, network Signet, incolla l'xpub di `0.4`, "Add watch wallet". | "Adding…", poi il Tracker HOME mostra la riga "Signet test" con il saldo dopo la prima sync. Esiste `~/.bhodl/watch-state/<uuid>.sqlite`. | [ ] |
| 1.11  | Aggiungi di nuovo lo stesso xpub con un'altra label.                  | Errore che indica il wallet già presente con quell'xpub; nessuna nuova riga.    | [ ] |
| 1.12  | Aggiungi un altro xpub con label "signet TEST" (stessa label, maiuscole diverse). | Errore nome già usato.                                                          | [ ] |
| 1.13  | Network Mainnet con Tor spento → "Add watch wallet".                  | Dialog "Mainnet sync without Tor" con avviso privacy; "Cancel" non aggiunge nulla, "Continue without Tor" procede. | [ ] |

---

## 2. Apertura di un wallet dal Tracker

**Precondizione**: device PIN sbloccato, almeno un wallet xpub (1.10).

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 2.1   | Tracker HOME → clicca la riga del wallet.                             | Si apre il wallet workspace sul tab Transactions: header con nome, "Watch-only wallet", saldo; tab Transactions · Receive · Addresses · UTXOs · Wallet settings. Nessun PIN richiesto. | [auto] DashboardScreenTest.`sub-tabs switch between Transactions, Receive, Addresses, UTXOs and Wallet settings` |
| 2.2   | Clicca ogni tab.                                                      | Ogni tab si apre senza errori; nessun tab Send.                                 | [auto] DashboardScreenTest.`sub-tabs switch between Transactions, Receive, Addresses, UTXOs and Wallet settings` |
| 2.3   | Torna al Tracker (rail) e apri un secondo wallet.                     | Il workspace mostra il secondo wallet (nome, saldo, transazioni suoi).          | [ ] |
| 2.4   | Header → "Contacts".                                                  | Apre la rubrica (§14).                                                          | [auto] DashboardScreenTest.`Contacts header action fires onAddressBookClicked` |
| 2.5   | Header → "Lock".                                                      | Chiude registry e wallet, torna a DevicePinUnlock con back stack vuoto.         | [auto] DashboardScreenTest.`Lock header action fires onLockClicked` |

---

## 3. Unlock con device PIN

**Precondizione**: registry già creato (§1), app **chiusa**.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 3.1   | Avvia app.                                                            | Boot guard rileva `registry.sqlite`, parte da DevicePinUnlock ("Enter your device PIN to open BHODL."). Cursore nel campo PIN. | [ ] |
| 3.2   | PIN sbagliato.                                                        | "Wrong device PIN."; resta sulla schermata.                                     | [ ] |
| 3.3   | Tre PIN sbagliati di fila.                                            | "Wrong device PIN. Wait Ns before retrying." con countdown; pulsante disabilitato per tutto il cooldown. Il cooldown cresce con i tentativi (5 s, 30 s, 2 min, 10 min). | [auto] PinAttemptTrackerTest |
| 3.4   | PIN giusto.                                                           | Atterra sul Tracker HOME (o su LegacyCleanup, §17b, se ci sono dati di build con seed). Wallet e dati del tracker invariati. | [ ] |
| 3.5   | Resta inattivo oltre il tempo di auto-lock.                           | L'app torna a DevicePinUnlock da sola.                                          | [auto] IdleLockTest |

---

## 4. Wallet workspace — sync, Transactions, auto-sync

**Precondizione**: wallet aperto (§2), saldo noto.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 4.1   | All'apertura del workspace.                                           | Auto-sync parte (ogni 60 s finché l'app è in focus). Prima della sync si vede il saldo in cache, non "—". | [auto] DashboardScreenTest.`auto-sync fires on first composition and balance lands` + `cached balance is displayed before any sync happens` |
| 4.2   | Tap Sync.                                                             | Sync manuale; se fallisce (es. offline) ErrorCard rossa con Retry.              | [auto] DashboardScreenTest.`manual Sync also calls the FFI` + `sync failure renders an ErrorCard, retry triggers another sync` |
| 4.3   | Lascia l'app in focus ~2 minuti.                                      | Auto-sync almeno un'altra volta; la cache di 60 s evita chiamate ravvicinate.   | [auto] DashboardScreenTest.`auto-sync is skipped when SyncCache is still fresh` |
| 4.4   | Tab Transactions con qualche tx.                                      | Tabella con importi firmati (+ in entrata, − in uscita), stato Pending / Confirming n of 6 / nessun badge quando definitiva. Filtri e ricerca restringono la tabella. | [auto] HistoryScreenTest.`status chips follow Pending, Confirming n of 6 and no badge once final` + `incoming amounts carry a plus sign, outgoing a minus` + `filter chips and search narrow the table` |
| 4.5   | Clicca una riga.                                                      | Pannello di dettaglio a destra: txid, importo, fee (se nota), stato, label.     | [auto] HistoryScreenTest.`clicking a row fires onTxClicked and opens the detail pane` |
| 4.6   | Nel dettaglio: "Copy" txid, poi incolla in un editor.                 | Il txid completo.                                                               | [ ] |
| 4.7   | "Open in mempool.space".                                              | Browser su `https://mempool.space/signet/tx/<txid>` (mainnet senza `/signet`).  | [auto] TransactionDetailsScreenTest.`Open in mempool space fires openExplorer with the full URL` (browser: manuale) |
| 4.8   | Un pagamento in uscita fatto dal wallet di firma esterno, poi Sync.   | Compare in Transactions con importo negativo e fee.                             | [ ] |
| 4.9   | Un trasferimento tra due wallet entrambi tracciati.                   | Classificato come "Send to self" nel tracker (§29.2).                           | [auto] SendToSelfDetectorTest |
| 4.10  | Chiudi e riapri l'app → unlock → apri il wallet.                      | Storico identico senza attendere una nuova scansione completa.                  | [ ] |

---

## 4b. Receive — label-first, derivazione indirizzi, BIP-21

**Precondizione**: wallet **xpub** aperto.

Il tab Receive non mostra un indirizzo finché l'utente non ha scelto
etichetta / categoria (o "Skip"): l'etichetta e la categoria vengono
legate all'indirizzo e copiate sul pagamento in arrivo alla sync.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 4b.1  | Tab Receive.                                                          | Passo "Label this payment first": campi Label, Category, Amount, messaggio per il pagatore. Nessun indirizzo visibile. | [auto] ReceiveScreenTest.`opening the tab reveals no address until the user continues` |
| 4b.2  | Label "Invoice #44", categoria "Freelance" → "Show address".          | Appare un indirizzo + QR + URI BIP-21; sotto "Label: Invoice #44" e la categoria. | [auto] ReceiveScreenTest.`label and category are attached to the address before it is shown` |
| 4b.3  | Confronta l'indirizzo con il wallet di firma.                         | È il prossimo indirizzo di ricezione non usato dello stesso account (stesso tipo di script). | [ ] |
| 4b.4  | Amount "100000".                                                      | URI `bitcoin:<addr>?amount=0.001`; QR rigenerato.                               | [auto] ReceiveScreenTest.`amount produces a BIP-21 URI with the amount in BTC` |
| 4b.5  | Amount "abc.0.5".                                                     | Solo cifre accettate.                                                           | [auto] ReceiveScreenTest.`digits-only filter rejects letters and decimals in amount` |
| 4b.6  | Messaggio per il pagatore "Coffee with Alice".                        | Solo il messaggio esplicito finisce nell'URI, percent-encoded (`%20`); label e categoria interne no. | [auto] ReceiveScreenTest.`only the explicit payer message reaches the URI, percent-encoded` |
| 4b.7  | "Copy URI" / "Copy address" → incolla in un editor.                   | URI BIP-21 completo / indirizzo nudo.                                           | [ ] |
| 4b.8  | "Edit label" → cambia label.                                          | Stesso indirizzo, label aggiornata; nessun nuovo indirizzo rivelato.            | [auto] ReceiveScreenTest.`editing the request relabels the same address without revealing another` |
| 4b.9  | Esci dal tab e rientra senza che l'indirizzo riceva fondi.            | Stesso indirizzo non usato (nessun buco nel gap limit).                         | [auto] ReceiveScreenTest.`re-opening the tab hands out the same unused address` |
| 4b.10 | "New address".                                                        | Indirizzo all'indice successivo, label mantenuta.                               | [auto] ReceiveScreenTest.`New address advances to the next index and keeps the label` |
| 4b.11 | Paga l'indirizzo di 4b.2 dal wallet di firma (o faucet), Sync.        | La tx in entrata in Transactions porta label "Invoice #44" e categoria "Freelance"; il tracker la conta già categorizzata. | [auto] DashboardScreenTest.`every sync copies receive labels through the core` + regtest watch test (cargo, `--ignored`) — pagamento reale: manuale |
| 4b.12 | Tab Addresses → "Show" su un indirizzo già usato.                     | Apre Receive su quell'indirizzo con l'avviso di riuso.                          | [auto] DashboardScreenTest.`Addresses tab lists used and unused, Show opens Receive with a reuse warning` + ReceiveScreenTest.`a previously used address shows the reuse warning` |
| 4b.13 | Apri un wallet **address-list** (aggiunto via FFI / build di test).   | Il tab Receive non c'è; gli altri tab sì.                                        | [auto] DashboardScreenTest.`address-list wallet hides the Receive tab` + `xpub wallet shows the Receive tab` |

---

## 4c. Addresses e UTXOs (sola lettura)

**Precondizione**: wallet xpub aperto con almeno un pagamento ricevuto.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 4c.1  | Tab Addresses.                                                        | Indirizzi receive (e change) con indice, stato usato / non usato, label.        | [auto] DashboardScreenTest.`Addresses tab lists used and unused, Show opens Receive with a reuse warning` |
| 4c.2  | Tab UTXOs.                                                            | Tabella outpoint · address · tipo (receive / change) · stato (confirmed / unconfirmed) · importo, con conteggio e totale. Nessun checkbox, nessun "Freeze", nessun "Send selected". | [auto] DashboardScreenTest.`UTXOs tab lists coins read-only with a count and total` |
| 4c.3  | Confronta i UTXO con il wallet di firma.                              | Stesso insieme e stessi importi.                                                | [ ] |

---

## 5. Pagamento reale su signet

**Precondizione**: wallet xpub aperto, wallet di firma con fondi signet (o faucet).

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 5.1   | Receive → label → "Show address" → copia.                             | Vedi 4b.2 / 4b.7.                                                               | [ ] |
| 5.2   | Paga 10 000 sat dal wallet di firma (o faucet `https://signetfaucet.com/`). | In 1–3 minuti la tx è in mempool (`mempool.space/signet/address/<addr>`).       | [ ] |
| 5.3   | Sync.                                                                 | Saldo +10 000 sat; riga Pending in Transactions; UTXO unconfirmed.              | [ ] |
| 5.4   | Dopo il mining, Sync.                                                 | Riga Confirming n of 6, poi senza badge; UTXO confirmed.                        | [ ] |
| 5.5   | Spendi dal wallet di firma, Sync in BHODL.                            | Uscita con fee in Transactions, UTXO spesi spariscono, saldo coerente con il wallet di firma. | [ ] |

---

## 12. Transaction details + label BIP-329

**Precondizione**: wallet aperto con almeno una tx.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 12.1  | Transactions → riga → pannello dettaglio (o pagina TransactionDetails da deep link). | Txid completo con Copy, importo, fee, stato (confermata / "In mempool"), campo label. | [auto] TransactionDetailsScreenTest.`confirmed tx renders status, amount, fee, txid` + `pending tx renders 'In mempool' status` |
| 12.2  | Scrivi "test label" → Save.                                           | Salvata; Save torna disabilitato.                                               | [auto] LabelsAndContactsUiTest.`tx label saves, survives a re-open, and Clear removes it` |
| 12.3  | Chiudi e riapri il dettaglio.                                         | La label è ancora lì e compare nella riga della tabella.                         | [auto] LabelsAndContactsUiTest.`tx label saves, survives a re-open, and Clear removes it` |
| 12.4  | Lock → unlock → riapri il wallet.                                     | La label persiste.                                                              | [ ] |
| 12.5  | Svuota il campo → Clear.                                              | Label rimossa.                                                                  | [auto] LabelsAndContactsUiTest.`tx label saves, survives a re-open, and Clear removes it` |
| 12.6  | Txid sconosciuto via deep link.                                       | Errore "not found", Back funziona.                                              | [auto] TransactionDetailsScreenTest.`unknown txid surfaces 'not found' error` |

---

## 14. Address book (Contacts)

**Precondizione**: wallet aperto.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 14.1  | Header → Contacts.                                                    | AddressBookScreen: form in alto, "No contacts yet." in basso.                    | [auto] AddressBookScreenTest.`empty state then add then list then delete` |
| 14.2  | "Save contact" con campi vuoti.                                       | Errore "Both fields are required.".                                             | [auto] AddressBookScreenTest.`empty form rejects with required fields` |
| 14.3  | Label "Exchange", address signet valido → Save.                       | Il contatto compare in lista.                                                   | [auto] AddressBookScreenTest.`empty state then add then list then delete` |
| 14.4  | Stesso address con altra label.                                       | Rifiutato (address UNIQUE), la lista resta con una voce.                        | [auto] LabelsAndContactsUiTest.`adding the same address twice is refused and the list keeps one entry` |
| 14.5  | Delete sul contatto.                                                  | Riga rimossa.                                                                   | [auto] AddressBookScreenTest.`empty state then add then list then delete` |
| 14.6  | Lock → unlock → riapri wallet → Contacts.                             | Le modifiche persistono (la rubrica è per wallet, nel suo watch-state cifrato). | [ ] |

---

## 14b. Settings e Wallet settings

**Precondizione**: device PIN sbloccato, un wallet aperto.

Settings (rail) contiene le preferenze dell'app; le sezioni legate al
wallet (rinomina, label BIP-329, descriptor, rimozione) sono nel tab
Wallet settings.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 14b.1 | Settings.                                                             | Sezioni Appearance (Theme, Bitcoin unit + BIP177, Density), Esplora endpoint, Tor proxy, Language, Factory reset, più il rimando a "Open wallet settings". Nessuna sezione seed / backup / recovery phrase. | [auto] SettingsScreenTest.`wallet-scoped sections moved to Wallet settings, with a pointer here` |
| 14b.2 | Theme: System (default) / Light / Dark / Neon.                        | Il tema cambia subito; una scelta esplicita resta dopo il riavvio.             | [auto] SettingsScreenTest.`the three theme chips render` + `tapping Dark writes ThemeMode_Dark to SettingsStore` + `tapping Light writes ThemeMode_Light` |
| 14b.3 | Language: English / Italiano.                                         | Tutta l'app cambia lingua senza logout (§16c).                                  | [auto] SettingsScreenTest.`language picker writes the right tag to SettingsStore` (resa: manuale) |
| 14b.4 | Esplora endpoint: la sezione nomina la rete del wallet attivo; cambia l'URL → Save; poi Reset. | Le sync successive usano il nuovo endpoint solo per quella rete; Reset torna al default mempool.space. | [auto] SettingsScreenTest.`Esplora section names its network and edits only that network` + `typing a URL and tapping Save persists it as override` + `Reset wipes the override back to null` |
| 14b.5 | Tor: toggle ON, proxy `127.0.0.1:9050` (con `tor` locale attivo), Sync. | La sync passa dal proxy SOCKS5.                                                 | [auto] SettingsScreenTest.`Tor toggle reveals the proxy field and writes torEnabled=true` + DashboardScreenTest.`auto-sync passes the SettingsStore Tor proxy to the FFI` |
| 14b.6 | Appearance › Bitcoin unit: Sats (default) / BTC / Auto, BIP177.       | Importi coerenti in workspace e tracker; la scelta resta dopo il riavvio.       | [auto] SettingsScreenTest.`appearance shows System theme selected and sats unit by default` + `unit chips and BIP177 toggle write SettingsStore and update the example` |
| 14b.7 | Appearance › Density: Compact / Ultra-dense.                          | Righe da 32 px / 24 px nelle tabelle.                                           | [auto] SettingsScreenTest.`density chips write SettingsStore` (resa: manuale) |
| 14b.8 | Wallet settings → rinomina il wallet (nome vuoto, nome di un altro wallet, nome nuovo). | Vuoto e duplicato rifiutati con messaggio; il nome nuovo appare nell'header e nel Tracker. | [ ] |
| 14b.9 | Wallet settings → "Show watch-only descriptor".                       | Mostra xpub e descriptor esterno con pulsanti Copy; "Hide" li nasconde. Nulla è mostrato prima del click. | [auto] SecretActionsUiTest.`watch-only descriptor is exported only on request` |
| 14b.10| Wallet settings → BIP-329 "Export labels…".                           | Dialog nativo "Save as" (`bhodl-labels-YYYYMMDD-HHmmss.jsonl`); "Exported N label(s) to <path>". Il file ha una riga JSON per label. | [auto] LabelsSectionTest.`Export happy path calls FFI then save dialog and surfaces success` (dialog nativo: manuale) |
| 14b.11| "Import labels…" dello stesso file.                                   | "Imported N label(s)." — idempotente, nessun duplicato.                         | [auto] LabelsSectionTest.`Import happy path forwards the JSONL to the FFI and reports the count` |
| 14b.12| Importa un file JSONL esportato da Sparrow per lo stesso wallet.      | Le label di tx / address compaiono in Transactions e Addresses.                 | [ ] |
| 14b.13| Wallet address-list → "Show watch-only descriptor".                   | Messaggio che il wallet non ha un descriptor.                                   | [auto] SecretActionsUiTest.`address-list wallet reports that it has no descriptor` |

---

## 14c. Rimozione di un wallet

**Precondizione**: almeno due wallet; uno aperto.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 14c.1 | Wallet settings → "Remove this wallet".                               | Primo tap: "Tap again to remove this wallet from BHODL."; nulla è cancellato.   | [auto] SecretActionsUiTest.`remove this wallet needs a second tap` |
| 14c.2 | Secondo tap ("Confirm: remove this wallet").                          | Torna al Tracker; la riga non c'è più; il net worth non include più quel saldo. | [ ] |
| 14c.3 | `Get-ChildItem $env:USERPROFILE\.bhodl\watch-state`.                  | Il file `<uuid>.sqlite` del wallet rimosso non esiste più (anche su Windows: l'handle viene chiuso prima). | [ ] |
| 14c.4 | Categorie, goal collegati a quel wallet.                              | Le categorie delle sue tx sono sparite; un goal collegato al wallet non punta più a un wallet inesistente. | [ ] |
| 14c.5 | Riaggiungi lo stesso xpub.                                            | Accettato; storico ricostruito dalla sync, label locali perse (a meno di re-import BIP-329). | [ ] |

---

## 15. Erase ALL local wallets (Welcome / Factory reset)

**Precondizione**: dati esistenti.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 15.1  | Da DevicePinUnlock torna a Welcome (←) → "Erase ALL local wallets…".  | Dialog "Erase ALL wallets on this device?": con un registry chiede il PIN del dispositivo **e** `DELETE`; senza registry (o con "I forgot the device PIN") chiede `DELETE` e la spunta "I can add every wallet again from its xpub, descriptor or addresses". Conferma disabilitata finché manca qualcosa. | [auto] WelcomeScreenTest.`reset entry is offered but does not wipe without confirmation` + SecretActionsUiTest.`wipe dialog stays disabled for anything but DELETE` + `Welcome reset with a registry requires the device PIN and counts wrong attempts` |
| 15.2  | Cancel.                                                               | Nulla cancellato.                                                               | [auto] SecretActionsUiTest.`wipe Cancel keeps every file and the unlocked wallet` |
| 15.3  | Con PIN: PIN + `DELETE` → "Erase all wallets". Senza PIN: `DELETE` + spunta → "Move wallets to recovery folder". | Con PIN: "Wiped N items.", `~/.bhodl/` senza registry, watch-state né storico PIN. Senza PIN: "Moved N items to …/.bhodl-trash/<AAAAMMGG-hhmmss>", stessi percorsi relativi, recuperabili. Vedi §57. | [auto] SecretActionsUiTest.`forgot device PIN switches to the trash path` + `Welcome reset without a registry moves the files to trash after the seed acknowledgement` + LocalWipeTrashTest |
| 15.4  | Settings → "Factory reset (all wallets)…" → `DELETE`.                 | Sessione già autenticata: basta `DELETE`, cancellazione definitiva; l'app torna a Welcome. | [auto] SettingsScreenTest.`Wipe is two-tap and clears the WalletStore on confirm` + SecretActionsUiTest.`confirmed wipe empties the data dir and drops PIN history` |
| 15.5  | Riapri l'app.                                                         | Boot guard parte da Welcome.                                                    | [ ] |

---

## 16. Scrolling — finestra ridotta

**Precondizione**: wallet aperto.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 16.1  | Riduci la finestra al minimo (960×640).                               | I pulsanti in fondo a ogni schermata (Welcome, PIN, Add watch, LegacyCleanup) restano raggiungibili via scroll. | [ ] |
| 16.2  | Transactions e UTXOs con molte righe.                                 | Scrolling fluido, header della tabella fisso.                                   | [ ] |

---

## 16b. L3 integration tests (Testcontainers — bitcoind regtest + electrs)

**Precondizione**: Docker Desktop in esecuzione. Test harness:
`./gradlew :apps:desktop:integrationTest`. Stack reale (bitcoind 28.x
regtest + electrs v3.2.0 via Testcontainers); Linux/macOS first-class,
Windows funziona se Docker Desktop è up. I test coprono solo la sync
watch-only.

| #     | Test                                      | Cosa verifica                                                                                       | Pass |
|-------|-------------------------------------------|------------------------------------------------------------------------------------------------------|------|
| 16b.1 | `RegtestSyncIntegrationTest`              | Funding di un indirizzo del wallet watch → sync Esplora → il saldo arriva. Smoke FFI ↔ BDK ↔ Esplora REST. | [auto] RegtestSyncIntegrationTest (integrationTest) |
| 16b.2 | `cargo test -p bhodl-wallet-onchain --test regtest -- --ignored` (con `BITCOIND_EXE` / `ELECTRS_EXE`, vedi `scripts/regtest-binaries.sh`) | Due test watch-only: bitcoind paga l'indirizzo di ricezione dell'xpub, sync, verifica saldo / UTXO / copia della label di ricezione. | [ ] (ignorati in CI) |

---

## 16c. UX consistency — back arrow, auto-focus, language switch

**Precondizione**: device PIN sbloccato.

| #     | Steps                                                                 | Risultato atteso                                                                | Pass |
|-------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 16c.1 | Apri Add watch, Contacts, Settings, Security Hub, Transaction details. | Ognuna mostra `←` in alto a sinistra (testTag `bhodl.backarrow`) che torna indietro. | [ ] |
| 16c.2 | Lock → DevicePinUnlock.                                               | Cursore già nel campo PIN.                                                      | [ ] |
| 16c.3 | Settings → Language → lingua opposta.                                 | Resti su Settings, le stringhe cambiano subito, nessun ritorno al PIN.          | [ ] |
| 16c.4 | Dopo 16c.3 naviga nel wallet e nel tracker.                           | Tutto nella nuova lingua; back stack coerente.                                  | [ ] |

---

## 17. Smoke automatici (FFI surface)

Da terminale, prima di considerare l'app testabile:

```sh
./gradlew :shared:jvmTest --tests "io.bhodl.ffi.UniffiSmokeTest"
./gradlew :apps:desktop:jvmTest
cargo test --workspace
```

| #     | Test                                                                  | Pass |
|-------|-----------------------------------------------------------------------|------|
| 17.1  | `:shared:jvmTest` — UniffiSmokeTest passa (i `@Ignore` sono attesi per test che richiedono rete). | [auto] CI (`:shared:jvmTest`) |
| 17.2  | `:apps:desktop:jvmTest` — verde.                                      | [auto] CI (`:apps:desktop:jvmTest`) |
| 17.3  | `cargo test --workspace` — verde (bhodl-wallet-onchain 300+ test incl. watch, bhodl-uniffi 46). | [auto] CI (`cargo test --workspace`) |
| 17.4  | `cargo clippy --workspace --all-targets -- -D warnings` — pulito.     | [auto] CI (`cargo clippy`) |
| 17.5  | `cargo fmt --all --check` — pulito.                                   | [auto] CI (`cargo fmt --check`) |
| 17.6  | `./gradlew koverHtmlReport` → `build/reports/kover/html/index.html` mostra ≥55% overall, ≥70% screens. | [ ] |
| 17.7  | `cargo llvm-cov report` mostra ≥95% bhodl-core, ≥77% bhodl-wallet-onchain. | [ ] |

---

## 17b. LegacyCleanup — dati di una build con seed

**Precondizione**: una data dir creata con una build precedente al
2026-10-23 che contenga almeno un signing wallet (creato da seed o
restore), meglio se con "Add to Tracker" attivo (mirror watch-only), e
uno watch-only aggiunto a mano. Fai una copia della cartella prima di
iniziare. Annota le recovery phrase.

| #      | Steps                                                                 | Risultato atteso                                                                | Pass |
|--------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 17b.1  | Avvia la build attuale → device PIN.                                  | Dopo l'unlock appare "Remove old spending wallets": spiega che BHODL è watch-only, che i wallet creati da recovery phrase saranno cancellati definitivamente (chiavi incluse) e come continuare a tracciarli (riaggiungerli da xpub). Riga "Old spending wallets in the wallet list: N". | [auto] WalletPathsTest.`legacySigningDataExists_detects_a_per_wallet_signing_dir` (schermata: manuale) |
| 17b.2  | Senza spuntare la checkbox.                                           | "Delete old wallets" disabilitato.                                              | [ ] |
| 17b.3  | "Not now".                                                            | Va al Tracker. I signing wallet non compaiono nella lista; il mirror e il watch manuale sì. Su disco `wallets/<uuid>/` è ancora presente. | [ ] |
| 17b.4  | Lock → unlock.                                                        | LegacyCleanup ricompare.                                                        | [ ] |
| 17b.5  | Spunta "I have the recovery phrases of these wallets" → "Delete old wallets". | Va al Tracker. `wallets/` non contiene più le directory dei signing wallet; il mirror resta come wallet watch-only normale con il suo storico e le sue categorie; il watch manuale è intatto. | [auto] registry::`purge_legacy_signing_wallets_keeps_mirrors_as_plain_watch_wallets` (cargo) + WalletPathsTest.`purgeLegacySigningData_removes_every_signing_artifact_and_keeps_watch_data` |
| 17b.6  | Lock → unlock.                                                        | LegacyCleanup non compare più.                                                  | [ ] |
| 17b.7  | Data dir con solo un'installazione pre-3.0 (`wallet.sqlite`, `wallet.kdf.json`, `wallet.salt`, `wallet.network` alla radice, nessun registry). | Al boot va direttamente a LegacyCleanup (nessun PIN richiesto). Dopo la cancellazione: Welcome. I file `wallet.*` sono spariti. | [auto] WalletPathsTest.`legacySigningDataExists_detects_each_root_artifact` (boot: manuale) |
| 17b.8  | Su Windows, tieni aperto un file della cartella con un altro programma e cancella. | "Some files could not be deleted (N). Close other programs…"; nessuna navigazione; riprovando dopo aver chiuso il programma riesce. | [ ] |

---

## 17c. Security Hub

**Precondizione**: device PIN sbloccato, almeno un wallet.

| #      | Steps                                                                 | Risultato atteso                                                                | Pass |
|--------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------|------|
| 17c.1  | Apri il Security Hub (shield nella barra di stato, Settings › Open Security Hub o palette). | Cinque righe, in ordine: Device PIN, Auto-lock, Connection, Wallets, BIP-329 labels export. Nessuna riga su backup o recovery phrase. | [auto] SecurityHubTest.`there is one row per check, in display order` + `hub renders rows and routes the actions` |
| 17c.2  | Riga Wallets.                                                         | "N watch-only (BHODL never holds private keys)", informativa (non conta nel punteggio). | [ ] |
| 17c.3  | Mainnet in clearnet vs Tor ON.                                        | Connection "attention" in clearnet su mainnet, OK con Tor; su signet clearnet è solo informativa. | [auto] SecurityHubTest.`clearnet mainnet needs attention` + `clearnet on signet is only informative and no wallet makes labels informative` |
| 17c.4  | Esporta le label (14b.10) e torna all'Hub.                            | Riga labels con la data di export; con Tor ON tutte le righe contate sono OK.   | [auto] SecurityHubTest.`exported labels and Tor make every counted check OK` |
| 17c.5  | Hub senza wallet aperto.                                              | La riga labels non ha azione.                                                   | [auto] SecurityHubTest.`labels row has no action without an open wallet` |
| 17c.6  | Factory reset, poi nuovo PIN e Hub.                                   | Le date di export sono azzerate.                                                | [auto] SecurityHubTest.`local wipe forgets the Security Hub dates` |

---

## 18. Bug noti / `@Ignore`d

Cose che NON passano per scelta (regression markers, da fixare in follow-up):

- `diagnostic_echo_string demonstrates the uniffi free-fn Result bug` — documenta l'ABI hazard (docs/uniffi-runtime.md). Quando uniffi/Ubique upstream è fixato, riattivare e validare.
- Test FFI che richiedono rete (`@Ignore` in `UniffiSmokeTest`): da riattivare in locale per verifiche end-to-end.

---

## 19b. Failure escalation

Se uno scenario fallisce in modo riproducibile:

1. Cattura stack trace completo dal terminale gradle / cargo.
2. Cattura screenshot della schermata dove fallisce.
3. Verifica `cargo clippy --workspace -- -D warnings` e `:shared:jvmTest` siano puliti pre-bug.
4. Annota il contenuto di `~/.bhodl/` (nomi file, non contenuto): registry, `watch-state/`, eventuali file legacy `wallet.*` / `wallets/`.
5. Apri issue / annota qui:

```
### FAIL: <breve descrizione>
- Scenario: <es. 4b.11>
- Steps minimi: <riproduzione>
- Expected: <atteso>
- Actual: <visto>
- Stack: <truncated>
- Versione: commit `<sha>`
- Data dir: <file presenti>
```

---

## 20. Definition of done

Il wallet watch-only è considerato pronto per una release alpha quando:

- Tutti gli scenari 0–17c sono `[x]` su almeno una macchina Windows e una Linux/macOS.
- 17.1–17.7 verdi.
- L3 integration test (16b.1) passa su CI.
- Bug dei `@Ignore` (18.x) documentati e tracciati.
- Il commit corrente è verde su CI per i 3 OS + integration-test job.

---

## 21. Phase 7.2.4 — Savings goal ↔ bucket NW (manuale)

Pre-requisiti: device PIN sbloccato, Tracker home aperto, almeno un watch wallet con balance > 0 sat, almeno un investment in `tracking_investments` con un current value (manuale o auto-fetched), `SettingsStore.fiatCurrency != NONE`, PriceCache popolato (basta una refresh sulla Tracker home).

### 21.1 Goal manuale resta invariato (regression)  `[auto]`

> Automatizzato: GoalBucketsTest.`manual bucket returns persisted current_sat unchanged` + registry::savings_add_then_list_round_trips_all_fields (cargo)

1. Tracker → Open Tracking dashboard.
2. Apri "+ Add" sulla Savings goals card.
3. Compila: name = "Manual test", target type = Absolute, target = 1000000 sat, Source = Manual, current = 250000 sat, color = cyan. Salva.
4. Atteso: progresso 25%, nessun badge "AUTO", sotto la barra "250.000 sat of 1.000.000 sat".
5. Riapri il goal, verifica che target type sia ancora Absolute e che il campo "Current progress (sat)" sia editabile.

### 21.2 Goal Investimenti auto-tracked  `[partial]`

> Parziale: GoalBucketsTest.`investments bucket sums fetched value when present` — manual: dialog preview, AUTO badge, live refresh

1. Conferma che nella Investments card ci sia almeno un investment con Value > 0 (es. AAPL 10 @ €150).
2. Apri "+ Add" sulla Savings goals card.
3. Compila: name = "Investimenti", Source = "Investments (auto)". A destra di quella riga deve apparire "≈ X sat" diverso da zero (somma current value × tasso BTC/EUR).
4. Imposta target type = "% of net worth", pct = 40.
5. Sotto l'input pct vedi "≈ <sat> sat at current net worth (recomputed live)".
6. Nella riga "Current progress" vedi `Current progress: <sat> (auto-tracked)`, in sola lettura.
7. Salva. La nuova riga deve mostrare il badge "AUTO · Investments" e un `<pct>%` allineato col valore degli investments / target derivato dal NW.
8. Senza chiudere il dialog, aggiungi un secondo investment con Value 500 €. Atteso: il numero della Savings goal aumenta dopo il prossimo refresh (≤ 2 min, oppure premi "Refresh balances").

### 21.3 Goal Bitcoin cash auto-tracked  `[auto]`

> Automatizzato: GoalBucketsTest.`bitcoin_cash bucket returns aggregate watch balance` + `badge label is fixed string for investments and bitcoin_cash`

1. Apri "+ Add", name = "Contanti BTC", Source = "Bitcoin cash — all wallets (auto)".
2. Preview a destra mostra l'aggregato dei watch-only (uguale al numero "Asset" del Net Worth snapshot).
3. Target type = Absolute, target = doppio dell'asset corrente. Salva.
4. Atteso: badge "AUTO · Bitcoin cash", progress ≈ 50%, "Current progress" mirror del totale.
5. Aggiungi un secondo watch wallet con balance > 0 in Tracker home → torna sulla Dashboard. Il goal deve aggiornarsi senza re-edit.

### 21.4 Goal su wallet specifico  `[auto]`

> Automatizzato: GoalBucketsTest.`badge label resolves wallet name from current registry` + `badge label is missing marker when wallet ref is gone`

1. Crea un goal "Fondo emergenza", Source = "Specific wallet (auto)".
2. Sotto compare la sezione "Wallet" con un chip per ogni watch wallet. Sceglie un wallet con balance noto.
3. Preview a destra di "Specific wallet" diventa "≈ <balance del wallet selezionato>".
4. Target type = Absolute, target = balance * 2. Salva.
5. Atteso: badge "AUTO · Wallet · <nome wallet>".
6. Rinomina il wallet (se hai il flusso di rename) → riapri la dashboard: il badge segue il nuovo nome senza touch del goal.
7. Rimuovi il wallet (Settings → Remove watch) → il goal mostra "AUTO · Wallet (missing)" e progress 0%, senza crash.

### 21.5 % NW dinamico  `[auto]`

> Automatizzato: SavingsGoalsTargetMathTest.`targetSatFromPct computes thirty percent of one BTC` + `targetSatFromPct hundred percent equals net worth`

1. Crea un goal con target type = "% of net worth", pct = 30, Source qualsiasi.
2. Annota il "<sat>" mostrato in "≈ <sat> sat at current net worth (recomputed live)".
3. Fai aumentare l'asset (faucet su signet, o ricarica con prezzo BTC più alto):
   - Refresh balances → il valore "of <target_sat>" nella card deve essere ricalcolato (~30% del nuovo NW), non frozen al save.
4. Verifica anche al contrario (drop di balance): il target scende proporzionalmente.

### 21.6 Migrazione DB pre-7.2.4  `[partial]`

> Parziale: registry::savings_migration_is_idempotent_across_opens (cargo) — manual: real pre-7.2.4 DB, edit + restart

1. Avvia con un DB esistente che ha goal pre-migrazione (creato in Phase 7.2.2 / 7.2.3).
2. Apri Tracking dashboard: i goal devono apparire con `current_sat` invariato (bucket = manual implicito), nessun badge AUTO, nessun errore in log.
3. Edita uno di questi goal: la dialog parte con Source = Manual selezionata. Cambia Source = Investments, salva.
4. Riavvia l'app: il goal deve essere ricaricato con badge "AUTO · Investments". Il refresh recompute il `current_sat` senza l'intervento dell'utente.

### Done
- 21.1–21.6 ✓ su Windows.
- `cargo test -p bhodl-wallet-onchain --lib savings` verde.
- `:apps:desktop:jvmTest --tests GoalBucketsTest` verde.
- Nessun warning nuovo nella build (warning preesistenti su Divider/ClipboardManager sono unrelated, vedono PR di cleanup separata).

---

## 22. Phase 7.3 — Investments in NW + allocation drift (manuale)

Pre-requisiti: device PIN sbloccato, Tracker home aperto, almeno un watch wallet con balance > 0 sat, `SettingsStore.fiatCurrency` a EUR (o altra non-NONE), PriceCache popolato (basta una refresh — visibile dal numero in fiat sotto l'hero).

### 22.1 Investments si sommano al Net Worth  `[partial]`

> Parziale: GoalBucketsTest.`investmentsTotalSat prefers fetched over manual` — manual: NW hero total + breakdown row

1. Tracker home: annota il numero "Net worth" mostrato dal NetWorthHero (es. 5.000.000 sat / €4.000).
2. Open Tracking dashboard → Investments card → Add: name="ETF Test", quantity=10, cost_basis=€100, current_value=€500 (manual). Salva.
3. Torna alla Tracker home. Atteso:
   - Net worth Hero aumenta di ~ €5000 / il loro equivalente in sat al rate corrente.
   - NetWorthBreakdownTable ora mostra **una nuova riga "Investments (fiat)"** col valore corretto e Weight ≠ 0.
   - La riga "Wallets (BTC)" resta uguale a prima.
   - Riga "Net worth" in fondo = nuovo totale.

### 22.2 Bucket BITCOIN_CASH non si gonfia con gli investments  `[auto]`

> Automatizzato: GoalBucketsTest.`bitcoin_cash bucket does NOT include investments after 7-3 rename`

Verifica regressione di Phase 7.2.4 dopo il rename `totalAssetSat → bitcoinAssetSat`.
1. Apri Tracking dashboard → Savings goals → Add: name="Tutti i BTC", Source="Bitcoin cash — all wallets (auto)", target Absolute = 2× balance BTC. Salva.
2. La progress bar deve essere ~50%.
3. Aggiungi un investment grosso (es. €10.000 valore). Salva. Refresh.
4. Atteso: la progress bar del goal "Tutti i BTC" **NON cambia** — il bucket BITCOIN_CASH guarda solo i watch wallets. Il numero "X of Y" resta lo stesso del passo 2.
5. (Cross-check) NetWorthHero invece si è alzato: significa che la separazione bitcoin/total funziona.

### 22.3 % NW dinamico include investments (Phase 7.3 amendment)  `[partial]`

> Parziale: SavingsGoalsTargetMathTest.`targetSatFromPct computes thirty percent of one BTC` — manual: NW includes investments

1. Salva un goal con target type="% of net worth", pct=30, Source="Investments (auto)". Annota il "≈ X sat at current net worth" mostrato nel dialog.
2. Aggiungi un investment grosso (€5000).
3. Riapri il dialog dello stesso goal. Atteso: il "≈ X sat" si è alzato — la conversione % usa il NW totale (BTC+investments), non solo BTC.

### 22.4 DRIFT chip ≥±5pp  `[auto]`

> Automatizzato: SavingsGoalsTargetMathTest.`drift is positive when bucket is above target` + `drift is null for manual bucket regardless of pct target`

1. Crea un goal "Investimenti = 30%", Source="Investments (auto)", target type="% of net worth", pct=30. Salva.
2. Se gli investments correnti sono molto sopra o sotto il 30% del NW, dovresti già vedere una chip "DRIFT +N pp" (o "-N pp") in giallo accanto al nome del goal.
3. Caso forced over-target: imposta gli investments a valore tale che superi il 35% del NW (es. NW=€8k, investments=€3k → 37% → "+7 pp"). Chip visibile.
4. Caso forced under-target: rimuovi investments fino a scendere sotto il 25%. Chip "−5 pp" o più.
5. Caso "on target ±4 pp": ricarica fino a stare a ±4pp. Chip NON visibile (threshold Vanguard ±5pp).
6. Cross-check goal MANUAL: anche se ha target_pct = 30%, NESSUNA chip drift (rumore — il current è digitato dall'utente).

### 22.5 PriceCache freddo (cold start)  `[partial]`

> Parziale: GoalBucketsTest.`investmentsTotalSat returns zero when price not cached` — manual: "—" placeholder, live update

1. Avvia l'app, apri Tracker home prima che PriceCache abbia fatto la prima fetch (usualmente <2s).
2. Se hai investments registrati, la riga "Investments (fiat)" deve mostrare "—" nelle colonne Value e Weight (non "0").
3. Quando il PriceCache risponde, la riga deve aggiornarsi col numero reale senza reload manuale.

### 22.6 Investments vuoti  `[partial]`

> Parziale: GoalBucketsTest.`investmentsTotalSat returns zero for empty list` — manual: breakdown row hidden

1. Rimuovi tutti gli investments via Edit → Delete.
2. La riga "Investments (fiat)" deve **scomparire** dal NetWorthBreakdownTable.
3. NetWorthHero = solo BTC, come pre-7.3.

### Done
- 22.1–22.6 ✓ su Windows.
- `:apps:desktop:jvmTest --tests GoalBucketsTest --tests SavingsGoalsTargetMathTest` verdi (22+17=39 test, 0 fail).
- Suite jvmTest completa verde (531 test).
- Nessun TODO Phase 7.3 residuo in `NetWorthBreakdownTable.kt` (la nota "Investments and manual cash categories arrive with Phase 7.3" è stata rimossa).

---

## 23. Phase 7.4 — Manual Asset Accounts (manuale)

Pre-requisiti: device PIN sbloccato, Tracker home aperto, PriceCache popolato (almeno una refresh dopo l'avvio), `SettingsStore.fiatCurrency` ≠ NONE.

### 23.1 Add cash account → NW si alza  `[partial]`

> Parziale: GoalBucketsTest.`manualAssetsTotalSat sums multi-kind multi-row` — manual: card row, NW hero, breakdown row

1. Tracking dashboard → scorri fino alla nuova card **MANUAL ASSETS**.
2. Tap "+ Add". Compila: Name = "Conto corrente", Type = Cash, Value = 5000, Currency = EUR, Notes = (vuoto). Salva.
3. Atteso:
   - Card mostra una riga "Conto corrente" col badge CASH, value €5.000,00 e sat equivalent (es. ≈ 6.250.000 sat se BTC=€80k).
   - Torna alla Tracker home. NW Hero deve essere salito di ~6.25M sat (BTC + investments + manual).
   - NetWorthBreakdownTable mostra una nuova riga "Cash (fiat)" col valore corretto.

### 23.2 Multi-kind, multi-currency  `[partial]`

> Parziale: GoalBucketsTest.`manualAssetsByKindSat groups by kind and converts each` — manual: row order, badges

1. Add: "Example home", Real estate, 350000, EUR.
2. Add: "Account USD", Brokerage, 1500, USD.
3. Add: "Car 2022", Vehicle, 25000, EUR.
4. La card MANUAL ASSETS ora ha 4 righe, ognuna col badge giusto.
5. NetWorthBreakdownTable mostra 4 nuove righe in ordine canonico:
   - "Cash (fiat)" — €5000
   - "Brokerage (manual)" — $1500 (convertito al rate USD)
   - "Real estate" — €350000
   - "Vehicles" — €25000
6. Cross-check: il Net worth in fondo alla tabella deve corrispondere a BTC + investments + ~€381k.

### 23.3 Cold start placeholder  `[partial]`

> Parziale: GoalBucketsTest.`manualAssetsTotalSat returns zero when price not cached` — manual: placeholder row

1. Avvia l'app, apri Tracking dashboard prima della prima fetch del PriceCache (qualche secondo).
2. Se hai manual assets, la card mostra "—" nella colonna ≈ SAT delle righe.
3. NetWorthBreakdownTable mostra un'UNICA riga placeholder "Manual assets" con "—" in Value/Weight, finché il prezzo non arriva (poi si espande in 8 row per kind).

### 23.4 Goal bucket "Specific manual asset"  `[auto]`

> Automatizzato: GoalBucketsTest.`manual_asset bucket returns sat for matching ref` + `manual_asset badge label resolves asset name`

1. Apri Savings Goals → Add: name = "Emergency fund", Source = "Specific manual asset (auto)".
2. Compare il sub-picker "Manual asset" con chip per ogni manual asset (es. Conto corrente, Account USD).
3. Seleziona "Conto corrente". Preview a destra mostra "≈ X sat" col valore convertito.
4. Imposta target absolute = 10000 EUR (in sat). Salva.
5. Atteso: la card SavingsGoals mostra il goal "Emergency fund" col badge **AUTO · Asset · Conto corrente**, progresso aggiornato in tempo reale dal valore del conto.
6. Aggiorna il conto corrente a €6000 (Edit → Value=6000 → Save). Il goal deve aumentare di progress senza altra azione.

### 23.5 Rimozione asset → goal mostra "Asset (missing)"  `[auto]`

> Automatizzato: GoalBucketsTest.`manual_asset badge label is missing marker when asset is gone` + `manual_asset bucket returns zero for missing ref`

1. Crea un goal bucket=manual_asset legato a un asset esistente.
2. Vai sulla card MANUAL ASSETS, edita quell'asset → Delete.
3. Torna alla Savings Goals card. Il goal mostra badge "AUTO · Asset (missing)", progress 0%, nessun crash.

### 23.6 % NW dinamico include manual assets  `[partial]`

> Parziale: SavingsGoalsTargetMathTest.`targetSatFromPct computes thirty percent of one BTC` — manual: NW includes manual assets

1. Goal "Investimenti = 30% NW", bucket=investments, target type = % NW, pct = 30. Annota il "≈ X sat at current net worth" nel dialog.
2. Aggiungi un real estate da €100.000.
3. Riapri il dialog del goal. Atteso: il "≈ X sat" è salito (perché il NW è cresciuto di ~€100k).

### 23.7 DRIFT chip risponde anche al bucket manual_asset  `[partial]`

> Parziale: SavingsGoalsTargetMathTest.`drift is negative when bucket is below target` — manual: manual_asset-specific case

1. Goal "Cash 10% NW", bucket=manual_asset (legato al Conto corrente), target type = % NW, pct = 10.
2. Se il Cash è al 5% del NW totale (5% sotto target), chip "DRIFT -5 pp" visibile (gialla).
3. Se sale al 15%, "DRIFT +5 pp".
4. Se sta al 7-13%, chip NON visibile (sotto threshold ±5pp).

### 23.8 Notes preservation  `[partial]`

> Parziale: registry::manual_assets_update_persists_field_changes (cargo) — manual: dialog prefill

1. Add: Name = "Car 2022", Vehicle, 25000, EUR, Notes = "Acquisto 2022, 40.000 km".
2. Tap row → edit. Il campo Notes deve essere precompilato col testo.
3. Modifica le notes a "Acquisto 2022, 50.000 km" → Save.
4. Riapri → le notes nuove sono persistenti.

### Done
- 23.1–23.8 ✓ su Windows.
- `cargo test -p bhodl-wallet-onchain --lib manual_asset` verde (8 test).
- `:apps:desktop:jvmTest` suite completa verde (542 test, 0 fail).
- Nessun TODO Phase 7.4 residuo nel codice.

---

## 24. Phase 7.5 — Rules engine + Recurring detection (manuale)

Pre-requisiti: device PIN sbloccato, Tracker home aperto, almeno un watch wallet con qualche tx OUTGOING ricorrente (es. 4 pagamenti mensili a Netflix), `SettingsStore.fiatCurrency` ≠ NONE.

### 24.1 Crea una rule "AMZN → Shopping"  `[partial]`

> Parziale: registry::rules_add_then_list_round_trips_all_fields (cargo) — manual: card row

1. Tracking dashboard → scorri fino alla card **AUTO-CATEGORIZATION**.
2. Tap "+ Add". Compila: Name="Amazon → Shopping", When=Description, op=contains, Value="AMZN", Then category="Shopping", Priority=5, Enabled=ON. Salva.
3. Atteso: la rule appare nella card con: name | "description contains 'AMZN'" | → Shopping | P5 | ON.

### 24.2 Apply-at-import su nuova tx  `[partial]`

> Parziale: RulesEngineTest.`applyRules returns category of first matching rule` + `text op contains is case insensitive` — manual: apply-at-import wiring

1. Genera un tx outgoing il cui txid contenga "AMZN" (su signet o regtest col faucet). Oppure rinomina una tx esistente per il test.
2. Refresh balances → la History dovrebbe mostrare il tx con category "Shopping" pre-popolata, senza intervento manuale.
3. Se l'utente edit manualmente la category dopo l'auto-apply, quella scelta prevale (no overwrite automatico).

### 24.3 Reapply-to-history
1. Crea una rule nuova che match transazioni già passate (es. "ends_with abc" se hai tx vecchi con txid che terminano così).
2. Click "↻ Reapply" sulla card AUTO-CATEGORIZATION.
3. Atteso: le tx storiche senza category ora hanno la category della rule. Le tx con category manuale non cambiano.

### 24.4 Toggle rule disabled  `[auto]`

> Automatizzato: RulesEngineTest.`applyRules skips disabled rules` + registry::rules_update_persists_enabled_toggle (cargo)

1. Edit una rule esistente → switch Enabled = OFF → Save.
2. La row mostra "OFF" in grigio.
3. Click Reapply: niente cambia (rule è inattiva).

### 24.5 Recurring detection — Netflix monthly  `[auto]`

> Automatizzato: RecurringDetectorTest.`monthly netflix happy path is detected` + `amount variance under 5 percent yields HIGH confidence`

1. Genera 4 tx outgoing mensili identici (stesso payee/description, stessa amount).
2. Refresh. Atteso: card **DETECTED** appare con un row:
   - Display name "netflix" (o l'identifier che hai usato)
   - "11.99 BTC · monthly · next ~ Mar 15"
   - Chip HIGH (4 occurrences, identical amounts)
   - Bottoni "+ Bill" e "Dismiss"

### 24.6 Accept as Bill  `[partial]`

> Parziale: RecurringCardBitcoinLensTest.`accept and dismiss actions remain wired` — manual: bill creation + auto-dismiss

1. Click "+ Bill" sul candidate Netflix.
2. Atteso: nuovo bill row nella Bills card con name=netflix, amount=11.99, due_day=quel mese.
3. La card DETECTED non mostra più quel candidate (è stato anche dismissed automaticamente — è diventato un bill).

### 24.7 Dismiss  `[auto]`

> Automatizzato: RecurringDetectorTest.`dismissed keys are filtered out` + registry::recurring_dismiss_persists_and_is_idempotent (cargo)

1. Genera 4 tx mensili a "spotify" identici.
2. Card DETECTED mostra il candidate.
3. Click "Dismiss".
4. Refresh → il candidate NON ritorna anche dopo successive sync.
5. (Optional admin) `registry.undismissRecurring("spotify")` → ricompare al refresh.

### 24.8 Confidence MEDIUM con 3 occurrences  `[auto]`

> Automatizzato: RecurringDetectorTest.`confidence drops to MEDIUM at 3 occurrences`

1. Genera 3 tx mensili (sotto la soglia HIGH che richiede 4).
2. Card DETECTED mostra chip "MED" giallo invece di "HIGH" verde.

### 24.9 Priority ordering  `[auto]`

> Automatizzato: RulesEngineTest.`applyRules returns category of first matching rule` + registry::rules_list_orders_by_priority_desc_then_created_asc (cargo)

1. Crea due rule che entrambe matchano lo stesso tx:
   - Rule A: contains "x", priority 5, → Cat A
   - Rule B: contains "x", priority 8, → Cat B
2. New tx con "x" nel txid → category = B (priority più alta vince).

### 24.10 Amount rule  `[auto]`

> Automatizzato: RulesEngineTest.`amount gt strict`

1. Crea rule "Large outgoing > 1M sat → Casa".
2. New tx outgoing 5M sat → category = Casa.

### Done
- 24.1–24.10 ✓ su Windows.
- `cargo test -p bhodl-wallet-onchain --lib -- rules_ recurring_` verde (12 test).
- `:apps:desktop:jvmTest --tests RulesEngineTest --tests RecurringDetectorTest` verde (14+15=29 test).
- Suite jvmTest completa verde (571 test, 0 fail).
- Nessun TODO Phase 7.5 residuo nel codice.

---

## 25. Phase 7.6 — KPI insights + cash flow forecast + monthly review (manuale)

Pre-requisiti: device PIN sbloccato, Tracker home aperto, almeno 90gg di tx storia per KPI rappresentativi, PriceCache popolato.

### 25.1 KPI strip visibile con valori coerenti  `[partial]`

> Parziale: KpiStripV7BitcoinLensTest.`five KPI cards rendered including OPP_COST` — manual: subtitles

1. Apri Tracker home. Subito sotto NetWorthHero deve apparire una row con 4 card:
   - SAVINGS · +N% · "last 30d"
   - BURN · X sat · "/month, 90d avg"
   - FIRE · Y sat · "25× annual exp (4% rule)"
   - RUNWAY · Z mo · "at current burn"
2. Atteso: con tx storiche non degenerate, almeno 2 dei 4 hanno valore (non "—").

### 25.2 Savings rate edge cases  `[partial]`

> Parziale: FinanceKpisTest.`savings rate null when income is zero` + `savings rate negative when expense exceeds income` — manual: colour

1. Wallet senza incoming negli ultimi 30gg → SAVINGS = "—" (income=0).
2. Wallet con solo income → SAVINGS = "+100%".
3. Wallet con expense > income → SAVINGS negativo, colore rosso.

### 25.3 FIRE = 300 × Burn  `[auto]`

> Automatizzato: FinanceKpisTest.`FIRE number is 300 times burn rate`

1. Annota il valore di BURN (es. 1.000.000 sat/mese).
2. Atteso: FIRE = 300.000.000 sat. (Verifica facendo il calcolo).

### 25.4 Runway = NW / Burn  `[partial]`

> Parziale: FinanceKpisTest.`runway is total asset over burn rate` — manual: colour thresholds

1. Annota NW Hero (es. 12M sat) e Burn (1M/mese).
2. Atteso: RUNWAY = 12.0 mo. Color verde (≥12 mo).
3. Se Burn cresce a 3M/mese, RUNWAY = 4 mo, color rosso (<6).

### 25.5 Cash flow forecast 90gg  `[partial]`

> Parziale: CashFlowForecastTest.`empty inputs return horizon plus one flat points` — manual: card + footer

1. Card "CASH FLOW FORECAST · 90 DAYS" appare sotto KpiStrip se ci sono input (bills o recurring).
2. Sparkline mostra una linea che parte dal valore corrente del NW.
3. Footer: "In 90 days: ~ X sat" + "Δ ±Y%" + "by YYYY-MM-DD".

### 25.6 Forecast cala quando si aggiunge un bill  `[auto]`

> Automatizzato: CashFlowForecastTest.`bill due on day 15 produces an outflow on day 15 of every future month`

1. Annota il valore "In 90 days" attuale.
2. Aggiungi un bill ricorrente di 5M sat / mese.
3. Refresh. Atteso: il valore "In 90 days" è sceso di ~15M sat (3 mesi × 5M).

### 25.7 Forecast diventa rosso quando finisce in negativo  `[partial]`

> Parziale: CashFlowForecastTest.`balance can go negative when outflow exceeds inflow` — manual: red colour

1. Aggiungi bills + recurring che superano il NW corrente nei 90gg.
2. Atteso: la sparkline + footer diventano rosso (`financialNegative`), valore con `−` prefix.

### 25.8 Monthly review banner appare il 1°-3° del mese  `[partial]`

> Parziale: CashFlowForecastTest.`banner shows on day 1 when never dismissed` + `banner hidden after day 3` — manual: banner UI

1. (Test manuale: cambia la data di sistema al 1° giorno di un mese dopo l'uso ≥1 mese.)
2. Apri Tracker home → banner "Review last month" sopra NetWorthHero, accent neon.
3. Click "Review" → naviga al Tracking Dashboard.
4. Reset data al 4° giorno → banner scompare.

### 25.9 Monthly review dismiss persiste  `[partial]`

> Parziale: CashFlowForecastTest.`banner hidden if dismissed this month` + `banner shows again next month after dismiss` — manual: restart

1. Quando il banner è visibile, click "Dismiss".
2. Refresh / restart app. Atteso: banner NON appare.
3. Cambia mese → banner riappare automaticamente.

### 25.10 KPI fallback se non ci sono dati  `[partial]`

> Parziale: FinanceKpisTest.`empty events yields all null KPIs` + KpiStripV7BitcoinLensTest.`null BURN and FIRE render em-dash placeholders` — manual: forecast card hidden

1. Nuovo wallet senza tx → tutti i 4 KPI mostrano "—" in grigio.
2. Cash flow forecast card NON appare (empty series).

### Done
- 25.1–25.10 ✓ su Windows.
- `:apps:desktop:jvmTest --tests FinanceKpisTest --tests CashFlowForecastTest` verde (16+15=31 test).
- Suite jvmTest completa verde (602 test, 0 fail).
- Nessun TODO Phase 7.6 residuo nel codice.

---

## 26. Phase 7.7 — Sinking funds + ON TRACK chip (manuale)

Pre-requisiti: device PIN sbloccato, Tracking dashboard aperto.

### 26.1 Crea un sinking goal con deadline futura  `[partial]`

> Parziale: SinkingGoalTest.`daysRemaining positive for future deadline` + `monthlyRate three months equals one third of missing` — note: a fresh goal shows NO chip (`scheduleStatus null when freshly created and untouched`)

1. Add savings goal: name="Vacanza", **Goal type = Sinking fund**, Deadline = 6 mesi nel futuro (es. 2026-11-20), Target type = Absolute, Target = 1.000.000 sat.
2. Atteso: salva OK. Nella card SavingsGoals la nuova riga mostra:
   - Chip "ON TRACK" verde (current=0 ma elapsed=0 al primo render)
   - Sotto la barra: "183 days to 2026-11-20 · ~163.934 sat/mo to stay on track"

### 26.2 Validazione deadline obbligatoria per sinking  `[partial]`

> Parziale: registry::savings_add_sinking_requires_deadline (cargo) — manual: Save disabled + error text

1. Add savings goal: name="X", Goal type = Sinking, lascia Deadline vuoto.
2. Atteso: bottone Save disabilitato. Testo "Invalid date — use YYYY-MM-DD." se l'utente digita qualcosa di malformato.
3. Switcha a Savings: Save si abilita anche senza deadline.

### 26.3 ON TRACK / BEHIND chip  `[auto]`

> Automatizzato: SinkingGoalTest.`scheduleStatus ON_TRACK at the linear expected point` + `scheduleStatus BEHIND when current is well below expected`

1. Crea sinking con deadline 60gg, target 1.000.000 sat. Aspetta 30gg (o adjust system clock).
2. Aggiorna manualmente current=500.000 → chip "ON TRACK" verde.
3. Aggiorna current=200.000 → chip "BEHIND" rosso.
4. Aggiorna current=800.000 → chip "AHEAD" giallo.

### 26.4 OVERDUE quando deadline passa  `[auto]`

> Automatizzato: SinkingGoalTest.`scheduleStatus OVERDUE when deadline passed` + `monthlyRate null when deadline passed`

1. Adjust system clock oltre la deadline.
2. Apri dashboard. Atteso: chip "OVERDUE" rosso. Monthly rate line nasconde la suggestion (rate null).

### 26.5 Savings goal: no chip / no countdown  `[auto]`

> Automatizzato: SinkingGoalTest.`scheduleStatus null when not sinking` + `daysRemaining null when not sinking`

1. Goal type = Savings, salva con qualunque deadline (anche null).
2. Atteso: nessuna chip schedule, nessuna countdown line.

### 26.6 Switch da Savings a Sinking persiste deadline  `[partial]`

> Parziale: registry::savings_update_to_sinking_requires_deadline (cargo) — manual: reopen round-trip

1. Edita un savings goal esistente, switcha a Sinking, setta deadline.
2. Save → riapri → kind e deadline preservati. La chip ON TRACK / BEHIND / AHEAD diventa visibile.

### 26.7 Backward compat con DB pre-7.7  `[partial]`

> Parziale: registry::savings_migration_adds_goal_kind_column_idempotent (cargo) + SinkingGoalTest.`fromWire falls back to SAVINGS for null and unknown` — manual: real old DB

1. Avvia con DB esistente (creato in 7.6 o prima).
2. Goal pre-migrazione devono apparire con `goal_kind="savings"` (default). Niente chip schedule.
3. Edit di un goal pre-migrazione mostra "Goal type" = Savings selezionato.

### Done
- 26.1–26.7 ✓ su Windows.
- `cargo test -p bhodl-wallet-onchain --lib savings_` verde (17 test, +5 Phase 7.7).
- `:apps:desktop:jvmTest --tests SinkingGoalTest` verde (~18 test nuovi).
- Suite jvmTest completa verde (621 test, 0 fail).
- Nessun TODO Phase 7.7 residuo nel codice.

---

## 27. Phase 7.8 — Investment lots + cost basis method (manuale)

Pre-requisiti: device PIN sbloccato, Tracking dashboard aperto, InvestmentsCard visibile.

### 27.1 Legacy single-row investment continua a funzionare  `[partial]`

> Parziale: InvestmentLotsTest.`effectiveCostBasis empty lots falls back to legacy aggregate` — manual: caret hidden

1. Avvia con DB esistente che ha investments creati pre-7.8 (Phase 5.1, single-row con cost_basis_fiat aggregate).
2. Atteso: la card mostra i numeri come prima (quantity / cost / value / P&L invariati). Nessun caret a sinistra del nome perché il record è in modalità legacy.

### 27.2 Expand row con caret
1. Click su un investment row. Atteso: il dialog InvestmentEdit si apre (comportamento legacy).
2. Click sul **caret ▶** a sinistra del nome (visibile se ci sono lots OR se l'investment è in modalità "seed-ready", cioè quantity=0 e cost_basis_fiat=0).
3. Quando expanded, sotto la row appare una sub-sezione indentata con: "No lots yet for this position." + link "+ Add lot".

### 27.3 Add primo lot → seed migration automatica  `[partial]`

> Parziale: registry::investment_seed_lot_from_aggregate_atomic (cargo) — manual: combined totals in UI

1. Su un investment legacy (con cost_basis_fiat > 0), click caret → expanded → "+ Add lot".
2. Compila LotEditDialog: qty=5, price=200, fees=10, date=2024-06-01. Save.
3. Atteso:
   - Registry crea automaticamente un seed lot dall'aggregate (qty=oldQty, price=oldCostBasis/oldQty, fees=0, notes="Seed lot from Phase 5.1 single-row position").
   - Aggiunge il nuovo lot.
   - Investment.quantity e .costBasisFiat vanno a 0 (segnale "modello multi-lot attivo").
   - Row mostra ora **2 lots**, qty totale = oldQty + 5, cost basis totale = (oldCostBasis + 5*200 + 10).
   - Sub-row mostra entrambi i lots con notes/date.

### 27.4 Edit lot persistente  `[partial]`

> Parziale: registry::investment_lots_update_persists_changes (cargo) — manual: row refresh

1. Click su un lot sub-row → LotEditDialog precompilato.
2. Modifica qty da 5 a 7 → Save.
3. Atteso: row aggregata aggiornata, P&L cresce.

### 27.5 Delete lot via dialog  `[partial]`

> Parziale: registry::investment_lots_delete_returns_true_then_false (cargo) — manual: UI recompute

1. Edit un lot → Delete.
2. Atteso: lot scompare dalla sub-row, aggregato si ricalcola.

### 27.6 Cascade delete investment → lots  `[auto]`

> Automatizzato: registry::investment_lots_cascade_delete_on_investment_delete (cargo)

1. Apri InvestmentEdit dialog di un investment con lots → Delete.
2. Atteso: investment row scompare, e tutti i suoi lots sono cascade-deleted dal DB.
3. Verifica via `cargo test investment_lots_cascade_delete_on_investment_delete` (è automatizzato).

### 27.7 Cost basis method picker (UI, Phase 7.8.1)  `[partial]`

> Parziale: registry::investment_cost_basis_method_set_persists (cargo) — manual: chip picker

1. Apri InvestmentEditDialog su un investment esistente.
2. Sotto "Current value per unit", trova la sezione "Cost basis method" con 3 chip: FIFO / HIFO / Specific ID. Default FIFO per nuovi.
3. Click HIFO → Save.
4. Riapri il dialog → HIFO selezionato (persiste su DB via `setInvestmentCostBasisMethod` post-update).
5. Click Specific ID → Save → riapri → Specific ID selezionato.
6. Hint sotto: "Used to order lot consumption on a future sale."
7. Test automatizzato: `cargo test investment_cost_basis_method_set_persists` verde.

### 27.8 Multi-lot P&L aggregato corretto  `[auto]`

> Automatizzato: InvestmentLotsTest.`effectiveCostBasis sums quantity times price plus fees` + `effectiveQuantity sums lot quantities`

1. Investment con 3 lots: (10 @ 100, fees 5), (5 @ 200, fees 3), (3 @ 150, fees 0).
2. Cost basis aggregato = (10*100+5) + (5*200+3) + (3*150) = 1005 + 1003 + 450 = 2458.
3. Quantity aggregata = 18.
4. Atteso: la row "COST" mostra 2458, "QTY" 18. P&L derivato come effective_value − 2458.

### Done
- 27.1–27.8 ✓ su Windows.
- `cargo test -p bhodl-wallet-onchain --lib -- investment_lots investment_seed investment_cost_basis` verde (12 test).
- `:apps:desktop:jvmTest --tests InvestmentLotsTest` verde (12 test).
- Suite jvmTest completa verde (632 test, 0 fail).
- Nessun TODO Phase 7.8 residuo nel codice.
- Phase 7.8.1 (cost-basis-method UI picker) chiusa contestualmente.

---

## 28. Phase 7.9 — Sell flow + P&L realizzato (manuale)

Pre-requisiti: device PIN sbloccato, almeno un investment con ≥1 lot.

### 28.1 Sell FIFO consuma il lot più vecchio  `[auto]`

> Automatizzato: registry::sells_record_consumes_lots_in_fifo_order (cargo) + InvestmentLotsTest.`previewSellPnl mirrors backend FIFO with lot and sale fees`

1. Investment "AAPL" con 3 lots: (10 @ €100 il 2024-01-01), (5 @ €200 il 2024-06-01), (3 @ €150 il 2024-12-01). Cost basis method = FIFO (default).
2. Espandi la row → "− Sell" link visibile in basso accanto a "+ Add lot".
3. Click Sell → SellDialog. Available = 18.
4. Compila: qty=12, sale_price=250, fees=0, date=oggi. Save.
5. Atteso:
   - SellDialog mostrava "Realized P&L preview: +€1.600 (FIFO)" prima del save.
   - Dopo save: la sub-sezione "Sells" appare con 1 row: "12 @ €250 · oggi · +€1.600".
   - Il lot da 10 viene completamente consumato (qty → 0); il lot da 5 perde 2 unità (qty → 3); il lot da 3 resta intatto.

### 28.2 Sell HIFO consuma prima il lot più caro  `[auto]`

> Automatizzato: registry::sells_record_consumes_lots_in_hifo_order (cargo)

1. Cambia cost basis method dell'investment a HIFO via InvestmentEditDialog. Save.
2. Crea nuovo sell qty=4, price=250.
3. Atteso: il lot da 5@€200 viene consumato 4 unità (gain (250-200)*4 = €200), gli altri restano intatti.

### 28.3 Sell parziale lascia residuo sul lot  `[auto]`

> Automatizzato: registry::sells_partial_consumption_leaves_residual (cargo)

1. Investment con lot 10 @ €100. Sell di 3 unità @ €150.
2. Atteso: lot row mostra qty=7. Subsezione "Sells" mostra "+€150" come gain (3 × 50).

### 28.4 Over-sell rifiutato  `[partial]`

> Parziale: registry::sells_reject_over_sell (cargo) + InvestmentLotsTest.`previewSellPnl rejects invalid inputs` — manual: red text, Save disabled

1. Investment con lot 5 @ €100. Available = 5.
2. SellDialog qty=10 → testo rosso "Over-sell: only 5.00 units available." + Save disabilitato.

### 28.5 Fee allocata pro-rata  `[auto]`

> Automatizzato: registry::disposals_gain_loss_includes_pro_rata_fee (cargo)

1. Lots: (10 @ €100), (5 @ €200). Sell qty=12 @ €250 con fees=€60.
2. Atteso disposals: lot1 → gain = (250-100)*10 - 60*(10/12) = 1500 - 50 = €1.450. lot2 → gain = (250-200)*2 - 60*(2/12) = 100 - 10 = €90. Verifica via `cargo test disposals_gain_loss_includes_pro_rata_fee` (automatizzato).

### 28.6 Delete sell ripristina i lotti  `[auto]`

> Automatizzato: registry::sells_cascade_delete_disposals_on_sell_delete + delete_sell_restores_lot_quantities_atomically (cargo) — note: lot qty is now RESTORED

1. Sell registrato. Click sulla row del sell nella sub-sezione "Sells".
2. Atteso: il sell e le sue disposal scompaiono e ogni lotto consumato torna alla quantità precedente al sell (M3, una sola transazione). `original_quantity` non cambia mai. Vale anche per i sell Specific-ID (48.6).

### 28.7 P&L preview nel dialog  `[partial]`

> Parziale: InvestmentLotsTest.`previewSellPnl mirrors backend FIFO with lot and sale fees` — manual: footer label

1. Apri SellDialog su un investment con cost_basis_method = HIFO.
2. Compila qty/price → footer mostra "Realized P&L preview: +€XYZ (HIFO)".
3. Cambia il method via InvestmentEditDialog (FIFO/HIFO/Specific-ID), riapri il sell dialog → label tra parentesi cambia.
4. Con Specific-ID il campo quantità è sostituito dal picker dei lotti (vedi 48.4–48.6); la preview usa i lotti scelti.

### 28.8 InvestmentEditDialog rispetta cascade delete  `[auto]`

> Automatizzato: registry::sells_cascade_delete_on_investment_delete (cargo)

1. Investment con lots + sells + disposals.
2. InvestmentEdit → Delete.
3. Atteso: tutti i lots, sells, disposals cancellati. Verifica via `cargo test sells_cascade_delete_on_investment_delete` (automatizzato).

### Done
- 28.1–28.8 ✓ su Windows.
- `cargo test -p bhodl-wallet-onchain --lib -- sells_ disposals_` verde (10 test).
- `:apps:desktop:jvmTest --tests InvestmentLotsTest` verde (20 test, +8 Phase 7.9 P&L helpers).
- Suite jvmTest completa verde (641 test, 0 fail).
- Nessun TODO Phase 7.9 residuo nel codice.

---

## 29. Phase 7.10 — Crypto event taxonomy (manuale)

Pre-requisiti: device PIN sbloccato, History tab aperto con almeno qualche transazione visibile, almeno 2 watch wallet registrati nel registry (per il test 29.2).

### 29.1 Classifica un INCOMING come airdrop  `[partial]`

> Parziale: registry::crypto_event_kind_set_then_get (cargo) — manual: chip/badge UI

1. History tab → trova un INCOMING tx → tap → CategoryEditDialog si apre.
2. Sotto la sezione "Crypto event kind", clicca chip "Airdrop". Il chip diventa arancione.
3. Save (o Cancel — il kind viene committato istantaneamente al tap, indipendente dal Save della category).
4. Atteso: la row tx ora mostra il badge "AIRDROP" arancione accanto al category chip.

### 29.2 Auto-detect send_to_self per transfer interno  `[auto]`

> Automatizzato: SendToSelfDetectorTest.`transfer whose only external flow is the fee is internal` + registry::crypto_event_kind_per_wallet_isolation (cargo)

1. Trasferisci 0.001 BTC dal wallet hot al wallet cold (entrambi watch nel registry).
2. Sync entrambi i wallet via "Refresh balances".
3. Atteso: dopo il refresh, sia l'evento OUTGOING (hot) sia l'INCOMING (cold) mostrano badge "INTERNAL" grigio.
4. Verifica: `cargo test crypto_event_kind_per_wallet_isolation` (automatizzato) + `crypto_event_kind_dump_returns_all_rows`.

### 29.3 Override manuale resta dopo refresh  `[auto]`

> Automatizzato: SendToSelfDetectorTest.`a stored classification is never overwritten` + registry::crypto_event_kind_auto_writes_and_clears_only_unpinned_rows (cargo)

1. Su un tx auto-classificato `send_to_self`, riapri il CategoryEditDialog → seleziona "Swap" (viola).
2. Save. Atteso: badge "SWAP" viola appare. Refresh balances: il badge resta SWAP (auto-detect non sovrascrive override esplicito).

### 29.4 Reset a regular cancella la row  `[partial]`

> Parziale: registry::crypto_event_kind_user_regular_is_persisted_and_pinned (cargo) — note: "regular" is now stored + pinned, not deleted

1. Su un tx classificato (es. "AIRDROP"), riapri dialog → click "Regular".
2. Atteso: badge scompare dalla row. `cargo test crypto_event_kind_set_regular_clears_row` (automatizzato).

### 29.5 Tooltip warning sui gifts
1. Apri dialog → click "Gift received" o "Gift sent".
2. Atteso: footer "Tax treatment varies by jurisdiction — consult your advisor." appare sotto i chip.

### 29.6 9 chip visibili + accessibili  `[partial]`

> Parziale: registry::crypto_event_kind_accepts_each_whitelisted_value (cargo) — manual: 9 chips

1. Apri dialog su qualsiasi tx → sezione "Crypto event kind" mostra 9 chip in 2 righe:
   - Riga 1 (Income family): Regular · Airdrop · Staking reward · Lending interest · Mining reward
   - Riga 2 (Capital + transfers + gifts): Swap · Send to self (internal) · Gift received · Gift sent
2. Ogni chip ha test tag accessibile (`crypto-event-kind.chip.<wire>`).

### 29.7 PaymentEvent FFI espone il kind
1. Tramite `cargo test list_watch_events_includes_crypto_kind` (automatizzato): un evento con kind=airdrop nel registry produce `PaymentEvent.crypto_event_kind = "airdrop"` quando letto via FFI.
2. Il TrackerCache popola automaticamente il campo dopo refresh.

### Done
- 29.1–29.7 ✓ su Windows.
- `cargo test -p bhodl-wallet-onchain --lib -- crypto_event_kind` verde (10 test).
- Suite jvmTest completa verde (641 test, 0 fail).
- Nessun TODO Phase 7.10 residuo nel codice.

---

## 30. Phase 8.1 — BTC rate history repository (manuale)

> Tutti i test di questa sezione richiedono un DB registry aperto
> con PIN valido. Usa la directory dati di test (`~/.bhodl-test/`
> oppure una tmpdir) + un PIN arbitrario come `test1234`.
> I test 30.6 e 30.7 richiedono disconnessione di rete.

| #    | Test                                                                                                     | Passi                                                                                                                                                                             | Atteso                                                                                                                            | Pass |
|------|----------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|------|
| 30.1 | Fresh DB — query rate di 30 giorni fa EUR → CoinGecko path                                              | 1. Apri DB di test (empty). 2. Chiama `btc_rate_at(data_dir, pin, <oggi-30g a mezzanotte UTC>, "EUR")`. 3. Osserva log HTTP.                                                      | Valore plausibile (BTC/EUR attuale ±30%). Log mostra una call a CoinGecko `/coins/bitcoin/history`. Tabella `btc_rate_history` ha 1 row. | [partial] btc_rate_history::online::rate_at_known_dates_matches_expected_within_tolerance (cargo, online-tests) — manual: CoinGecko path |
| 30.2 | Cache hit immediato — stessa query (30.1) non fa network call                                            | 1. Chiama di nuovo `btc_rate_at` con gli stessi parametri. 2. Osserva log HTTP.                                                                                                   | Stesso valore, NESSUNA call HTTP. Tabella ha ancora 1 row (INSERT OR IGNORE).                                                     | [auto] btc_rate_history::rate_at_caches_after_first_fetch_via_seed (cargo) |
| 30.3 | Data >365d (2015-06-01 USD) → blockchain.info path                                                       | 1. Chiama `btc_rate_at(data_dir, pin, 1433116800, "USD")`.                                                                                                                        | Valore ≈ 220–240 USD. Log mostra call a `api.blockchain.info`. Source in DB = `blockchain-info`.                                  | [partial] btc_rate_history::online::rate_at_known_dates_matches_expected_within_tolerance (cargo, online-tests) |
| 30.4 | Data >365d non-USD (2015-06-01 EUR) → triangulation                                                     | 1. Chiama `btc_rate_at(data_dir, pin, 1433116800, "EUR")`.                                                                                                                        | Valore ≈ btc_usd × usd_eur del giorno. Log mostra call blockchain.info + Frankfurter. Source in DB = `ecb-triangulated`.         | [partial] btc_rate_history::online::triangulated_eur_within_5pct_of_coingecko_eur_when_both_available (cargo, online-tests) |
| 30.5 | Range degli ultimi 90 giorni EUR → CoinGecko market_chart/range (una sola call)                         | 1. Chiama `btc_rate_range(data_dir, pin, <oggi-90g>, <oggi-1g>, "EUR")`.                                                                                                          | Ritorna ≥ 85 punti giornalieri ordinati ASC. Ogni punto ha `date_unix` a mezzanotte UTC e `rate` > 0.                             | [partial] btc_rate_history::range_returns_sorted_ascending (cargo) — manual: ≥90 points live |
| 30.5b | Range che parte >365g fa (es. 2024-01-01 → oggi-1g) NON chiama CoinGecko per i giorni vecchi            | 1. Chiama `btc_rate_range` con `from` = 1704067200. 2. Osserva log HTTP.                                                                                                          | Nessuna call a CoinGecko con `from` >365g (l'API keyless risponde 401): i giorni vecchi vanno giorno per giorno via blockchain.info/Frankfurter, solo gli ultimi 365g via `market_chart/range`. | [auto] btc_rate_history::missing_days_older_than_the_keyless_window_skip_coingecko (cargo) |
| 30.6 | Offline — cache hit (data già in cache da 30.1)                                                          | 1. Disconnetti rete. 2. Chiama `btc_rate_at(data_dir, pin, 1713484800, "EUR")`.                                                                                                   | Valore restituito (cache). Nessun errore di rete.                                                                                 | [auto] btc_rate_history::rate_at_caches_after_first_fetch_via_seed (cargo) |
| 30.7 | Offline — nuova data non in cache → `RateError::AllSourcesFailed`                                        | 1. Rete ancora disconnessa. 2. Chiama `btc_rate_at(data_dir, pin, 1709251200, "USD")` (2024-03-01, non in cache).                                                                 | Errore `WalletError::Storage` con messaggio che contiene `all sources failed` o simile.                                           | [partial] BtcRateHistoryClientErrorHardeningTest.`rateAt with typed RateAllSourcesFailed also returns null (soft-fail)` — manual: real offline path |
| 30.8 | Backfill 50 date miste 2022-2024 EUR → completa in <60s, report corretto                                 | 1. Riconnetti rete. 2. Costruisci 50 `RateBackfillRequestFfi` (date mensili 2022-01-01 → 2024-02-01). 3. Chiama `btc_rate_backfill(data_dir, pin, requests)`. 4. Misura il tempo. | `report.requested = 50`. `report.failed` vuoto o quasi. Tempo < 60s. Log non mostra errori di rate-limit (token bucket funziona). | [partial] btc_rate_history::backfill_dedupes_identical_requests + rate_limiter_tokens_decrement (cargo) — manual: 50 live dates timing |

### Verifiche automatizzate

```
cargo test -p bhodl-wallet-onchain --lib -- btc_rate_history
```

Atteso: test verdi (offline, nessuna call HTTP).

> **CoinGecko keyless (verificato 2026-10-10)**: sia `/coins/bitcoin/history`
> sia `/coins/bitcoin/market_chart/range` rispondono **HTTP 401** (error
> 10012) per date più vecchie di 365 giorni (365g fa → 200, 366g fa → 401).
> BHODL non interroga mai CoinGecko oltre quella finestra: >365g usa solo
> blockchain.info (+ Frankfurter), così nessuna richiesta né budget di
> rate-limit viene bruciato su un fallimento garantito. Il vecchio fallback
> `coingecko-range` per le date >365g è stato rimosso (le righe di cache già
> salvate con source `coingecko-range` / `coingecko-history` restano valide).

### Done

- 30.1–30.8 ✓ su Windows.
- `cargo test -p bhodl-wallet-onchain --lib -- btc_rate_history` verde (11 test).
- FFI compila: `cargo build -p bhodl-uniffi` senza errori.
- Nessun TODO Phase 8.1 residuo nel codice.

---

## 32. Phase 8.2 — BTC-at-time backfill per tracker tables (manuale)

> §29 e §31 sono riservati per fasi precedenti. Il presente §32 documenta la migrazione
> che aggiunge le colonne BTC-at-time ai 4 tracker tables e il backfill retroattivo.
>
> Pre-condizioni comuni: DB registry aperto con PIN valido, almeno qualche lot / sell /
> disposal / manual asset già presenti (inseriti nelle fasi 7.4–7.9).
> I test 32.5 e 32.6 richiedono connessione di rete attiva.

| #    | Test                                                                                                      | Passi                                                                                                                                                                                                                              | Atteso                                                                                                                                                                                                              | Pass |
|------|-----------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 32.1 | Schema migration — colonne aggiunte idempotentemente                                                      | 1. Apri DB di test esistente (da Phase 7.x, senza colonne 8.2). 2. Chiama `phase_8_2_backfill_btc_at_time(data_dir, pin)`. 3. Ispeziona schema con `.schema tracking_investment_lots`.                                             | Colonna `btc_per_unit_at_purchase` presente in `tracking_investment_lots`. Analogamente per le 6 colonne su tutti e 4 i table. Chiamata successiva non dà errori ("duplicate column name" swallowed).               | [auto] btc_at_time_backfill::migration_adds_btc_at_purchase_column_idempotent + migration_adds_4_manual_asset_columns_idempotent (cargo) |
| 32.2 | Backfill lots e sells — colonne populate                                                                  | 1. Inserisci un lot con `purchase_unix = 1713484800` (2024-04-19, BTC halving) in USD. 2. Inserisci un sell con `sale_unix = 1605225600` (2020-11-13) in EUR. 3. Esegui backfill. 4. Leggi le colonne via `list_investment_lots`.   | `btc_per_unit_at_purchase ≈ 63 800 USD`. `btc_per_unit_at_sale ≈ 13 800 EUR`. Entrambe le colonne non-NULL. `report.investment_lots.fetched = 1`, `report.investment_sells.fetched = 1`.                             | [auto] btc_at_time_backfill::backfill_lots_with_known_rate_populates_btc_at_purchase (cargo) |
| 32.3 | Backfill disposals — gain_loss_btc calcolato                                                              | 1. A partire dal lot e sell di 32.2, inserisci un disposal che consuma 3 unità con `cost_basis_per_unit = 150`, `sale_price_per_unit = 200`. 2. Esegui backfill. 3. Leggi `gain_loss_btc` via `list_all_lot_disposals`.             | `gain_loss_btc ≈ −0.000833 BTC` (valore negativo: hai speso più BTC all'acquisto di quanti ne hai ricevuti alla vendita, nonostante gain_loss_fiat = +150). `report.lot_disposals.fetched = 1`.                      | [auto] btc_at_time_backfill::backfill_disposals_derives_gain_loss_btc_from_parent_snapshots (cargo) |
| 32.4 | Legacy fallback manual asset — purchase_unix derivato da created_at_unix                                  | 1. Inserisci un manual asset senza compilare i campi purchase_* (legacy row). 2. Esegui backfill. 3. Leggi i 4 campi `purchase_*` via `list_manual_assets`.                                                                         | `purchase_unix = created_at_unix` del record. `purchase_value_fiat = value_fiat`. `purchase_currency = currency`. `purchase_btc_at_time` non-NULL (tasso del giorno di created_at_unix). Report: `manual_assets.fetched = 1`. | [auto] btc_at_time_backfill::backfill_manual_asset_legacy_falls_back_to_created_at_unix (cargo) |
| 32.5 | Idempotenza — seconda chiamata non ri-backfilla                                                           | 1. Esegui backfill una seconda volta sullo stesso DB. 2. Controlla i contatori nel report.                                                                                                                                          | `considered = 0` per tutti i table. `already_done` = numero di righe già backfillate al run precedente. Nessuna call HTTP aggiuntiva.                                                                                | [auto] btc_at_time_backfill::backfill_idempotent_across_runs (cargo) |
| 32.6 | Rete offline — rows con data sconosciuta lasciate NULL                                                    | 1. Disconnetti rete. 2. Inserisci un lot con `purchase_unix` futuro o fuori range. 3. Esegui backfill offline. 4. Controlla report e colonna.                                                                                       | `btc_per_unit_at_purchase = NULL`. `report.investment_lots.failed = 1`. Nessun errore propagato (soft fail). Riga è ritentatile al prossimo run.                                                                      | [auto] btc_at_time_backfill::backfill_lots_with_unrecoverable_rate_leaves_null_and_reports_failed (cargo) |

### Verifiche automatizzate

```
cargo test -p bhodl-wallet-onchain --lib btc_at_time
```

Atteso: 11 test verdi (offline, nessuna call HTTP, mock BtcRateHistoryRepository).

```
cargo build -p bhodl-uniffi
```

Atteso: compilazione senza errori. I 4 DTO FFI (`TrackingInvestmentLotFfi`,
`TrackingInvestmentSellFfi`, `TrackingLotDisposalFfi`, `TrackingManualAssetFfi`)
espongono i nuovi campi nullable. Nuova funzione `phase_8_2_backfill_btc_at_time`
visibile nel bindings generato.

### Done

- 32.1–32.6 ✓ su Windows.
- `cargo test -p bhodl-wallet-onchain --lib btc_at_time` verde (11 test).
- `cargo build -p bhodl-uniffi` senza errori.
- Nessun TODO Phase 8.2 residuo nel codice.

## 33. Phase 8.3.1 — `<Money>` composable + Bitcoin Lens (manuale)

> §33 documenta la prima sotto-fase di 8.3: fondazioni Kotlin (`<Money>` composable +
> domain types + FFI wrapper + BackfillRunner) e i nuovi controlli Settings advanced
> "Bitcoin Lens". In questa fase NESSUN call site esistente è migrato — l'app non cambia
> visivamente fuori dalla nuova sezione Settings. La migrazione view-by-view delle hero
> / card di tracking parte in 8.3.2.

Pre-condizioni comuni: DB registry aperto con PIN valido. Phase 8.2 chiusa (le tabelle
hanno le colonne `btc_*_at_*`). PriceCache idratata (alme­no una currency selezionata
in Settings, anche se i test 33.4-33.5 non dipendono dal price live).

| #    | Test                                                                                  | Passi                                                                                                                                                                                                                                              | Atteso                                                                                                                                                                                                                       | Pass |
|------|---------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 33.1 | Settings — sezione "Bitcoin Lens (advanced)" presente con default                     | 1. Apri Settings. 2. Scorri fino a "Bitcoin Lens (advanced)". 3. Osserva i 5 controlli.                                                                                                                                                            | Sezione visibile sotto "Privacy". Unit format = "Auto" selezionato. Tutti e 4 i toggle "Fiat-visible operational surfaces" sono ON. Sotto i toggle riga "Outside these surfaces, all amounts display in BTC/sat."             | [partial] SettingsStoreTest.`default unit format is always sats` + `default Bitcoin Lens fiat surfaces are all ON` — manual: Settings section UI |
| 33.2 | Settings — cambia Unit format ad "Always sat", persiste cross-restart                  | 1. Tappa la chip "Sat" sotto Unit format. 2. La descrizione testuale sotto le chip cambia in "Always sat — e.g. 1,234,567 sat." 3. Chiudi e riapri l'app. 4. Torna in Settings.                                                                    | Chip "Sat" ancora selezionata dopo il restart. `settings.json` su disco contiene `"unitFormat":"ALWAYS_SAT"`.                                                                                                                  | [partial] SettingsPersistenceTest.`save then rehydrate restores the 5 Bitcoin Lens preferences` — manual: chip UI, restart |
| 33.3 | Settings — disattiva "Bank CSV import rows", persiste cross-restart                    | 1. Spegni il Switch su "Bank CSV import rows". 2. Chiudi e riapri l'app. 3. Torna in Settings.                                                                                                                                                      | Switch ancora off dopo il restart. `settings.json` contiene `"showFiatInBankCsv":false`. Gli altri 3 toggle restano on (indipendenza fra i 4 slot).                                                                            | [partial] SettingsStoreTest.`Bitcoin Lens fiat surface toggles flip independently` — manual: switch UI, restart |
| 33.4 | Primo lancio dopo Phase 8.2 — BackfillRunner gira senza freezare la UI                 | 1. Pulisci registry (o avvia su DB con righe Phase 7.x non-backfillate). 2. Sblocca col device PIN. 3. Osserva la TrackerHome che parte. 4. Ispeziona log (opzionale).                                                                            | UI risponde immediatamente, nessun freeze percepibile. Il `BackfillRunner.state` transita Idle → Running → Done (verificabile via log o future debug surface). Sul DB le colonne `btc_*_at_*` ora popolate.                    | [partial] BackfillRunnerTest.`runIfNeeded transitions Idle to Done with a happy-path target` — manual: UI freeze, real DB |
| 33.5 | Lancio successivo — BackfillRunner idempotente                                          | 1. Chiudi e riapri l'app sullo stesso DB. 2. Sblocca col device PIN.                                                                                                                                                                                | BackfillRunner gira di nuovo ma `report.X.already_done == considered` per tutti e 4 i table. Nessuna call HTTP aggiuntiva (Phase 8.1 cache hit). Stato Done in pochi millisecondi.                                              | [auto] BackfillRunnerTest.`runIfNeeded is idempotent - already_done equals considered on the second run` |
| 33.6 | (Smoke) `<Money>` composable testato via render-tree assertions                         | Vedi `MoneyRenderTest.kt` in `apps/desktop/src/jvmTest`. Sette scenari coperti: Medium dual-line, Compact single-line, Hero 56sp, Pnl positive ("+" sign), Pnl negative ("-" sign + financialNegative color), MoneyPrimary.Fiat invertito, rate mancante → "—". | Tutti i 7 test verdi via `./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.ui.money.MoneyRenderTest"`. Da ricontrollare a vista in 8.3.2 quando il composable inizia a guidare la NetWorthHero.                   | [auto] MoneyRenderTest (7 tests) |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.ui.money.*"
```

Atteso: 15 test verdi (MoneyValueTest 4 + UnitFormatTest 4 + MoneyRenderTest 7).

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.prices.BtcRateHistoryClientTest"
```

Atteso: 4 test verdi (rate happy path + currency-not-supported → IAE + network-unavailable → null + range sorted).

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.data.BackfillRunnerTest"
```

Atteso: 4 test verdi (Idle→Done, idempotente, Idle→Failed, null-pin no-op).

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.settings.*"
```

Atteso: 33 SettingsStoreTest + 24 SettingsPersistenceTest tutti verdi, incluso il
round-trip per i 5 nuovi slot Bitcoin Lens.

### Done

- 33.1–33.6 ✓ su Windows.
- 31 nuovi test Kotlin verdi (di cui 16 strettamente Phase 8.3.1 + 15 di copertura
  estesa sulle render assertions e i round-trip di persistence).
- Nessun call site esistente migrato a `<Money>` (per design — 8.3.2 in poi).
- `SettingsScreen` mostra la sezione "Bitcoin Lens (advanced)" con i 5 controlli.
- BackfillRunner si esegue al primo lancio post-8.2 (verificato a mano + via test idempotence).
- No TODO Phase 8.3.1 residuo.

## 34. Phase 8.3.2 — `NetWorthHero` Bitcoin Lens migration (manuale)

> §34 chiude la migrazione del primo componente al display layer Bitcoin Lens:
> BTC primary fissato, sparkline sat-on-y, chip "EUR ↓ vs BTC" assertivo,
> fiat overlay dashed toggleabile. La tap-cycle legacy (BTC→SAT→FIAT→HIDDEN)
> è rimossa — solo `<Money>` decide la formattazione, il long-press resta per
> il privacy reveal.

Pre-condizioni comuni: TrackerHome attivo con almeno un wallet pairato, una
fiat currency selezionata (EUR consigliato), PriceCache idratata, history
non-vuota sul periodo selezionato (90D consigliato per il test 34.4).

| #    | Test                                                                                  | Passi                                                                                                                                                                                                                                              | Atteso                                                                                                                                                                                                                       | Pass |
|------|---------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 34.1 | Hero in BTC-primary, fiat secondary                                                   | 1. Apri TrackerHome. 2. Osserva l'hero in alto.                                                                                                                                                                                                    | Linea grande "X BTC" (o "X sat" se NW < 100k sat con Unit format Auto). Sotto, riga 16sp "€Y" (secondary fiat).                                                                                                                | [auto] NetWorthHeroBitcoinLensTest.`hero renders primary BTC line and secondary fiat line` |
| 34.2 | Tap sull'hero NON cycla i modi                                                        | 1. Con privacy auto-hide OFF, tappa l'hero 3-4 volte.                                                                                                                                                                                              | Niente cambia. L'hero resta in BTC-primary. La tap-cycle legacy è rimossa.                                                                                                                                                    | [auto] NetWorthHeroBitcoinLensTest.`tap on the hero does NOT cycle units (no AmountDisplayMode cycler)` |
| 34.3 | Privacy auto-hide ON + long-press reveal                                              | 1. Abilita Settings → Privacy → auto-hide. 2. Torna in TrackerHome. 3. Osserva l'hero. 4. Tieni premuto sull'hero ~600ms. 5. Rilascia.                                                                                                              | Boot: hero mostra bullet silhouette + riga "Hold to reveal". Mentre tieni premuto: amount visibile via `<Money>`. Al rilascio: torna ai bullet.                                                                                | [partial] NetWorthHeroBitcoinLensTest.`privacy mode on shows Hold to reveal hint` — manual: long-press reveal |
| 34.4 | Chip "EUR ↓ X% vs BTC" su periodo 90D                                                  | 1. Seleziona 90g nel selettore di periodo globale (unico, sopra la sezione — B12). 2. Osserva la delta row.                                                                                                                                                                                    | Chip visibile, formato "EUR ↓ X.X% vs BTC" con sfondo muted-negative quando BTC è salito vs EUR sul periodo. Se BTC è sceso, chip "EUR ↑ X.X% vs BTC" con sfondo muted-positive.                                              | [auto] NetWorthHeroBitcoinLensTest.`chip 'EUR vs BTC' renders when fiat history present and rate end greater` + `chip arrow flips up when rate end is less than rate start` |
| 34.5 | Toggle "Show fiat overlay on net-worth chart" off → linea dashed scompare              | 1. Apri Settings → Bitcoin Lens (advanced). 2. Spegni il toggle "Show fiat overlay on net-worth chart". 3. Torna in TrackerHome. 4. Osserva la sparkline. 5. Riaccendi il toggle.                                                                  | Off: sparkline mostra solo la linea piatta sat-on-y (primary). On: ricompare la linea tratteggiata fiat sopra/sotto la primaria, in tono testTertiary 55% alpha.                                                                | [partial] NetWorthHeroBitcoinLensTest.`sparkline renders when overlay toggle is off` — manual: dashed line absent |
| 34.6 | Settings persiste `showFiatOverlayOnNwChart` cross-restart                            | 1. Spegni il toggle. 2. Chiudi e riapri l'app. 3. Apri Settings → Bitcoin Lens (advanced).                                                                                                                                                          | Toggle ancora off dopo il restart. `settings.json` su disco contiene `"showFiatOverlayOnNwChart":false`.                                                                                                                       | [ ]  |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.NetWorthHeroBitcoinLensTest" --tests "io.bhodl.desktop.screens.FiatVsBtcChangeTest" --tests "io.bhodl.desktop.ui.SparklineOverlayTest"
```

Atteso: ~25 test verdi (FiatVsBtcChange 8 + NetWorthHeroBitcoinLens 11 + SparklineOverlay 7).

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.settings.SettingsPersistenceTest"
```

Atteso: round-trip per `showFiatOverlayOnNwChart` incluso fra i Bitcoin Lens preference test.

### Done

- 34.1–34.6 ✓ su Windows.
- Tap-cycle legacy rimossa dal NetWorthHero (commento esplicito nel codice).
- Chip "EUR ↓/↑ vs BTC" visibile su periodo 90D quando la history ha movement.
- Sparkline primary in sat-on-y (piatta), overlay fiat dashed in tono testTertiary.
- `SettingsScreen` mostra il 6° toggle "Show fiat overlay on net-worth chart".
- No TODO Phase 8.3.2 residuo.

---

## 35. Phase 8.3.3 — `InvestmentsCard` dual P&L migration (manuale)

> §35 estende il Bitcoin Lens a `InvestmentsCard`. La tabella passa
> da 6 a 7 colonne con una nuova `P&L BTC` accanto a `P&L EUR`. Le
> celle COST e VALUE diventano dual-line (BTC primary + fiat secondary
> via `<Money>` Medium). La storia centrale del prodotto — "asset
> sale in EUR ma scende in BTC" — emerge dal chip ⚠ `↓ vs ₿` (o
> `↑ vs ₿` nel caso raro inverso) quando i segni divergono.

Pre-condizioni comuni: TrackerHome attivo con almeno un investimento
EUR-denominato che abbia lot(s) backfilled dalla Phase 8.2
(`tracking_investment_lots.btc_per_unit_at_purchase` non-null), fiat
currency = EUR su dashboard, PriceCache idratata (`PriceCache.price`
non-null).

| #    | Test                                                                                  | Passi                                                                                                                                                                                                                                              | Atteso                                                                                                                                                                                                                       | Pass |
|------|---------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 35.1 | InvestmentsCard EUR con lots backfilled mostra dual-line COST e VALUE + dual P&L      | 1. Apri TrackerHome / Tracking. 2. Osserva un investimento EUR con almeno un lot Phase 8.2.                                                                                                                                                       | Header: 7 colonne (NAME / TYPE / QTY / COST / VALUE / P&L EUR / P&L BTC). Riga dati: COST e VALUE stacked (linea grande BTC, riga sotto in €). Entrambe `P&L EUR` e `P&L BTC` popolate con %, colore by sign.               | [auto] InvestmentsCardBitcoinLensTest.`header row shows 7 columns including P_AND_L BTC` + `data row dual-line cost shows BTC primary and fiat secondary` |
| 35.2 | Investimento con P&L divergente (EUR +X% / BTC −Y%) → chip ⚠ visibile                  | 1. Identifica un investimento con prezzo corrente più alto del cost basis in EUR ma con BTC apprezzato più dell'asset. 2. Osserva la cella P&L BTC. 3. Osserva la totals row.                                                                       | Cella P&L BTC: testo "↓ Y%" + chip `↓ vs ₿` muted-amber accanto. Totals row: stessa chip quando l'aggregato è divergente.                                                                                                    | [auto] InvestmentsCardBitcoinLensTest.`divergent signs fiat positive btc negative renders warning chip` + `non-divergent both positive does NOT render the warning chip` |
| 35.3 | Investment legacy (pre-7.8, no lots) → P&L BTC = "—" senza chip                       | 1. Identifica un investimento single-row legacy (zero lots). 2. Osserva la cella P&L BTC.                                                                                                                                                          | Cella P&L BTC: solo em-dash "—". Niente chip ⚠ rendered.                                                                                                                                                                      | [auto] InvestmentsCardBitcoinLensTest.`legacy investment without lots renders pnlBtc as em-dash` |
| 35.4 | Investment in USD (currency != dashboard EUR) → P&L BTC = "—" + niente chip            | 1. Aggiungi un investimento `costBasisCurrency = USD` con lots backfilled. 2. Osserva la cella P&L BTC.                                                                                                                                            | P&L BTC: em-dash "—" (PriceCache espone solo BTC/EUR, non BTC/USD). Niente chip. La row contribuisce comunque al suo P&L EUR (ma resta esclusa dai totali EUR-aggregati). [Una phase futura wirerà BtcRateHistoryClient.current(USD)] | [partial] InvestmentsCardBitcoinLensTest.`null pnlBtc renders em-dash without chip` — superseded by §42 rateFor |
| 35.5 | Expansion lot row: secondary `≈ ₿0.0XXXX at purchase`                                  | 1. Espandi un investimento con lots backfilled (tap sulla freccia). 2. Osserva la sub-row del lot.                                                                                                                                                 | Linea principale invariata (`qty @ price`, data, fee). Sotto, indentata 4dp, linea grigia "  ≈ ₿0.0XXXX at purchase" (oppure "X sat at purchase" se sub-100k). Quando il lot non ha rate (legacy), la linea è silenziosamente omessa. | [auto] InvestmentsCardBitcoinLensTest.`lot row shows BTC at purchase secondary when backfilled` |
| 35.6 | Expansion sell row: dual signed P&L `+$150 · −₿0.00083`                                | 1. Espandi un investimento con almeno una sell Phase 8.2-backfilled. 2. Osserva la sub-row sotto "Sells".                                                                                                                                          | Cella P&L finale: dual line `+50 € · −₿0.0005` (signed fiat + signed BTC) con colore by-sign sul fiat. Se la sell non ha BTC rate (qualsiasi disposal con `gainLossBtc = null`), fallback a solo fiat senza il "·". | [auto] InvestmentsCardBitcoinLensTest.`sell row shows dual signed pnl when btc backfilled` |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.InvestmentLotsBtcPnlTest" --tests "io.bhodl.desktop.screens.InvestmentsCardBitcoinLensTest"
```

Atteso: 22 nuovi test verdi (InvestmentLotsBtcPnlTest 12 + InvestmentsCardBitcoinLensTest 10).

### Done

- 35.1–35.6 ✓ su Windows.
- Helper puri `realizedPnlBtc`, `unrealizedPnlBtc`, `totalPnlBtc` in `InvestmentLots.kt` con policy "any NULL → NULL" per `realizedPnlBtc`/`unrealizedPnlBtc`, permissiva per `totalPnlBtc`.
- `InvestmentsCard` header 7 colonne, data row 44dp dual-line COST/VALUE + 7° colonna `P&L BTC` con chip divergenza ⚠.
- Totals row ~60dp stacked BTC sopra fiat su COST/VALUE.
- Lots expansion: secondary line `≈ ₿0.0XXXX at purchase` quando il rate è backfilled.
- Sells expansion: dual signed P&L `+€X · −₿0.0XX` quando entrambi i lati sono disponibili.
- No TODO Phase 8.3.3 residuo.

---

## 36. Phase 8.3.4 — `ManualAssets` + `Goals` + `KPI` + `Recurring` migration (manuale)

> §36 chiude la migrazione Bitcoin Lens delle 4 card residue sotto Phase
> 8.3. La carta sostanziale è `ManualAssetsCard`: passa da 4 colonne
> (NAME / TYPE / VALUE / ≈ SAT) a 5 colonne (NAME / TYPE / VALUE NOW /
> VALUE AT BUY / Δ BTC), con celle dual-line e una nuova colonna `Δ BTC`
> che racconta lo storyboard "auto/casa che perdono in BTC anche se
> guadagnano in EUR" (cfr. Phase 7.10 storyboard #4).
>
> Le altre 3 card (`SavingsGoalsCard`, `KpiStripV7`, `RecurringCard`)
> sono passate cosmetiche al pattern `<Money>` consolidato in 8.3.1+:
> stesso comportamento UX, formattazione canonica per BTC/fiat. In più
> `KpiStripV7` aggiunge un 5° slot KPI `OPP COST` come placeholder (il
> compute vero arriva in Phase 8.5, vs HODL).

Pre-condizioni comuni: TrackerHome attivo, fiat currency = EUR su
dashboard, PriceCache idratata (`PriceCache.price` non-null).

| #    | Test                                                                                | Passi                                                                                                                                                                                              | Atteso                                                                                                                                                                                                                                                | Pass |
|------|-------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 36.1 | `ManualAssetsCard` mostra 5 colonne incluso `Δ BTC`                                 | 1. Apri TrackerHome / Tracking. 2. Scorri fino a "Manual assets". 3. Osserva l'header row e una qualsiasi data row.                                                                              | Header: 5 colonne (`NAME` / `TYPE` / `VALUE NOW` / `VALUE AT BUY` / `Δ BTC`). Riga ~52dp con linea grossa BTC + linea piccola €, due celle dual-line, e una cella `Δ BTC` colorata.                                                                  | [auto] ManualAssetsCardBitcoinLensTest.`header row shows 5 columns including DELTA BTC` + `data row shows dual-line VALUE NOW and VALUE AT BUY` |
| 36.2 | Asset legacy (backfilled fallback) → `Δ BTC` negativo perché il rate è cambiato     | 1. Identifica un asset creato pre-Phase-8.2 (Phase 8.2 backfill ha riempito `purchase_unix ← created_at_unix`, `purchase_value_fiat ← value_fiat`). 2. Osserva la cella `Δ BTC`.                  | `Δ BTC` mostra "↓ -X%" (negativo) anche se il valore EUR non si è mosso. Motivo: con purchase_value_fiat ≈ value_fiat, il "fiat delta" è zero MA il BTC equivalente cala perché il rate (BTC/EUR) è salito tra `created_at_unix` e ora.              | [auto] ManualAssetsBtcDeltaTest.`delta sign tracks rate move with constant fiat value` |
| 36.3 | Immobile di esempio con dati di acquisto → perdita BTC visibile                     | 1. Crea / modifica un asset `real_estate` €300.000 in EUR. 2. Verifica che il backfill abbia popolato `purchase_btc_at_time` ≈ 50.000 (2021) tramite il `BtcRateHistoryClient` (Phase 8.1).      | `VALUE NOW`: linea grossa "₿X.XX" con €300,000 sotto. `VALUE AT BUY`: "₿Y.YY" con "€250,000 (2021)" sotto (hint anno). `Δ BTC`: "↓ -Z%" rosso (financialNegative), tipicamente nell'ordine di −20÷−60% per asset comprati nel pump 2021.            | [auto] ManualAssetsBtcDeltaTest.`apple-storyboard real estate plus EUR minus BTC` + ManualAssetsCardBitcoinLensTest.`purchase year appears next to AT BUY secondary line` |
| 36.4 | Asset in USD su dashboard EUR → `Δ BTC` "—"                                         | 1. Aggiungi un asset con `currency = USD`. 2. Osserva la cella `Δ BTC`.                                                                                                                          | `Δ BTC`: em-dash "—" silenzioso. Motivo: PriceCache espone solo BTC/EUR; lo stesso `<Money>` rende "—" nella linea BTC della cella VALUE NOW (cross-currency). Identica policy a InvestmentsCard §35.4.                                              | [auto] ManualAssetsCardBitcoinLensTest.`delta BTC renders explicit no-rate for cross-currency asset` |
| 36.5 | `SavingsGoalsCard`: progress bar invariata, amount in BTC via `<Money>`             | 1. Apri TrackerHome. 2. Osserva un goal qualsiasi (manuale o bucket).                                                                                                                            | Progress bar e ON TRACK chip (per sinking) invariati. La linea sotto la barra ora mostra due `<Money>` Medium (BTC primary + fiat secondary): "₿0.25 (€15k) of ₿1.00 (€60k)". Allocation suffix "target X% NW" resta plain text.                    | [auto] SavingsGoalsCardBitcoinLensTest.`goal current_sat rendered via Money Medium BTC primary` + `progress percentage unchanged regression check` |
| 36.6 | `KpiStripV7`: 5 cards visibili, `OPP COST` placeholder                              | 1. Apri TrackerHome. 2. Scorri allo strip KPI sopra "CASH FLOW · 90D".                                                                                                                            | 5 card weight(1f) in riga: `SAVINGS` (%) · `BURN` (₿... / €...) · `FIRE` (₿... / €...) · `RUNWAY` (mesi) · `OPP COST` (em-dash "—" + sottotitolo "vs HODL (Phase 8.5)"). BURN/FIRE ora usano `<Money>` Compact (BTC primary + fiat fra parentesi).  | [auto] KpiStripV7BitcoinLensTest.`five KPI cards rendered including OPP_COST` + `BURN and FIRE use Money Compact BTC primary` |
| 36.7 | `RecurringCard`: candidate amount in BTC via `<Money>` Compact                      | 1. Apri TrackerHome con almeno una recurring candidate detectata (cfr. Phase 7.5). 2. Osserva la riga della candidate.                                                                          | La riga sotto il nome (es. "Spotify Premium") mostra `<Money>` Compact ("₿X.XX (€Y)") + " · monthly · next ~ d mmm". Le azioni "+ Bill" e "Dismiss" restano cliccabili senza side-effects rispetto a Phase 7.5.                                       | [auto] RecurringCardBitcoinLensTest.`candidate amount renders Money Compact BTC primary` + `accept and dismiss actions remain wired` |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.ManualAssetsBtcDeltaTest" --tests "io.bhodl.desktop.screens.ManualAssetsCardBitcoinLensTest" --tests "io.bhodl.desktop.screens.KpiStripV7BitcoinLensTest" --tests "io.bhodl.desktop.screens.SavingsGoalsCardBitcoinLensTest" --tests "io.bhodl.desktop.screens.RecurringCardBitcoinLensTest"
```

Atteso: 26 nuovi test verdi (`ManualAssetsBtcDeltaTest` 7 + `ManualAssetsCardBitcoinLensTest` 7 + `KpiStripV7BitcoinLensTest` 5 + `SavingsGoalsCardBitcoinLensTest` 4 + `RecurringCardBitcoinLensTest` 3).

### Done

- 36.1–36.7 ✓ su Windows.
- Helper puro `computeManualAssetDeltaBtcPct` in `ManualAssetsBtcDelta.kt` con policy "ogni input null/non-finito/non-positivo → null" → cella `Δ BTC` em-dash silenzioso.
- `ManualAssetsCard` ora 5 colonne, righe 52dp dual-line, Δ BTC con colore by-sign.
- `SavingsGoalsCard`: linea sat sotto la progress bar via `<Money>` Medium; rate suggerita per sinking via `<Money>` Compact (elimina la chiamata UniFFI `formatSat` che provocava un crash JNI nei test Compose).
- `KpiStripV7`: BURN/FIRE via `<Money>` Compact + 5° slot `OPP COST` (placeholder Phase 8.5).
- `RecurringCard`: amount candidato via `<Money>` Compact, suffix frequenza/data invariato.
- No TODO Phase 8.3.4 residuo. Chiude Phase 8.3 (Bitcoin Lens migration).

---

## 37. Phase 8.4 — Net worth historical reconstruction (manuale)

> §37 sblocca la sparkline sat-on-y vera nel `NetWorthHero`. Fino a 8.3.2
> la sparkline era una linea flat (placeholder); ora la funzione Rust
> `reconstruct_net_worth_history` ricostruisce un punto per ogni giorno UTC
> sommando bilancio on-chain + investments + manual assets − liabilities,
> valutati in BTC al rate del giorno tramite `BtcRateHistoryRepository`.
> Il Kotlin wrapper `NetWorthHistoryClient` aggiunge TTL cache in-memory.

Pre-condizioni comuni: TrackerHome attivo, fiat currency = EUR su
dashboard, almeno un deposito on-chain storico presente, PriceCache idratata.

| #    | Test                                                                                          | Passi                                                                                                                                                                                                                                   | Atteso                                                                                                                                                                                                                                                       | Pass |
|------|-----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 37.1 | `NetWorthHero` sparkline mostra movimento sat dove c'è stato un deposito                      | 1. Apri TrackerHome. 2. Osserva la sparkline sat nella hero card (asse-y in sat). 3. Confronta la forma della curva con la data dell'ultimo deposito on-chain (Timeline).                                                               | La sparkline ha un gradino visibile in corrispondenza della data del deposito — non è più una linea piatta. Il delta-chip "₿ ±X sat" rispecchia la differenza tra snapshot iniziale e snapshot finale del periodo selezionato.                               | [auto] NetWorthHeroNwHistoryTest.`sparkline primary uses reconstructed sat history when available` + net_worth_history::reconstruct_with_single_onchain_event_steps_up_at_block_time (cargo) |
| 37.2 | Period selector "1Y" mostra movimento sat ai depositi storici                                 | 1. Seleziona period "1Y" nel `NetWorthHero`. 2. Osserva la sparkline.                                                                                                                                                                   | La curva mostra step multipli corrispondenti ai depositi dell'anno. Rate change influisce sulla curva fiat overlay ma non sulla sat-on-y. Flat section = giorni con rate mancante (soft-fail corretto).                                                       | [partial] net_worth_history::reconstruct_handles_partial_rate_history_with_zeros_not_errors (cargo) — manual: 1Y multi-step |
| 37.3 | Network non disponibile → sparkline torna flat senza errore visivo                            | 1. Disabilita la rete (es. modalità aereo / firewall). 2. Riavvia l'app. 3. Naviga su TrackerHome. 4. Osserva la sparkline.                                                                                                             | La sparkline è flat (placeholder di 8.3.2), non c'è crash, nessun dialog di errore visibile all'utente. Il blocco `netWorthHistory` in `NetWorthHistoryClient` ha catturato l'eccezione e restituito lista vuota.                                             | [auto] NetWorthHistoryClientTest.`network failure returns empty list without throwing` + NetWorthHeroNwHistoryTest.`falls back to flat line when reconstruction returns empty` |
| 37.4 | Manual asset comprato nel passato appare nella sparkline a partire dalla data di acquisto     | 1. Aggiungi un manual asset con `purchase_unix` impostato a 6 mesi fa (EUR). 2. Seleziona period "1Y". 3. Osserva la sparkline.                                                                                                         | La sparkline è flat fino a 6 mesi fa, poi mostra un gradino positivo in corrispondenza del `purchase_unix` dell'asset. Prima della data: l'asset non contribuisce. Dopo: contribuisce per tutta la finestra residua.                                          | [auto] net_worth_history::reconstruct_excludes_future_manual_assets (cargo) |
| 37.5 | Liability presente abbassa il totale rispetto al solo `onchain_balance`                       | 1. Aggiungi una liability (es. "prestito" 50.000 EUR). 2. Torna su TrackerHome. 3. Confronta la sparkline sat col valore atteso: total = onchain + investments + manual − liabilities.                                                     | La hero card mostra `total_sat` inferiore a `onchain_sat` da solo (visibile nel tooltip o confrontando con la breakdowntable). I `liabilities_sat` sono costanti su tutti i giorni (approssimazione documentata: nessuna storia storica per le passività).    | [auto] net_worth_history::reconstruct_liabilities_constant_across_days (cargo) |

### Verifiche automatizzate

```
cargo test -p bhodl-wallet-onchain -- net_worth_history --nocapture

./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.prices.NetWorthHistoryClientTest"
```

Atteso:
- 9 nuovi test Rust verdi in `net_worth_history.rs::tests`.
- 3 nuovi test Kotlin verdi in `NetWorthHistoryClientTest`.

### Done

- §6.1 cargo test verdi (9 test Rust in `net_worth_history.rs::tests`).
- §6.2 jvmTest verdi (3 test Kotlin in `NetWorthHistoryClientTest`).
- `NetWorthHistoryClient.kt` singleton con TTL cache (5min/1h/6h), `NwHistorySource` seam, `clearCacheForTest`.
- `NwSnapshotFfi` + `PaymentEventLiteFfi` + `net_worth_history()` aggiunto al layer UniFFI in `bhodl-uniffi/lib.rs`.
- `reconstruct_net_worth_history` + 3 sub-helpers (`onchain_balance_at`, `investments_value_btc_at`, `manual_assets_value_btc_at`) in `crates/bhodl-wallet-onchain/src/net_worth_history.rs`.
- `open_registry_in_memory` helper aggiunto a `registry.rs` per i test Rust.
- No modifica a `NetWorthHero.kt` / `TrackerHomeScreen.kt` (scope Phase 8.5).
- No TODO Phase 8.4 residuo.

---

## 38. Phase 8.5 — Shadow portfolio "vs HODL" opportunity cost (manuale)

> §38 popola il KPI `OPP COST` lasciato vuoto in Phase 8.3.4. Il
> calcolo confronta ogni lot/manual-asset con uno scenario
> contro-fattuale: "se ogni € fosse stato convertito in BTC al rate
> del giorno, oggi avrei N sat in più (o in meno)". Pure Kotlin —
> tutti i field FFI necessari sono già esposti da Phase 8.2.

Pre-condizioni comuni: TrackerHome attivo, fiat currency = EUR su
dashboard, PriceCache idratata (`PriceCache.price` non-null), Phase 8.2
backfill applicato almeno una volta.

| #    | Test                                                                                          | Passi                                                                                                                                                                                                                                   | Atteso                                                                                                                                                                                                                                                       | Pass |
|------|-----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 38.1 | Portfolio con investments + manual assets backfilled → OPP COST mostra valore reale           | 1. Apri TrackerHome con almeno un investment con lot in EUR (`btc_per_unit_at_purchase` non null) e un manual asset in EUR (`purchase_value_fiat` e `purchase_btc_at_time` non null). 2. Osserva il KPI `OPP COST` nello strip dei KPI. | Il valore non è più "—" ma una stringa BTC tipo "−0.00XXXXXX BTC" (segno meno se BTC ha sovraperformato). Il colore della cifra è `financialNegative` quando il valore è positivo (utente ha "perso" BTC vs HODL). Il subtitle è "vs HODL" senza riferimenti a Phase 8.5. | [auto] OpportunityCostTest.`aggregates investments plus manual assets correctly` + KpiStripV7OppCostTest.`OPP_COST shows computed BTC value when opportunityCostBtc positive` |
| 38.2 | Portfolio nuovo (zero lots, zero manual assets) → OPP COST "—"                                | 1. Apri TrackerHome senza investments e senza manual assets. 2. Osserva il KPI `OPP COST`.                                                                                                                                              | Il valore è "—" (em-dash) con colore `textTertiary` neutro. Il subtitle resta "vs HODL".                                                                                                                                                                     | [auto] OpportunityCostTest.`totalBtc is null when no positions considered` + KpiStripV7OppCostTest.`OPP_COST shows em-dash when opportunityCostBtc is null` |
| 38.3 | Portfolio solo BTC (no fiat positions) → OPP COST "—"                                         | 1. Apri TrackerHome con solo wallet on-chain (BTC) e nessun investment né manual asset. 2. Osserva il KPI `OPP COST`.                                                                                                                   | Il valore è "—". Niente da confrontare contro HODL — la posizione è già in BTC. Counter `consideredPositions = 0` e `excludedPositions = 0` (nessuna riga è stata processata).                                                                              | [auto] OpportunityCostTest.`totalBtc is null when no positions considered` + `considered and excluded counters track each contribution` |
| 38.4 | Asset cross-currency (USD su dashboard EUR) → escluso dal calcolo                             | 1. Aggiungi un investment con `cost_basis_currency = "USD"` (es. AAPL su NYSE) o un manual asset con `currency = "USD"`. 2. Imposta dashboard fiat = EUR. 3. Apri TrackerHome.                                                          | Il KPI `OPP COST` resta "—" (o conta solo le posizioni EUR se ce ne sono). Il counter `excludedPositions` incrementa per ogni riga USD. Phase 8.x successiva potrebbe wirà rate USD live.                                                                    | [auto] OpportunityCostTest.`excluded when current rate not available for currency` |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.OpportunityCostTest"
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.KpiStripV7OppCostTest"
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.KpiStripV7BitcoinLensTest"
```

Atteso:
- 10 nuovi test Kotlin verdi in `OpportunityCostTest` (logiche pure).
- 4 nuovi test Compose UI verdi in `KpiStripV7OppCostTest`.
- 5 test esistenti verdi in `KpiStripV7BitcoinLensTest` (l'em-dash fallback resta corretto via `opportunityCostBtc = null` di default).

### Done

- §3.1 jvmTest pure verdi (10 test in `OpportunityCostTest`).
- §3.2 jvmTest Compose UI verdi (4 test in `KpiStripV7OppCostTest`).
- §3.3 test-book 38.1–38.4 ✓
- `OpportunityCost.kt` con `computeOpportunityCostBtc(...)` + `OpportunityCostBreakdown`.
- `FinanceKpis` esteso con `opportunityCostBtc: Double?` (default null) + overload `computeFinanceKpis(events, totalAssetSat, nowUnix, investments, lots, manualAssets, currentBtcPerUnit)`.
- `KpiStripV7` rende il KPI con segno esplicito (positive `opportunityCostBtc` → display "−X BTC") + colore by-sign (`financialNegative` / `financialPositive` / `textTertiary`).
- `TrackerHomeScreen` passa investments/lots/manualAssets + closure `currentBtcPerUnit` keyed sul dashboard fiat.
- No TODO Phase 8.5 residuo.

---

## 39. Phase 8.6 — Goal denomination + error hardening (manuale)

> §39 chiude Phase 8 con la denomination picker per i savings goal
> (sat default + fiat opt-in libero) e la migrazione dei rate error
> dal substring matching su `WalletException.Storage.message` ai
> typed variant `WalletException.RateCurrencyNotSupported` /
> `RateNetworkUnavailable` / `RateAllSourcesFailed`. Le query SQL che
> sommano `target_sat` sui goal NON vedono il contributo dei fiat goal
> (per design: il target in sat di un goal fiat non è persistito), quindi NW breakdown e drift chip
> ignorano i fiat goal: comportamento atteso.

Pre-condizioni comuni: TrackerHome attivo, fiat currency = EUR su
dashboard, PriceCache idratata (`PriceCache.price` non-null).

| #    | Test                                                                                  | Passi                                                                                                                                                                                                                                                                                  | Atteso                                                                                                                                                                                                                                                                              | Pass |
|------|---------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 39.1 | Crea nuovo goal con denomination `sat` (default) → comportamento attuale              | 1. Apri Savings goals → "+ Add". 2. Nome = "1 BTC stack", lascia denomination su `sat` (chip già evidenziato di default). 3. Target type = Absolute, target = `100000000` sat. 4. Save.                                                                                                | Il goal compare con progress bar e label `0 BTC of 1 BTC` esattamente come prima della 8.6. Nel DB `target_denomination = 'sat'`, `target_fiat_amount = NULL`, `target_fiat_currency = NULL`.                                                                                       | [auto] registry::set_savings_goal_denomination_sat_clears_fiat_fields (cargo) + SavingsGoalsCardDenominationTest.`effectiveTargetSat falls back to sat target for sat-denominated goal` |
| 39.2 | Crea nuovo goal con denomination `fiat`, target €1000 EUR → target_sat live dal rate  | 1. "+ Add" → nome = "Casa". 2. Tap chip `fiat`. 3. Compaiono i campi `Target amount` + `Currency (ISO)`. Inserisci `1000` e lascia `EUR` (prefilled). 4. Save.                                                                                                                          | Il goal compare. Il target line mostra `≈ X BTC` calcolato live (es. con rate 60_000 EUR/BTC → `≈ 0.01666666 BTC`). Nel DB `target_denomination = 'fiat'`, `target_sat = 0`, `target_fiat_amount = 1000.0`, `target_fiat_currency = 'EUR'`.                                          | [auto] SavingsGoalsCardDenominationTest.`goal saved with fiat denomination round-trips through DTO` + `fiat goal renders target via PriceCache rate when available` |
| 39.3 | Edit goal esistente sat → fiat → i field si vuotano, l'utente compila i nuovi field   | 1. Apri un goal sat-denominated. 2. Tap chip `fiat`. 3. Verifica che il campo `Target (sat)` scompare e compaiono `Target amount` + `Currency (ISO)` **vuoti** (no auto-conversion). 4. Compila amount=500 currency=EUR. 5. Save.                                                       | Nessun auto-fill dal vecchio target_sat. Dopo Save, il goal renderizza con target fiat €500 + sat equivalente live. Riaprendo il dialog il chip `fiat` è selected e i due campi sono valorizzati (no perdita di dati post-save).                                                     | [partial] SavingsGoalsCardDenominationTest.`selecting fiat shows fiat amount + currency inputs, hides sat input` — manual: reopen values |
| 39.4 | Goal fiat in currency senza PriceCache → "—" + chip "BTC equiv unavailable"           | 1. Crea (o edita) un goal con denomination=fiat, currency=`USD`, amount=1000. Dashboard fiat = EUR (PriceCache solo per EUR). 2. Osserva la riga sulla card.                                                                                                                            | Al posto del target sat compare un em-dash `—` e a destra una chip piccola "BTC equiv unavailable" (testTag `goal.btc-equiv-unavailable`). La progress bar mostra `0%`. Nessun crash, nessun valore garbage.                                                                          | [auto] SavingsGoalsCardDenominationTest.`fiat goal renders em-dash when PriceCache missing rate for currency` + `effectiveTargetSat returns unavailable when fiat amount missing` |
| 39.5 | Disconnect rete → goal fiat resta visibile col last-good rate                          | 1. Crea un goal fiat €1000 EUR. 2. Mentre la PriceCache ha un valore valido (last_fetch_unix recente), scollega la rete (Wi-Fi off). 3. Riapri la dashboard.                                                                                                                            | Il goal continua a mostrare il target sat calcolato dall'ultimo rate cached (PriceCache.price.value non-null). Nessuna eccezione propagata in UI. Quando la rete torna e PriceCache si aggiorna, il target sat si ri-aggiusta al nuovo rate.                                          | [ ]  |
| 39.6 | Smoke: error tipato `RateCurrencyNotSupported` arriva al client Kotlin                 | 1. Da Settings → "BTC rate history" (o equivalent), forza un backfill request con `currency = "XYZ"` (currency non supportata). 2. Osserva log / banner.                                                                                                                                | Banner di errore "Currency XYZ not supported" (o equivalente IllegalArgumentException catch nel chiamante). Nei log appare `BTC rate FFI: unsupported currency: XYZ` (non più `Storage("currency not supported: XYZ")`). No substring matching residuo.                              | [partial] BtcRateHistoryClientErrorHardeningTest.`rateAt with typed RateCurrencyNotSupported throws IllegalArgumentException` — manual: banner + log |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.SavingsGoalsCardDenominationTest"
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.prices.BtcRateHistoryClientErrorHardeningTest"
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.prices.BtcRateHistoryClientTest"
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.SavingsGoalsTargetMathTest"
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.SavingsGoalsCardBitcoinLensTest"
```

Atteso:
- ~10 nuovi test Kotlin verdi (~7 in `SavingsGoalsCardDenominationTest`, 3 in `BtcRateHistoryClientErrorHardeningTest`).
- Test esistenti `BtcRateHistoryClientTest` ancora verdi (fallback `Storage`-message-substring intatto per backward compatibility).
- Test esistenti `SavingsGoalsTargetMathTest` / `SavingsGoalsCardBitcoinLensTest` ancora verdi (sat-default invariato).

### Done

- §3.1 cargo test verdi (7 nuovi Rust test in `set_savings_goal_denomination*` + `wallet_error_rate_*_round_trip`).
- §3.2 jvmTest verdi (~10 nuovi Kotlin test).
- §3.3 test-book 39.1–39.6 ✓
- `SavingsGoalsCard.kt`: `GoalDenomination` enum + `EffectiveTarget` + `effectiveTargetSat()` resolver; dialog picker SAT/FIAT + fiat amount + ISO currency inputs; row em-dash + `BTC_EQUIV_UNAVAILABLE_CHIP` fallback.
- `PriceCache.kt`: `rateFor(currency)` helper (single-currency by design — multi-currency cache fuori scope).
- `BtcRateHistoryClient.kt`: typed catches per `RateCurrencyNotSupported` / `RateNetworkUnavailable` / `RateAllSourcesFailed`; legacy substring matching su `Storage.message` conservato come fallback per FFI path non ancora migrati.
- `TrackingScreen.kt`: dopo `add/updateSavingsGoal` chiama `set_savings_goal_denomination(id, denom, fiat_amount?, fiat_currency?)` atomicamente.
- No TODO Phase 8.6 residuo. Phase 8 Bitcoin Lens chiusa.

## 40. Security review 2026-06-10 — hardening pass (manuale)

> Esito dell'audit di cyber security (3 audit paralleli: app Kotlin,
> key-material lifecycle, Rust/FFI). Nessun finding Critical; questa
> sezione copre i fix user-visible. I fix Rust-only (zeroize
> `DescriptorParts`/`BackupBundle`, `checked_amount` al boundary FFI,
> SSRF guard PayJoin, cap JSONL/HTTP) sono coperti da test cargo.
>
> **2026-10-23 (watch-only)**: rimosse le righe 40.4 (PayJoin), 40.6
> (challenge mnemonic), 40.7 (recovery phrase), 40.8 (backup cifrato);
> 40.5 vale ora per il device PIN. "Verifiche" e "Done" restano come
> record storico.

Pre-condizioni comuni: build phase-8 con la security pass applicata.

| #     | Test                                            | Passi                                                                                                                  | Atteso                                                                                                                                                                                                  | Pass |
|-------|--------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 40.1  | URL Esplora invalido rifiutato                  | 1. Settings → Esplora URL. 2. Inserisci `not a url` o `ftp://x`. 3. Save.                                                | Il valore non viene salvato; errore inline nel campo. L'endpoint attivo resta quello precedente.                                                                                                            | [auto] SettingsNetworkSecurityTest.`invalid Esplora URLs are rejected inline and never saved` |
| 40.2  | Warning plaintext HTTP senza Tor (Esplora)      | 1. Tor OFF. 2. Settings → Esplora URL = `http://example.com/api`. 3. Save.                                               | Warning persistente "⚠ Plaintext HTTP endpoint without Tor…" sotto il campo. Con `http://127.0.0.1:3000` o un `.onion` il warning NON compare.                                                              | [auto] SettingsNetworkSecurityTest.`plaintext http warning appears for remote hosts only` |
| 40.3  | Default Esplora network-aware                   | 1. Wallet mainnet senza override URL. 2. Osserva l'endpoint attivo in Settings.                                          | `https://mempool.space/api` (non più signet). Wallet signet → `/signet/api`; regtest → `http://127.0.0.1:3000`.                                                                                             | [auto] SettingsNetworkSecurityTest.`a mainnet wallet shows the mainnet default and its own override only` + EsploraEndpointTest.`current(network) returns each network's own default` |
| 40.5  | Hint robustezza PIN                             | 1. Create device PIN. 2. Digita un PIN di 6 cifre.                                                                       | Hint "6-digit PIN: moderate protection against offline attacks — 8+ digits recommended." sotto il campo; con 8+ cifre sparisce. Il PIN da 6 resta accettato.                                             | [partial] PinValidatorTest (strength) — manual: hint su DevicePinSetup |
| 40.9  | Auto-clear clipboard xpub                       | 1. Wallet settings → Show watch-only descriptor → Copy. 2. Attendi 60s senza copiare altro. 3. Incolla.                  | Dopo ~60s la clipboard è vuota. Se nel frattempo hai copiato altro, il TUO contenuto resta intatto.                                                                                                         | [ ]  |
| 40.10 | Errori UI sanitizzati                           | 1. Provoca un errore FFI (es. Esplora URL valido ma irraggiungibile → sync).                                             | Il messaggio in UI non contiene path assoluti (`C:\Users\…` / `/home/…` → `<path>`) ed è troncato a ~200 caratteri.                                                                                         | [auto] ErrorMessagesTest (4 tests) |
| 40.11 | Permessi POSIX su `~/.bhodl` (solo Linux/macOS) | 1. Prima run su Linux/macOS. 2. `ls -ld ~/.bhodl && ls -l ~/.bhodl/settings.json`.                                       | Directory `rwx------` (700), `settings.json` `rw-------` (600). Su Windows nessun cambiamento (ACL di default).                                                                                             | [ ]  |
| 40.12 | Tor proxy URL validato                          | 1. Settings → Tor proxy URL = `socks5://host:99999` (porta invalida) o `http://x`.                                       | Valore rifiutato; resta l'ultimo valore valido.                                                                                                                                                             | [auto] SettingsNetworkSecurityTest.`Tor proxy rejects invalid URLs and stores socks5 as socks5h` |

### Verifiche automatizzate

```
cargo test --workspace
./gradlew :apps:desktop:jvmTest
```

Atteso:
- Rust: 385+ test verdi (nuovi: SSRF blocklist PayJoin ×10, empty-password
  backup, JSONL cap ×2, checked_amount, zeroize Drop).
- Kotlin: jvmTest verdi (nuovi: `PinValidatorTest.strength_*` ×3,
  `MnemonicChallengeTest.create_defaults_to_four_positions` (rimosso con il
  passaggio a watch-only, 2026-10-23), validazione
  URL in `SettingsStore`/`SettingsPersistence`, `BackfillRunnerTest`
  aggiornati).

### Done

- Rust: zeroize su `DescriptorParts`/`BackupBundle`/chain code; `Zeroizing`
  per PIN/mnemonic/passphrase nei wrapper FFI; `load_persistent` non
  trattiene più l'xpriv; `checked_amount` (cap 21M BTC) su tutti i
  `build_send_tx*`; SSRF guard sull'endpoint PayJoin; read HTTP bounded
  (4 MiB) su btc_rate_history; `list_utxos` → `Result` (niente abort);
  cap 100k righe su import JSONL; deny.toml `multiple-versions = "warn"`
  + licenza CDLA-Permissive-2.0.
- Kotlin: validazione Esplora/Tor URL + warning plaintext; default Esplora
  network-aware; permessi POSIX su datadir e settings; recovery phrase
  non selezionabile; warning PayJoin http; export backup bloccato senza
  password; hint robustezza PIN (6–7 cifre); challenge a 4 parole;
  auto-clear clipboard xpub (60s); `userMessage()`/`sanitizeErrorDetail`
  in ~18 schermate; `DevicePinSession` su CharArray azzerabile; qrose
  nel version catalog.
- Known limitations (accettate, documentate nel codice): mnemonic/PIN
  attraversano la FFI come String JVM (limite UniFFI/JVM); nessun
  certificate pinning sulle price API; cargo-deny advisories rotto a
  monte (parse CVSS 4.0) — da aggiornare in CI.

## 41. UX restructure 2026-06-12 — Phase 9 (IA + estetica + interazione)

> Esito dell'analisi UX vs canoni finance-tracker (Kubera / Monarch /
> Copilot / YNAB / Lunch Money). Cinque interventi: (1) IA a sezioni
> con rail espansa e home unica, (2) identità estetica unificata
> Primer-flat con neon opt-in, (3) periodo globale + filtri
> componibili, (4) CRUD su slide-over panel + date picker, (5) empty
> state espliciti + "no rate" al posto di "—".

Pre-condizioni comuni: build phase-9 con almeno un wallet watch-only
sincronizzato e i moduli Investments / Budget & Bills / Savings Goals /
Manual Assets attivi (Modules).

| #     | Test                                          | Passi                                                                                                                 | Atteso                                                                                                                                                                                                       | Pass |
|-------|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 41.1  | Rail a sezioni modulo-gated                   | 1. Apri il tracker (device PIN). 2. Osserva la rail sinistra. 3. Disattiva un modulo da Modules e torna indietro.        | Voci: Home, Activity, Budget, Goals, Portfolio, Reports, Wallet, Settings (in fondo). Disattivando es. Investments+Manual Assets la voce Portfolio sparisce dalla rail.                                        | [ ]  |
| 41.2  | Home unica con hero in testa                  | 1. Device PIN → atterri su Home.                                                                                         | Una sola home: NetWorthHero (privacy hold-to-reveal + sparkline) in alto, poi KPI strip, donut NW, inflow/outflow, lista wallet (clic = apre il wallet) con CTA add. Nessun "Open Dashboard" intermedio, TrackerHome non esiste più. | [ ]  |
| 41.3  | Sezioni dedicate (fine del muro di card)      | 1. Naviga Budget, Goals, Portfolio, Reports dalla rail.                                                                  | Budget: breakdown income/expense + recurring + bills + lollipop + Rules in fondo ("Automation"). Goals: solo goals. Portfolio: rollup + investments + manual assets + liabilities. Reports: tabelle + (con Analytics Labs) chart. | [ ]  |
| 41.4  | Periodo globale persistente                   | 1. Su Home seleziona 90g (dal selector o dai chip dell'hero). 2. Vai su Budget, poi Reports.                             | Tutte le sezioni (incluse le tabelle Reports, titolo "By category · 90g") mostrano la stessa finestra. I chip dell'hero e il selector globale sono sempre allineati.                                            | [ ]  |
| 41.5  | Filtri componibili su Activity                | 1. Da Budget clicca una categoria del lollipop. 2. Da Reports/chart clicca un bucket temporale.                          | Atterri su Activity con ENTRAMBI i filtri attivi (chip categoria + chip range): il secondo drill-down non azzera il primo. Esc li pulisce entrambi.                                                              | [ ]  |
| 41.6  | Scorciatoie sezioni                           | 1. Nel tracker premi 1…6, poi Ctrl/Cmd+K.                                                                                | 1=Home, 2=Activity, 3=Budget, 4=Goals, 5=Portfolio, 6=Reports. La palette elenca "Vai a <sezione>" per tutte e sei + periodo/refresh/CSV.                                                                        | [ ]  |
| 41.7  | Estetica flat unificata                       | 1. Tema Dark. 2. Scorri Home/Budget/Goals/Portfolio.                                                                      | Tutte le card hanno lo stesso linguaggio: superficie piatta, bordo 1dp, raggio 8dp. Nessun glow/gradient. Donut: arancio brand (BTC), palette Okabe-Ito (investments/manual), rosso semantico (liabilities); barre in/out = verde/rosso del tema. | [ ]  |
| 41.8  | Neon opt-in                                   | 1. Settings → Theme → Neon. 2. Torna al tracker.                                                                          | Le stesse card tornano col bordo glow + gradient (16dp). Dark/Light/System restano flat.                                                                                                                         | [ ]  |
| 41.9  | CRUD su slide-over panel                      | 1. Budget → "+ Add" su Bills. 2. Goals → "+ Add". 3. Portfolio → add/edit investment, lot, sell, manual asset, liability. 4. Budget → regola. | Ogni form si apre come pannello laterale destro (440dp, scrim, ✕, Esc chiude, click sullo scrim chiude). Il GoalEdit è organizzato in sezioni GOAL / TARGET / SCHEDULE / BUCKET / COLOR.                         | [ ]  |
| 41.10 | Date picker al posto del testo libero         | 1. Goals → nuovo sinking goal → campo deadline. 2. Portfolio → add lot → purchase date. 3. Sell → sale date.             | I tre campi data aprono un calendario Material (niente parsing YYYY-MM-DD a mano); la data scelta compare in formato ISO nel campo.                                                                              | [ ]  |
| 41.11 | Empty state con CTA                           | 1. Con registry fresco apri Budget / Goals / Portfolio.                                                                   | Recurring: zero-state "No recurring payments detected yet…" (la card non sparisce). Goals/Investments/Manual Assets: riga esplicativa + "+ Add" visibile.                                                        | [partial] ManualAssetsCardBitcoinLensTest.`empty state preserved when no assets` — note: RecurringCard renders nothing when empty |
| 41.12 | "no rate" esplicito                           | 1. Crea un manual asset in USD con dashboard EUR. 2. Osserva Δ BTC. 3. Spegni la rete e osserva la riga fiat dell'hero.   | Le celle il cui valore esiste ma manca il tasso mostrano "no rate" (mai "—"). Il "—" resta solo dove il dato non esiste proprio (es. snapshot d'acquisto mancante).                                              | [partial] MoneyRenderTest.`Missing rate on AtCost renders explicit no-rate on BTC line` + ManualAssetsCardBitcoinLensTest.`delta BTC renders explicit no-rate for cross-currency asset` — manual: hero offline |
| 41.13 | Wallet tab senza wallet aperto                | 1. Senza wallet aperto, clicca Wallet nella rail.                                                                         | Atterri sulla Home del tracker (la lista wallet è il selettore), nessuna richiesta PIN. Con un wallet aperto → workspace del wallet.                                                                         | [ ]  |
| 41.14 | Settings dalla rail                           | 1. Da qualsiasi sezione clicca Settings (in fondo alla rail). 2. Back.                                                    | Settings si apre full-screen; Back torna alla sezione di provenienza.                                                                                                                                            | [ ]  |

### Verifiche automatizzate

```
./gradlew :apps:desktop:compileKotlinJvm
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.*" --tests "io.bhodl.desktop.prices.*" --tests "io.bhodl.desktop.data.*" --tests "io.bhodl.desktop.wizard.*"
```

Atteso: 618 test verdi (aggiornato:
`ManualAssetsCardBitcoinLensTest."delta BTC renders explicit no-rate
for cross-currency asset"` asserisce "no rate" al posto di "—").

### Done

- IA: `Route.Tracking(section)` parametrica + enum `TrackerSection`;
  rail TwoTabShell a 8 voci modulo-gated con Settings in fondo;
  TrackerHome fuso nella sezione HOME (`TrackerHomeSection`); rotte
  `TrackerHome`/`Analytics` rimosse; `TrackingTabMode` =
  HOME/BUDGET/GOALS/PORTFOLIO/ANALYTICS; RulesCard demolita in fondo a
  Budget; stato condiviso in `TrackerUiState` (periodo + filtri).
- Estetica: `Modifier.bhodlCard()` (flat 8dp+outline di default, neon
  16dp+glow solo con ThemeMode.Neon); migrate tutte le card
  neon/12dp/16dp; `nwRoleColors()` per donut/portfolio; barre e legenda
  inflow/outflow su financialPositive/Negative; badge kind su
  Okabe-Ito; `design/tokens.json` riconciliato con BhodlTheme.kt
  (brand #F7931A, superfici Primer, header "source of truth").
- Periodo/filtri: periodo globale in TrackerUiState consumato da hero
  (mapping NetWorthPeriod↔TrackingPeriod), KPI, Budget, Reports
  (tabelle pre-filtrate); drill-down compongono categoria+range.
- Interazione: `SlideOverPanel` (API drop-in AlertDialog) per i 9 CRUD
  (budget, bill, goal, liability, manual asset, investment, lot, sell,
  rule); `DatePickerField` (epoch seconds) per deadline sinking, data
  acquisto lot, data sale; GoalEdit a sezioni.
- Stati: zero-state RecurringCard; empty state con CTA su
  Goals/Investments/ManualAssets; `Money` distingue "no rate"
  (conversione impossibile) da "—" (nessun valore).

## 42. Fix prezzi investimenti — endpoint Yahoo v8 (2026-06-13)

> Bug: nessuna quota investimento si aggiornava più (es. ticker AAPL).
> Causa duplice: (a) `autoFetchInvestmentPrices` è OFF di default — va
> attivato in Settings; (b) l'endpoint `/v7/finance/quote` di Yahoo ora
> risponde `401 Unauthorized` (richiede cookie+crumb), quindi
> `fetchYahooQuote` riceveva 401, `runCatching` lo ingoiava → null →
> nessuna scrittura del prezzo. Fix: ricablato `PriceFetcher` sul nuovo
> endpoint `/v8/finance/chart/{ticker}` (anonimo, `200`), prezzo letto
> da `chart.result[0].meta.regularMarketPrice` + `meta.currency`.
>
> Aggiunti inoltre due trigger di refresh (prima la fetch quote partiva
> solo dal loop a 5 min): (1) `refreshNow(forceInvestmentPrices=true)`
> dal chip prezzo dell'hero → forza le quote anche con auto-fetch off;
> (2) `LaunchedEffect` su `autoFetchInvestmentPrices` in Main.kt → all'
> attivazione del toggle parte subito una fetch. `refreshInvestmentPrices`
> ora prende il `refreshMutex` (corpo interno `…Locked()`) per serializzare
> i tap manuali col loop di background.
>
> Wiring `rateFor` (cross-currency): `InvestmentsCard` e `ManualAssetsCard`
> risolvono il tasso BTC corrente via `PriceCache.rateFor(currency)` invece
> di accettarlo solo quando la valuta coincide con la dashboard. La mappa
> `_currencyRates` (popolata dall'auto-fetch con BTC/EUR-USD-CHF) alimenta
> la riga BTC anche per posizioni in valuta estera (es. AAPL/USD su EUR).
> I totali restano dashboard-fiat-only (no forex). Test:
> `ManualAssetsCardBitcoinLensTest."delta BTC renders percentage for
> cross-currency asset when rate is cached"` + seeder
> `PriceCache.seedCurrencyRatesForTest`.

| #     | Test                                          | Passi                                                                                                                  | Atteso                                                                                                                                                              | Pass |
|-------|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 42.1  | Auto-fetch on + ticker valido                 | 1. Settings → attiva "Auto-fetch investment prices". 2. Aggiungi investimento con ticker AAPL (ccy USD). 3. Attendi il refresh (≤5 min) o forza il refresh prezzo. | La colonna VALUE in fiat si popola col prezzo Yahoo (es. ~$124/share × qty). Nessun valore manuale richiesto.                                                       | [partial] PriceFetcherTest.`parseYahooBody extracts the regular market price` — manual: live fetch |
| 42.2  | Auto-fetch off → nessun fetch                 | 1. Settings → auto-fetch OFF. 2. Aggiungi investimento con ticker. 3. Attendi.                                          | VALUE resta sul `manualCurrentValue` (o vuoto se non inserito). Nessuna chiamata di rete per le quote.                                                              | [partial] SettingsStoreTest.`default autoFetchInvestmentPrices is false (off by default)` — manual: no fetch happens |
| 42.3  | Ticker inesistente                            | 1. Auto-fetch ON. 2. Aggiungi investimento con ticker `ZZZZINVALID`.                                                    | Nessun crash, nessuna scrittura: il prezzo resta non valorizzato (parser → null su `result:null`).                                                                  | [auto] PriceFetcherTest.`parseYahooBody returns null when result is null (unknown ticker)` |
| 42.4  | Cross-currency BTC line (wiring rateFor)      | 1. Dashboard EUR, auto-fetch ON. 2. Investimento AAPL in USD con quota fetchata. 3. Attendi/forza un refresh (così l'auto-fetch cachea BTC/USD). | La riga/colonna BTC si popola col valore reale (via `PriceCache.rateFor("USD")` → mappa `_currencyRates`), NON "no rate". "no rate" resta solo per valute fuori dall'enum (GBP/JPY…) o se il tasso non è ancora in cache. Idem Δ BTC dei Manual Asset in valuta estera. | [partial] ManualAssetsCardBitcoinLensTest.`delta BTC renders percentage for cross-currency asset when rate is cached` — manual: InvestmentsCard BTC line |
| 42.5  | Tor on                                        | 1. Settings → Tor ON. 2. Auto-fetch ON + ticker valido.                                                                | La quota si aggiorna comunque (chiamata via SOCKS5), oppure resta non valorizzata senza crash se il proxy è giù.                                                    | [partial] PriceFetcherTorTest.`tor path fails when the proxy is unreachable` + PriceFetcherLiveTorTest (nightly) — manual: Yahoo quote via Tor |
| 42.6  | Refresh manuale forza le quote                | 1. Auto-fetch **OFF**. 2. Aggiungi AAPL (nessun valore manuale). 3. Sulla Home clicca il chip prezzo BTC dell'hero.     | Anche con auto-fetch off, il tap forza `refreshInvestmentPrices`: VALUE si popola dopo il refresh. (Idem il chip "stale" sulla card Portfolio.)                     | [ ]  |
| 42.7  | Auto-fetch off→on innesca fetch               | 1. Con AAPL già presente e VALUE vuoto, auto-fetch OFF. 2. Attiva "Auto-fetch investment prices" in Settings.           | All'attivazione parte subito una fetch (senza aspettare il tick a 5 min né cliccare): VALUE si popola entro pochi secondi.                                          | [ ]  |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.prices.PriceFetcherTest"
```

Atteso: verde. Parser Yahoo migrato alla forma `chart.result[].meta`
(`parseYahooBody`), `buildYahooUrl` punta a `/v8/finance/chart/` con
encoding path-safe (slash/`&`/`=` percent-encoded). Endpoint v8
verificato live `HTTP 200` con lo user-agent reale dell'app.

## 43. Fix edit investimento non riflesso in pagina (2026-06-13)

> Bug: modificando qty/costo di un investimento dal dialog, la riga non
> si aggiornava. Causa: ogni investimento creato dal dialog semina un
> lot (`seedInvestmentLotFromAggregate`), e la riga mostra qty/costo
> DERIVATI dai lot (`effectiveQuantity`/`effectiveCostBasis` ignorano i
> campi aggregati quando esiste ≥1 lot). L'edit però aggiornava solo i
> campi aggregati → no-op visivo. Fix: in `TrackingScreen`, sull'edit di
> un investimento con ESATTAMENTE un lot, il lot viene risincronizzato
> dal nuovo aggregato (`pricePerUnit = (costBasis − fees) / qty`).

| #     | Test                                          | Passi                                                                                                                 | Atteso                                                                                                                          | Pass |
|-------|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|------|
| 43.1  | Edit qty/costo riflesso (1 lot)               | 1. Crea investimento (qty 10, costo 1000). 2. Aprilo in edit, cambia qty→20 e costo→3000. Salva.                       | La riga mostra subito QTY 20 e COST 3000 (lot singolo risincronizzato); P&L ricalcolato. Nessun refresh manuale necessario.    | [ ]  |
| 43.2  | Edit metadati                                 | 1. Edit di nome/ticker/tipo/valuta/valore-per-unità. Salva.                                                            | Tutti i campi sul row si aggiornano (sono sul record investimento, già reattivi via bumpDataVersion).                          | [partial] registry::investments_update_changes_fields_in_place (cargo) — manual: row reload |
| 43.3  | Multi-lot: qty/costo read-only                | 1. Investimento con 2+ lot. 2. Apri il dialog edit.                                                                    | QTY e COST sono read-only e mostrano la SOMMA dei lot (pre-fill dai valori effettivi); hint "Quantità e costo sono la somma dei N lot — modificali espandendo la riga". Nome/ticker/tipo/valuta/valore-per-unità restano editabili. | [ ]  |
| 43.4  | Dialog pre-fill = riga (1 lot)                | 1. Investimento con 1 lot il cui aggregato è divergente (es. dato pre-fix). 2. Apri il dialog.                          | Il dialog mostra qty/costo DERIVATI dal lot (= ciò che vede la riga), non l'aggregato stantio. Al Save i due restano allineati.                                                                                                     | [partial] InvestmentLotsTest.`effectiveQuantity sums lot quantities` — manual: dialog prefill |
| 43.5  | Delete investimento                           | 1. Edit → Delete.                                                                                                       | L'investimento sparisce dalla card (bumpDataVersion → reload).                                                                 | [partial] registry::investments_delete_returns_true_then_false (cargo) — manual: card reload |
| 43.6  | Quota in valuta estera convertita             | 1. AAPL ticker, Ccy=EUR, dashboard EUR, auto-fetch ON. 2. Refresh.                                                     | VALUE in € coerente: la quota Yahoo (USD) è convertita EUR via BTC (`convertViaBtc`), non mostrata come "€<prezzo USD>". Se la valuta quota non è prezzabile (no rate) il VALUE resta non scritto invece di un numero mislabellato. | [auto] PriceCacheTest.`convertViaBtc bridges USD quote to EUR via BTC` + `convertViaBtc returns null when a rate is missing` |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.InvestmentLots*" --tests "io.bhodl.desktop.screens.InvestmentsCardBitcoinLensTest" --tests "io.bhodl.desktop.prices.PriceCacheTest"
```

Atteso: verde. (a) Pre-fill del dialog dai valori effettivi
(`effectiveQuantity`/`effectiveCostBasis`) + qty/costo read-only per
multi-lot; sync del lot singolo al Save in `TrackingScreen`. (b)
`PriceCache.convertViaBtc` converte la quota nella valuta
dell'investimento via BTC prima di scriverla (5 test unit dedicati);
`ensureRate` recupera on-demand il tasso BTC/<ccy> della valuta quota.

## 44. Wiring persistenza settings al boot (2026-06-13)

> Diagnosi: il layer `SettingsPersistence` (snapshot/apply/save/load/
> rehydrate, scrittura atomica, versioning, perms 600) + i write-through
> `persistIfEnabled()` su ogni setter ESISTEVANO già e sono testati, ma
> `rehydrate` ed `enablePersistence` erano chiamati SOLO nei test, mai in
> produzione → i settings (auto-fetch, valuta fiat, moduli, tema, Tor…)
> si resettavano a ogni riavvio. Fix: cablaggio in `Main.kt` → all'avvio
> `SettingsPersistence.rehydrate(<dataDir>/settings.json)` PRIMA della
> prima composizione, poi `SettingsStore.enablePersistence(file)`. File
> non cifrato col PIN (prefs UX a bassa sensibilità), letto pre-unlock.

| #     | Test                                          | Passi                                                                                                                 | Atteso                                                                                                                          | Pass |
|-------|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|------|
| 44.1  | Auto-fetch persiste al riavvio                | 1. Settings → attiva auto-fetch. 2. Chiudi e riapri l'app.                                                             | Auto-fetch è ancora ON (prima tornava OFF). Stesso per valuta fiat, tema, unit, moduli, Tor.                                   | [partial] SettingsPersistenceTest.`save then load round-trips every field` — manual: Main.kt boot wiring, real restart |
| 44.2  | File settings.json creato                     | 1. Cambia un'impostazione qualsiasi. 2. Ispeziona `~/.bhodl/settings.json`.                                            | Il file esiste, JSON leggibile, contiene il valore appena cambiato; permessi owner-only dove supportato.                       | [auto] SettingsPersistenceTest.`save writes a UTF-8 JSON file and creates parent dirs` |
| 44.3  | Primo avvio senza file                        | 1. Rimuovi `settings.json`. 2. Avvia l'app.                                                                            | Nessun crash; lo store parte dai default; al primo cambio il file viene ricreato.                                              | [auto] SettingsPersistenceTest.`load returns null on missing file (first launch)` |
| 44.4  | Tema corretto al primo frame                  | 1. Imposta tema Neon. 2. Riavvia.                                                                                      | L'app parte già in Neon (rehydrate avviene PRIMA della composizione: nessun flash dal default System).                        | [ ]  |
| 44.5  | File corrotto                                 | 1. Scrivi JSON invalido in `settings.json`. 2. Avvia.                                                                  | Nessun crash; default applicati; al primo cambio il file viene risovrascritto correttamente.                                  | [auto] SettingsPersistenceTest.`corrupt file is backed up, Tor fails closed and a warning is raised` + SettingsNetworkSecurityTest.`corrupt settings file raises a dismissable banner and keeps Tor on` |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.settings.*"
```

Atteso: verde. La logica di `SettingsPersistence` era già coperta
(round-trip, versioning, backward-compat, perms); questo intervento è
puro wiring nel composition root (`Main.kt`), verificato manualmente coi
test 44.x (l'entry point `main()` non è unit-testabile).

## 45. BTC P&L corretto — tasso storico alla data dell'evento (2026-06-14)

> Riprogettazione hardenizzata del P&L "in Bitcoin". Prima il tasso
> BTC d'acquisto era quello CORRENTE all'inserimento (sbagliato per
> acquisti retrodatati) o assente; ora ogni evento (acquisto lotto,
> vendita, acquisto manual asset) risolve il tasso BTC/<valuta> STORICO
> alla data dell'evento, nella valuta propria dell'asset, via metodi
> registry-interni (`resolve_lot_btc_rate` / `resolve_sell_btc` /
> `resolve_manual_asset_purchase_btc`), riusando la macchina del backfill
> Phase 8.2. Risoluzione fuori dal percorso critico del save (riga subito
> visibile, tasso al secondo bump). Tasso non risolvibile → colonna NULL
> → "no rate" (mai un valore finto). PIN sessione via DevicePinSession.

| #     | Test                                          | Passi                                                                                                                  | Atteso                                                                                                                                            | Pass |
|-------|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 45.1  | Acquisto retrodatato → tasso storico          | 1. Nuovo investimento, ticker, "Data acquisto" = un anno fa. Salva. 2. Espandi la riga / guarda COST in BTC.            | Il cost basis BTC usa il tasso BTC di UN ANNO FA (non quello di oggi). Verificabile: ≈ costo_fiat / BTCprice(allora).                            | [auto] btc_at_time_backfill::resolve_lot_btc_rate_writes_historical_rate_not_current (cargo) |
| 45.2  | Modifica data lotto → re-risoluzione          | 1. Espandi un lotto, cambia "Data acquisto". Salva.                                                                     | `btc_per_unit_at_purchase` ricalcolato alla nuova data; COST BTC e P&L BTC cambiano di conseguenza.                                              | [partial] btc_at_time_backfill::resolve_lot_btc_rate_idempotent_on_second_call (cargo) — manual: re-resolve after date edit |
| 45.3  | Vendita → realizzato BTC                       | 1. Registra una vendita con data passata.                                                                              | La riga "Sells" mostra il realizzato in BTC (gain_loss_btc) al tasso storico della data di vendita, non "—".                                     | [auto] btc_at_time_backfill::resolve_sell_btc_writes_sale_rate_and_gain_loss_for_disposal (cargo) |
| 45.4  | Manual asset con acquisto → Δ BTC reale       | 1. Nuovo manual asset, compila "Purchase date" + "Purchase value". Salva.                                              | La colonna Δ BTC mostra una percentuale reale (su/giù) invece di "—". Senza i campi purchase → resta "—".                                        | [auto] btc_at_time_backfill::resolve_manual_asset_purchase_btc_writes_rate_for_explicit_purchase_unix (cargo) |
| 45.5  | Recompute one-shot                            | 1. Settings → "Ricalcola cost basis BTC".                                                                              | Mostra "Aggiornati N/M lotti · K senza tasso"; i lotti col vecchio tasso corrente vengono sovrascritti col tasso storico. Idempotente.          | [partial] btc_at_time_backfill::recompute_all_lot_btc_rates_overwrites_wrong_seeded_value (cargo) — manual: Settings button + message |
| 45.6  | Null-policy (rete giù / valuta non supp.)     | 1. Disattiva la rete. 2. Aggiungi lotto retrodatato in valuta esotica (es. GBP).                                       | Il save NON si blocca; la riga compare subito; il tasso resta NULL → "no rate" (mai un numero finto). Riprovabile con recompute a rete attiva.  | [partial] btc_at_time_backfill::resolve_lot_btc_rate_leaves_null_when_rate_unavailable (cargo) — manual: non-blocking save |
| 45.7  | Cross-currency nella valuta propria           | 1. Lotto AAPL in USD su dashboard EUR.                                                                                 | Il cost basis BTC è risolto in BTC/USD (valuta del lotto), indipendente dalla dashboard.                                                        | [auto] btc_at_time_backfill::resolve_lot_btc_rate_uses_own_currency_not_dashboard_currency (cargo) |

### Verifiche automatizzate

```
cargo test -p bhodl-wallet-onchain          # resolve_* + recompute + add/update_manual_asset
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.prices.*" \
  --tests "io.bhodl.desktop.screens.*" --tests "io.bhodl.desktop.settings.*"
```

Atteso: verde. Rust: 323 test (resolve scrive il tasso storico alla data,
nella valuta della riga; NULL su rate-unavailable; idempotenza; recompute
sovrascrive). Kotlin: invariati + compile pulito del wiring (dialog con
DatePickerField, handler che chiamano `TrackerBtcRate.resolve*` off-critical
path, pulsante recompute in Settings). Verifica manuale end-to-end: tabella
sopra (richiede rete + scenari con date d'acquisto reali).

## 46. Audit 2026-10 — Tor/trasporto + FFI tassi storici (2026-10-09)

> Esplora via Tor ora funziona: il client blocking (minreq) supporta solo
> proxy HTTP CONNECT, quindi BHODL avvia un bridge locale
> CONNECT→SOCKS5 su 127.0.0.1 (porta effimera, token di autenticazione,
> solo verso l'host:porta Esplora configurato, DNS risolto da Tor). TLS
> resta end-to-end. Proxy giù → errore visibile, mai fallback in chiaro.
> Tassi storici (CoinGecko/blockchain.info/Frankfurter) e prezzi spot
> passano dal proxy Tor con DNS remoto; UA generico. Le FFI dei tassi
> sono metodi di `RegistryHandle` (niente PIN in memoria, niente Argon2
> per chiamata). Endpoint Esplora per-rete.

| #     | Test                                   | Passi                                                                                         | Atteso                                                                                                                                         | Pass |
|-------|----------------------------------------|-----------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 46.1  | Sync via Tor — **automatizzato**       | `scripts/tor-tests.sh` (vedi sotto).                                                          | Full scan signet, tip height e `.onion` via daemon Tor reale coperti da `tor_live_tests`.                                                      | [auto] tor_live_tests + PriceFetcherLiveTorTest (nightly `Tor (live)`) |
| 46.1b | Timeout Esplora con Tor — **automatizzato** (unit test `esplora_timeout_is_larger_only_when_a_proxy_is_configured`) | Con proxy configurato il client Esplora usa 90 s (clearnet 30 s); il bridge apre il tunnel SOCKS entro 75 s. | mempool.space via Tor (stall di 20-35 s prima del connect) non manda più in timeout il full scan; clearnet invariato. | [auto] |
| 46.2  | Tor giù → errore visibile              | 1. Tor ON ma `tor` spento. 2. Tracker → Refresh.                    | Tracker mostra "Sync failed for N wallet(s) — showing cached data…". Nessuna richiesta in chiaro. | [ ]  |
| 46.3  | Nessun leak DNS prezzi                 | 1. Tor ON. 2. Monitor DNS locale (es. Wireshark filtro `dns`). 3. Attendi il refresh prezzo.  | Nessuna query DNS per `api.coingecko.com` / `mempool.space` dalla macchina locale.                                                             | [auto] PriceFetcherTorTest (SOCKS ATYP=0x03, remote DNS) + tor_live_tests (nightly) |
| 46.4  | Endpoint per rete                      | 1. Wallet signet: Settings → Esplora URL custom. 2. Apri un wallet mainnet / watch mainnet.   | Il wallet mainnet usa il default mainnet (o il SUO override), non l'URL signet. I watch wallet sincronizzano sulla propria rete.               | [auto] EsploraEndpointTest.`an override applies only to its own network` + SettingsNetworkSecurityTest.`a mainnet wallet shows the mainnet default and its own override only` |
| 46.5  | Link explorer per rete + Tor           | 1. Wallet mainnet → dettaglio tx → "Open in mempool.space". 2. Ripeti con Tor ON.              | URL `mempool.space/tx/<txid>` (signet: `/signet/tx`). Con Tor ON appare il dialog "Open outside Tor?" con "Copy txid" / "Open anyway".        | [auto] ExplorerLinkTest.`explorer base url follows the network` + `with Tor the link asks first and offers copying the txid` |
| 46.6  | Warning http:// con Tor ON             | 1. Tor ON. 2. Esplora URL = `http://example.com/api`.                                          | Warning "Plaintext HTTP endpoint — Tor does not encrypt the last hop…". `.onion` / localhost: nessun warning.                                   | [auto] SettingsNetworkSecurityTest.`plaintext http warning appears for remote hosts only` + ExplorerLinkTest.`plaintext http warning applies with Tor on too` |
| 46.7  | Idle lock non tenuto vivo dai job      | 1. Sblocca con device PIN. 2. Lascia l'app aperta senza input > 15 min con il tracker aperto. | L'app si blocca comunque (backfill / grafico NW / resolver non rinfrescano più il timer).                                                     | [auto] IdleLockTest (policy, 6 tests) — App-level 10 s watcher: manual |
| 46.8  | Recompute bypassa la cache             | 1. Settings → "Ricalcola cost basis BTC" con rete attiva.                                     | I tassi vengono riscaricati (non riletti dalla cache) e sovrascritti; tassi assurdi (≤0, NaN, enormi) rifiutati.                              | [ ]  |
| 46.9  | Isolamento stream Tor per wallet — **automatizzato** | Fake SOCKS5: greeting `[5,2,0,2]` (no-auth + user/pass), credenziali RFC 1929 = token casuale hex da 32 caratteri, password `x`; token stabile per scope nella sessione, diverso tra wallet / tassi / fee; proxy che sceglie `0x00` → si procede senza isolamento; credenziali rifiutate o `0xFF` → errore. Manuale (opzionale): Tor ON, due wallet, `GETINFO stream-status` / `circuit-status` via control port durante il refresh. | I due wallet usano circuiti diversi (streams con `SOCKS_USERNAME` diversi), tassi/prezzi un terzo; stesso wallet = stesso token. | [auto] tor:: (`socks5_connect_offers_userpass_and_sends_rfc1929_token`, `…proceeds_without_isolation…`, `…fails_when_creds_rejected…`, `isolation_tokens_are_stable_per_scope…`, `bridge_forwards_its_isolation_token_to_socks`, `ureq_isolated_proxy_url_carries_token_credentials`) + PriceFetcherTorTest (`…random per-session isolation token`, `…no-auth`, `…without a token…`); circuiti reali: manual |

### Verifiche automatizzate

```
cargo test -p bhodl-wallet-onchain -- tor:: btc_rate_history:: btc_at_time_backfill::
cargo test -p bhodl-uniffi -- rate_methods
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.prices.*" \
  --tests "io.bhodl.desktop.settings.EsploraEndpointTest" \
  --tests "io.bhodl.desktop.screens.ExplorerLinkTest" --tests "io.bhodl.desktop.wizard.DevicePinSessionTest"
```

Atteso: verde. Fake SOCKS5 server nei test Rust (bridge, ureq) e Kotlin
(PriceFetcher) verificano ATYP=0x03 (hostname non risolto localmente).

### Test live con un daemon Tor reale (automatizzati, 2026-10-10)

Sostituiscono il vecchio test manuale "sync con un daemon Tor reale"
(46.1) e coprono la parte di rete di 46.2/46.3. Girano solo su richiesta,
in locale o in CI lanciando a mano il workflow `Tor (live)` (`.github/workflows/tor.yml`),
mai in automatico: dipendono da Tor e da servizi pubblici.

```
scripts/tor-tests.sh            # attende il bootstrap di Tor, poi Rust + Kotlin
scripts/tor-tests.sh --rust     # cargo test -p bhodl-wallet-onchain --features tor-tests tor_live
scripts/tor-tests.sh --kotlin   # ./gradlew :apps:desktop:torTest
```

Proxy da `BHODL_TOR_SOCKS` (default `socks5h://127.0.0.1:9050`). Senza Tor
raggiungibile i test stampano `SKIP (no Tor): …` e passano, a meno che
`BHODL_TOR_REQUIRED` sia impostata (CI e lo script la impostano): allora
falliscono. Windows: Tor dentro WSL2 (`wsl -d Ubuntu-22.04 -- sudo apt-get
install -y tor`) è raggiungibile da Windows su `127.0.0.1:9050` grazie al
localhost forwarding di WSL2; tieni viva la distro durante i test.

| Test | Cosa verifica |
|------|---------------|
| `tor_live_esplora_tip_height_through_bridge` | Esplora signet (mempool.space) via bridge CONNECT→SOCKS5 + Tor: tip height plausibile. |
| `tor_live_esplora_full_wallet_sync_through_bridge` | Full scan del wallet BIP86 "abandon … about" su signet via Tor: storico trovato. |
| `tor_live_bridge_path_exits_through_tor` | Stesso builder Esplora (bridge + minreq) verso `check.torproject.org/api/ip`: `IsTor: true`. |
| `tor_live_rate_path_exits_through_tor` | Agent ureq dei tassi storici (stesso di `BtcRateRepo::with_proxy`): `IsTor: true`. |
| `tor_live_historical_rate_through_tor` | `BtcRateRepo::rate_at` (BTC/USD 2024-04-19, >365g → blockchain.info) via Tor entro tolleranza. |
| `tor_live_recent_rate_uses_coingecko_through_tor` | `BtcRateRepo::rate_at` (BTC/EUR 30 giorni fa, dentro la finestra keyless → CoinGecko `/history`) via Tor: valore plausibile. |
| `tor_live_*_fails_closed_with_dead_proxy` | Proxy su porta morta → errore, e un listener "canary" locale non riceve MAI connessioni dirette (bridge, ureq, sync wallet). |
| `tor_live_onion_esplora_blockstream` | Esplora `.onion` (Blockstream, signet) via bridge. |
| `tor_live_onion_esplora_mempool` | `#[ignore]`: l'onion di mempool.space accetta il circuito ma risponde vuoto (2026-10-10). |
| `PriceFetcherLiveTorTest` (Kotlin, `torTest`) | `fetchBtcPrice` con Tor ON → prezzo plausibile; fallback mempool.space via `torHttpGet`; `check.torproject.org` via lo stesso percorso SOCKS → `IsTor: true`; proxy morto → `null`/eccezione e nessuna connessione diretta. |

Bug trovato da questi test (fix `fix(onchain): accept 2xx fee-estimates …`):
mempool.space risponde a `/fee-estimates` con **203** e esplora-client
accettava solo 200, quindi la stima fee sul server di default falliva
sempre (con e senza Tor). Storico: la stima fee e il relativo test live
sono stati rimossi con il passaggio a watch-only (2026-10-23).

## 47. Scenari di sicurezza automatizzati (UI, 2026-10-10)

> Scenari nati con l'hardening dell'audit 2026-10 che prima non erano
> nel test book. Girano tutti in `./gradlew :apps:desktop:jvmTest` (quindi
> su ogni PR) con `FakeWalletPort`: niente rete, niente Argon2, wipe
> sempre su una directory temporanea.
>
> **2026-10-23 (watch-only)**: rimosse 47.1–47.8 (unlock e re-auth con il
> PIN del wallet), 47.12 (seed negli argomenti di navigazione) e 47.13
> (debug watch): quelle schermate non esistono più. Il lockout del device
> PIN è in §3.3.

| #     | Scenario                                                        | Atteso                                                                                              | Pass |
|-------|-----------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|------|
| 47.9  | Wipe: testo diverso da `DELETE`                                 | `delete`, `DELET`, `DELETE NOW`, `Delete`, `DE LETE` → bottone disabilitato; ` DELETE ` → abilitato. | [auto] SecretActionsUiTest.`wipe dialog stays disabled for anything but DELETE` |
| 47.10 | Wipe: Cancel dopo aver digitato `DELETE`                        | Nessun file cancellato, wallet ancora sbloccato.                                                    | [auto] SecretActionsUiTest.`wipe Cancel keeps every file and the unlocked wallet` |
| 47.11 | Wipe confermato                                                  | Directory dati vuota, storico PIN azzerato.                                                         | [auto] SecretActionsUiTest.`confirmed wipe empties the data dir and drops PIN history` |
| 47.14 | Tor OFF                                                         | Campo proxy nascosto, `EsploraEndpoint.currentProxy()` = null.                                      | [auto] SettingsNetworkSecurityTest.`turning Tor off hides the proxy field and drops the proxy` |
| 47.15 | `settings.json` corrotto                                        | Banner in cima all'app, Tor resta ON se lo era; "Dismiss" lo chiude.                               | [auto] SettingsNetworkSecurityTest.`corrupt settings file raises a dismissable banner and keeps Tor on` |
| 47.16 | Idle / minimise lock (policy)                                   | Lock a 15 min di inattività, dopo 60 s minimizzato, subito se la sessione device-PIN è scaduta; attività e restore azzerano i timer. | [auto] IdleLockTest (6 test) |
| 47.17 | Errori UI sanificati                                            | Path assoluti → `<path>`, max 200 caratteri, anche nei messaggi di Wallet settings.                 | [auto] ErrorMessagesTest (4 test) |

## 48. Audit 2026-10 — residui tracker/settings (2026-10-10)

> Chiusura dei residui dell'audit: mirror watch-only collegato in modo
> esplicito al signing wallet (registry schema v4), vendite Specific-ID
> con scelta dei lotti, endpoint Esplora per-rete reso visibile,
> re-auth device PIN con esito tipizzato, rifiuto del downgrade con
> messaggio chiaro.
>
> **2026-10-23 (watch-only)**: rimosse 48.1–48.3 (mirror dei signing
> wallet) e 48.9–48.10 (re-auth del device PIN per "Promote to signing").
> I signing wallet rimasti da build precedenti si gestiscono con
> LegacyCleanup (§17b).

| #     | Test                                         | Passi                                                                                                                                       | Atteso                                                                                                                                                                                         | Pass |
|-------|----------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 48.4  | Sell Specific-ID con scelta lotti             | 1. Investment con 3 lotti (10 @ €10 fee €10, 10 @ €20, 10 @ €30) → method Specific ID. 2. Sell: il campo quantità è sostituito da un input per lotto. 3. Lotto €30 = 4, lotto €10 = 2, prezzo €50, fee €6. Save. | Preview "(SPECIFIC_ID)" = +€152. Lotti: €10 → 8, €20 → 10, €30 → 6. Disposal: entrambi +€76 (fee ripartita 4/6 e 2/6, fee d'acquisto inclusa nel costo del lotto €10).                    | [ ]  |
| 48.5  | Specific-ID: selezione non valida             | 1. Come 48.4. 2. Inserisci su un lotto più unità di quelle rimaste (o un valore non numerico).                                               | Messaggio rosso "Lot #N: only X units remain." / "Invalid quantity…"; Save disabilitato. Lotti acquistati dopo la data di vendita non compaiono nel picker.                                        | [ ]  |
| 48.6  | Delete sell Specific-ID ripristina i lotti    | 1. Dopo 48.4, elimina il sell dalla sub-sezione "Sells".                                                                                    | I tre lotti tornano a 10 / 10 / 10; nessuna disposal residua. Il realized P&L torna a 0.                                                                                                        | [ ]  |
| 48.7  | Esplora URL: rete esplicita                   | 1. Wallet mainnet sbloccato. 2. Settings → sezione Esplora.                                                                                 | Riga "Applies to Mainnet only. Each network keeps its own endpoint…" e label campo "Mainnet Esplora URL" (IT: "Vale solo per Mainnet…", "URL Esplora Mainnet").                                  | [ ]  |
| 48.8  | Override modifica solo quella rete            | 1. Wallet signet: Esplora URL = `https://signet.example/api` → Save. 2. Apri un wallet mainnet → Settings: URL = `https://mainnet.example/api` → Save → Reset. 3. Torna al wallet signet. | Mainnet: dopo Reset torna a `mempool.space/api` (default). Signet: l'override `https://signet.example/api` è ancora attivo.                                                                    | [ ]  |
| 48.11 | Downgrade rifiutato con messaggio chiaro      | 1. Con una build di sviluppo, porta `PRAGMA user_version` del registry a 99 (es. `sqlcipher` con la chiave) oppure imposta `"version": 9` in `registry.kdf.json`. 2. Apri l'app → device PIN corretto. | "This data was written by a newer version of BHODL, which this version cannot open (downgrades are not supported). Update BHODL…". Il tentativo NON conta per il lockout.                       | [ ]  |
| 48.12 | Build vecchia su dati aggiornati (informativo) | 1. Sblocca una volta con questa build. 2. Avvia una build precedente all'audit 2026-10 sulla stessa data dir.                               | La build vecchia non apre i dati (descriptor KDF v2 / schema v4); documentato in handbook §6.1 e README. Recupero: data dir nuova e wallet riaggiunti da xpub.                                  | [ ]  |

### Verifiche automatizzate

```
cargo test -p bhodl-wallet-onchain --lib -- registry::tests::specific_id \
  registry::tests::lot_selection registry::tests::downgrade_refusal
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.SellDialogTest" \
  --tests "io.bhodl.desktop.screens.InvestmentLotsTest" \
  --tests "io.bhodl.desktop.screens.SettingsScreenTest" \
  --tests "io.bhodl.desktop.wizard.DevicePinSessionTest" \
  --tests "io.bhodl.desktop.wizard.SecurityAuditPinTest"
```

## 49. UX review 2026-10 — P0 numeri e layout del tracker (2026-10-10)

> Correzioni B1–B9 e B12–B14 di `docs/ux-review-2026-10.md` §2. Un solo
> calcolo del net worth (`TrackerNetWorth.kt`: wallet tracciati +
> investimenti + manual asset − passività) condiviso da hero HOME, ciambella
> HOME e Portfolio. Budget: **scelta = riproporzionare il budget alla
> lunghezza del periodo globale** (non bloccare la vista al mese di
> calendario): resta un solo selettore di periodo (Phase 9) e ogni pagina
> legge lo stesso intervallo. Formula: `budget × giorni_periodo / 30,4375`
> (settimanale: /7, annuale: /365,25); con "All" i giorni vanno dal primo
> evento a oggi. L'intestazione dice "Budget (90g, prorated)" e la cella
> mostra sotto l'importo mensile salvato ("1900000 sat / month").

Dati di esempio: 3 wallet tracciati ("Daily spending" registrato due
volte sullo stesso xpub,
Cold storage, Savings = 0,90880118 BTC), 4 investimenti (2 EUR, 2 USD),
6 manual asset (uno in CHF), 3 passività (0,181 BTC), budget mensili
(Rent 1.900.000 sat), affitto pagato 1×/mese.

| #     | Test                                             | Passi                                                                                                       | Atteso                                                                                                                                                                                         | Pass |
|-------|--------------------------------------------------|-------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 49.1  | Net worth hero = Portfolio (B1)                  | 1. HOME: leggi il numero grande. 2. Portfolio: riga NET WORTH in fondo allo stato patrimoniale (§51).      | Stesso valore ovunque (es. 2.46188819 BTC = asset 2.64288819 − passività 0.181). Il centro della ciambella HOME mostra lo stesso net worth con etichetta "net worth".                          | [auto] TrackerNetWorthTest.`B1 net worth subtracts liabilities from assets` |
| 49.2  | Totale investimenti in BTC su tutte le righe (B2) | 1. Portfolio → Investments, riga "Total".                                                                   | BTC VALUE = somma di tutte le righe (≈ ₿0.2421, come "Investments (4)" sopra), non solo quelle EUR. Le righe fiat sotto coprono solo la valuta della dashboard e sotto "Total" compare "fiat: EUR rows only". QTY vuota. | [auto] InvestmentsTotalsTest.`B2 BTC value total covers every row at its own currency rate` |
| 49.3  | Income breakdown senza categorie di spesa (B3)   | 1. Budget → "Income breakdown".                                                                              | Solo categorie di entrata (Salary, Freelance, Gifts…). Nessuna riga Rent/Dining/Groceries a −100%. I trasferimenti send_to_self non compaiono né in entrate né in uscite.                       | [auto] BudgetBreakdownCardTest.`B3 spending budgets do not appear in the income table` |
| 49.4  | Budget riproporzionato al periodo (B4)           | 1. Periodo globale 90g. 2. Budget → "Expense breakdown", riga Rent. 3. Passa a 30g e poi a All.            | 90g: intestazione "Budget (90g, prorated)", budget ≈ 5.618.070 sat (sotto: "1900000 sat / month"), Rent ≈ 101% (prima 298%). 30g: ≈ 1.872.690 sat. All: proporzionato ai giorni dal primo evento. | [auto] BudgetBreakdownCardTest.`B4 a monthly budget is prorated to a 90 day window` |
| 49.5  | Fiat sotto il net worth uguale in light e dark (B5) | 1. HOME in light: leggi la riga fiat sotto il numero grande. 2. Passa a dark (o aspetta il caricamento dello storico prezzi). | Stesso valore (net worth × prezzo spot, es. ≈ €143.8k), mai "€0.00". La coda "+€…" accanto al delta compare solo con lo storico NW ricostruito, altrimenti è nascosta (mai "+€0.00"). Causa: race di caricamento, non il tema. | [auto] TrackerHomeAggregationsTest.`B5 hero fiat is net worth times the spot rate, never a zero tx reconstruction` |
| 49.6  | Importi di passività e bill al loro posto (B6)   | 1. Portfolio → stato patrimoniale, sezione Liabilities, riga Mortgage share. 2. Budget → Bills tracker, riga Rent / Internet. | La colonna importo mostra sempre l'importo ("−15,400,000 sat", "1890000 sat"). Nei bill la nota libera ("Apartment – rent") è sotto il nome, in grigio. Il campo del dialog si chiama "Note (optional)". | [ ]  |
| 49.7  | Valuta CHF e ISO generiche (B7)                  | 1. Portfolio → Manual assets, riga "Watch collection" (CHF).                                                | "CHF 3,200.00" (mai "F3,200.00"). Codici senza simbolo dedicato → "SEK 1.00"; segno prima del simbolo ("-€12.50").                                                                             | [auto] AmountFormatTest.`B7 CHF renders with its code, never a bare F` + MoneyRenderTest.`B7 CHF fiat line renders as CHF code not a bare F` |
| 49.8  | Conteggio wallet coerente (B8)                   | 1. Portfolio: sezione "Bitcoin wallets (n)". 2. HOME: tabella wallet e footer "n wallets".                             | Stesso n: due righe del registry sullo stesso xpub di account contano come un solo wallet; i wallet address-list non si fondono mai. Escludere un wallet dai totali lo toglie da conteggio e totale BTC. | [auto] TrackerNetWorthTest.`B8 two registry rows on the same account xpub count as ONE tracked wallet` + `B8 address-list wallets never merge, even with equal descriptors` + `B8 excluded wallets drop out of the tracked count and total` |
| 49.9  | P&L BTC con tasso storico disponibile (B9)       | 1. Aggiungi un lotto con la rete spenta (tasso non risolto → "no rate"). 2. Riaccendi la rete, aspetta il refresh (≤ 2 min) o premi l'icona refresh. | COST mostra la riga BTC e P&L BTC una percentuale (non "—"). Il recupero gira DOPO che il refresh ha pubblicato i dati (mai "Loading tracker…" bloccato a rete giù): max 12 lookup per passata, 8 s ciascuno, solo nei refresh di rete; il tasso viene poi salvato nel registry. "no rate" resta solo se il tasso per quella data/valuta non esiste davvero.        | [auto] InvestmentRateHealingTest.`B9 missing lot rate is filled from the purchase-date rate, making BTC P&L computable` — manual: persistenza dopo riavvio |
| 49.10 | Un solo selettore di periodo (B12)               | 1. HOME: guarda la card NET WORTH. 2. Cambia il periodo globale (7g → 1a).                                  | Nessun chip 7D/30D/… dentro la card. Grafico, delta "(… • 1Y)" e KPI seguono il selettore globale.                                                                                              | [ ]  |
| 49.11 | Refresh e Add nell'intestazione wallet (B13)     | 1. HOME: intestazione "WALLETS". 2. Passa sopra l'icona ↻. 3. Clicca "+ Add".                               | Icona refresh con tooltip "Refresh balances" (disabilitata durante il refresh). "+ Add" apre il menu "Watch-only wallet (xpub)" / "New signing wallet". Nessun testo libero né bottone a tutta larghezza sotto la tabella; i KPI seguono subito la tabella. | [ ]  |
| 49.12 | Testi leggibili nei grafici (B14)                | 1. HOME: centro della ciambella. 2. Reports: Sankey (colonna "Totale") e ciambella "By category" a finestra stretta. 3. KPI "Net Savings". | Centro ciambella dentro il foro. "Totale" e il suo importo sopra la barra centrale, non coperti dai flussi. Ciambella stretta: importo compatto ("25.4M sat") con tooltip completo. KPI: "Inflow > outflow"; testi troppo lunghi con "…" e tooltip. | [ ]  |
| 49.13 | COST in BTC = somma per lotto (review)          | 1. Investimento con 2 lotti da €100: uno a BTC 10k, uno a 50k.                                              | COST mostra 0.012 BTC (0.01 + 0.002), non 0.00667 (media dei tassi). Stesso valore usato da P&L BTC e dalla riga Total. Un lotto senza tasso → "no rate" sull'intero costo. | [auto] InvestmentsTotalsTest.`BTC cost sums each lot at its own purchase rate` + InvestmentsCardBitcoinLensTest.`cost cell BTC line is the per-lot sum, not an averaged rate` |
| 49.14 | Goal "% del NW" sul patrimonio netto (review)   | 1. Passività 0,2 BTC, asset 1 BTC. 2. Goal "30% NW".                                                         | Target = 30% di 0,8 BTC (240.000 sat), non del lordo. Net worth negativo → base 0. Investimenti/manual asset valutati al tasso live (cache solo senza tasso). | [auto] TrackerNetWorthTest.`percent-of-net-worth goal base subtracts liabilities and floors at zero` |
| 49.15 | Arrotondamento fiat (review)                    | 1. Valore €0,29 / €1,005 / −€0,004.                                                                          | "€0.29", "€1.01" (HALF_UP), "€0.00" senza segno meno.                                                                                                                     | [auto] AmountFormatTest.`rounds half up to the nearest cent` + `tiny negatives that round to zero carry no minus sign` |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.TrackerNetWorthTest" \
  --tests "io.bhodl.desktop.screens.TrackerHomeAggregationsTest" \
  --tests "io.bhodl.desktop.screens.BudgetBreakdownCardTest" \
  --tests "io.bhodl.desktop.screens.InvestmentsTotalsTest" \
  --tests "io.bhodl.desktop.data.InvestmentRateHealingTest"   --tests "io.bhodl.desktop.data.TrackerCacheRateHealingTest"   --tests "io.bhodl.desktop.screens.InvestmentsCardBitcoinLensTest" \
  --tests "io.bhodl.desktop.ui.AmountFormatTest" \
  --tests "io.bhodl.desktop.ui.money.MoneyRenderTest"
```

## 50. UX review 2026-10 — Wallet a sotto-tab, Send/Receive/stati tx (2026-10-10)

> Raccomandazioni 5–8 di `docs/ux-review-2026-10.md` §4 (decisione di progetto:
> sotto-tab in stile Sparrow, tutta larghezza). Il wallet è un
> workspace con header (nome, saldo nell'unità scelta + fiat operativo,
> stato sync, Sync / Contatti / Blocca) e sotto-tab **Transazioni ·
> Ricevi · Indirizzi · UTXO · Impostazioni wallet**. Mappa dei vecchi
> pulsanti: Receive → tab; History → tab Transazioni (pannello di
> dettaglio laterale; la route `TransactionDetails` resta per i deep link);
> Address book → azione "Contatti" nell'header; Settings → tab Impostazioni
> wallet (le preferenze dell'app restano in Settings nella rail, con un
> rimando); Logout → "Blocca". Nuove FFI: `list_addresses`,
> `set_address_label`, `chain_tip_height`.
>
> **2026-10-23 (watch-only)**: il tab Invia non esiste più. Rimosse 50.7
> (Speed up), 50.8 (tx sostituita), 50.9–50.11 (preset fee, fee
> personalizzata, diagramma della revisione), 50.15 (freeze e coin
> control: il tab UTXO è in sola lettura, §4c) e 50.17 (watch-only come
> caso a parte: ora tutti i wallet lo sono).

Dati di esempio: wallet watch-only signet "Daily spending" (15 tx, 2 in
attesa, 6 UTXO), tip sincronizzato, prezzo EUR in cache.

| #     | Test                                              | Passi                                                                                                   | Atteso                                                                                                                                                                                                                       | Pass |
|-------|---------------------------------------------------|---------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 50.1  | Workspace a tutta larghezza con sotto-tab         | 1. Apri un wallet dal Tracker. 2. Clicca ogni sotto-tab. 3. Ripeti a finestra 1024×720.                 | Header in alto (nome, saldo es. "4,230,118 sat", "≈ €2,471.20 · Synced hh:mm", Sync, Contatti, Blocca), tab con sottolineatura arancione, footer di stato. Nessuna colonna centrata: tabelle e form usano tutta la larghezza. | [auto] DashboardScreenTest.`sub-tabs switch between Transactions, Receive, Addresses, UTXOs and Wallet settings` |
| 50.2  | Vecchi ingressi raggiungibili                     | 1. Contatti nell'header. 2. Blocca. 3. Settings (rail) → "Open wallet settings".                         | Contatti apre la rubrica; Blocca chiude il wallet e torna al gate del PIN dispositivo; Settings mostra il rimando a Wallet settings e il pulsante apre la tab Impostazioni wallet.                | [auto] DashboardScreenTest.`Contacts header action fires onAddressBookClicked` + `Lock header action fires onLockClicked` + SettingsScreenTest.`wallet-scoped sections moved to Wallet settings, with a pointer here` |
| 50.3  | Tabella transazioni densa, ordinabile, filtrabile | 1. Tab Transazioni. 2. Clicca le intestazioni DATE / AMOUNT / CONF. 3. Chip "Incoming", "Pending". 4. Cerca "salary". | Righe alte 32 px (24 px con Ultra-dense), importi allineati a destra in cifre tabulari, ▲/▼ sulla colonna attiva. Filtri e ricerca (etichetta, categoria, txid) restringono la lista; contatore "n of m transactions". | [auto] HistoryScreenTest.`filter chips and search narrow the table` + WalletTxModelTest (sort/filter) |
| 50.4  | Stati: In attesa → In conferma → Confermata       | 1. Guarda la colonna STATUS di una tx in mempool, di una con 1–5 conferme e di una vecchia.              | "Pending" (ambra), "Confirming n/6", nessun badge da 6 conferme in su. Colonna CONF. con il numero di conferme ("0" per le pendenti). Tip non sincronizzato o indietro: "Confirming" senza numero e CONF. "—", mai "Confermata".                                                                        | [auto] HistoryScreenTest.`status chips follow Pending, Confirming n of 6 and no badge once final` + `a mined tx with an unknown tip reads Confirming, never final` + WalletTxModelTest |
| 50.5  | Colori degli importi (BDG)                        | 1. Confronta righe in entrata e in uscita con la pagina Activity del tracker.                           | Entrate "+1,250,000 sat" nel verde delle entrate del tracker; uscite "−38,500 sat" in colore neutro. Il rosso compare solo per errori (sync fallita).                                                                     | [auto] HistoryScreenTest.`incoming amounts carry a plus sign, outgoing a minus` |
| 50.6  | Pannello di dettaglio laterale                    | 1. Clicca una riga. 2. Modifica l'etichetta e salva. 3. Chiudi.                                          | Il pannello si apre a destra senza cambiare pagina: stato, importo, fee, conferme, categoria, txid + Copia + explorer, editor etichetta BIP-329. Salvando, la riga aggiorna l'etichetta. Chiudi lo nasconde.             | [auto] HistoryScreenTest.`clicking a row fires onTxClicked and opens the detail pane` |
| 50.12 | Ricevi: etichetta e categoria prima               | 1. Tab Ricevi. 2. Etichetta "Invoice #44", categoria "Freelance", importo. 3. "Show address". 4. Ricevi un pagamento e sincronizza. | Nessun indirizzo viene generato finché non continui ("Skip, show address" se non scrivi nulla); poi viene mostrato il primo indirizzo NON usato (riaprire la tab o modificare l'etichetta non consumano indici; solo "New address" ne rivela uno nuovo). QR + indirizzo a blocchi di 4; l'URI BIP-21 NON contiene l'etichetta privata, solo l'eventuale "Message for the payer". Dopo il sync la tx in entrata (solo saldo netto positivo) ha già etichetta e categoria, anche nel tracker. Un'etichetta cancellata a mano non viene ri-applicata; un auto-trasferimento in uscita verso l'indirizzo etichettato non viene etichettato. | [auto] ReceiveScreenTest.`label and category are attached to the address before it is shown` + `editing the request relabels the same address without revealing another` + `re-opening the tab hands out the same unused address` + `only the explicit payer message reaches the URI, percent-encoded` + DashboardScreenTest.`every sync copies receive labels through the core` — manuale: arrivo reale su signet |
| 50.13 | Avviso di riuso dell'indirizzo                    | 1. Tab Indirizzi → "Show" su un indirizzo "Used".                                                        | Si apre Ricevi su quell'indirizzo con l'avviso "⚠ This address has already received funds…". Su un indirizzo nuovo nessun avviso.                                                                                            | [auto] ReceiveScreenTest.`a previously used address shows the reuse warning` + DashboardScreenTest.`Addresses tab lists used and unused, Show opens Receive with a reuse warning` |
| 50.14 | Tab Indirizzi                                     | 1. Tab Indirizzi, chip Receive / Change.                                                                 | Indici rivelati (più recente in alto), indirizzo a blocchi di 4, etichetta · categoria, stato Used / Unused.                                                                                                              | [auto] DashboardScreenTest.`Addresses tab lists used and unused, Show opens Receive with a reuse warning` |
| 50.16 | Impostazioni wallet                               | 1. Tab Impostazioni wallet.                                                                              | Rinomina, etichette BIP-329 (import / export), descrittore watch-only (mostrato solo su richiesta), "Remove this wallet" con doppio tap. Nessun PIN del wallet: c'è solo il PIN dispositivo.            | [auto] SecretActionsUiTest.`watch-only descriptor is exported only on request` + `remove this wallet needs a second tap` |
| 50.18 | Italiano                                          | 1. Lingua IT. 2. Passa tutte le sotto-tab.                                                               | Tab "Transazioni · Ricevi · Indirizzi · UTXO · Impostazioni wallet", intestazioni e pulsanti tradotti, nessun testo tagliato a 1024×720.                                                                  | [ ]  |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.WalletTxModelTest" \
  --tests "io.bhodl.desktop.screens.SecretActionsUiTest" \
  --tests "io.bhodl.desktop.screens.HistoryScreenTest" \
  --tests "io.bhodl.desktop.screens.DashboardScreenTest" \
  --tests "io.bhodl.desktop.screens.ReceiveScreenTest"
```

## 51. UX review 2026-10 — P1 tracker: stato patrimoniale, scelta lotti, regole, budget (2026-10-10)

> Raccomandazioni 9–12 di `docs/ux-review-2026-10.md` §4.
> **Portfolio** = stato patrimoniale alla Kubera (foglio → sezione → riga):
> colonne Valore (unità bitcoin scelta), Variazione sul periodo globale,
> % del net worth, Aggiornato. Subtotali e riga "Net worth" vengono da
> `TrackerNetWorth` (stesso numero dell'hero HOME). Variazione per riga:
> wallet = flussi on-chain nella finestra; investimenti e manual asset =
> valore fiat di oggi al tasso BTC di inizio periodo (la stessa
> approssimazione dello storico NW); passività statiche (0). Su finestre
> strette le colonne spariscono in ordine: Aggiornato → Tipo → % NW →
> Variazione; Nome e Valore restano sempre. La vecchia card "Net worth
> snapshot" è stata assorbita dal foglio.
> **Vendita**: FIFO, HIFO e ID specifico affiancati, ciascuno con i lotti
> consumati e il P&L realizzato (fiat + BTC). Default = metodo
> dell'investimento; scegliere un altro metodo registra la vendita con i
> lotti mostrati (`record_sell_with_lots`, ora accettato anche per
> investimenti FIFO/HIFO). HIFO ordina per prezzo + commissione per unità,
> come il registry. Collegamento lotto↔UTXO: futuro.
> **Regole**: dopo un cambio di categoria compare "Always do this?" con
> anteprima del numero di transazioni esistenti senza categoria a cui si
> applica; le regole "payee" ora confrontano l'etichetta del wallet;
> l'ordine è esplicito (prima regola che corrisponde vince, ▲/▼ nella lista,
> niente più slider di priorità).
> **Budget**: chip di stato sempre con testo e nota "perché il budget in
> sats si è mosso" (prezzo vs spesa, mai smussato). La proporzione al
> periodo di §49.4 resta.

Dati di esempio: come §49 (periodo globale 90g, prezzo BTC/EUR in
movimento nello storico).

| #     | Test                                             | Passi                                                                                                       | Atteso                                                                                                                                                                                         | Pass |
|-------|--------------------------------------------------|-------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 51.1  | Stato patrimoniale: sezioni e subtotali          | 1. Portfolio. 2. Confronta "Net worth" in fondo al foglio con l'hero HOME.                                  | Sezioni Bitcoin wallets / Investments / Manual assets / Liabilities con il numero di righe e il subtotale sulla riga di sezione; passività in negativo. Net worth = hero HOME. Righe alte come la densità scelta (32 / 24 px). | [auto] PortfolioSheetTest.`subtotals and net worth come from TrackerNetWorth` + `rows add up to the live subtotals` |
| 51.2  | Variazione segue il periodo globale              | 1. Portfolio con periodo 90g. 2. Passa a 7g, poi a All.                                                    | L'intestazione diventa "Change · 7g"; i valori cambiano. Wallet: entrate − uscite nella finestra (stessa tx su signing e mirror contata una volta). All: "—". Segno +/− esplicito, non solo colore. | [auto] PortfolioSheetTest.`wallet change is the signed flow inside the window, deduped by txid` + `all-time period has no change values` |
| 51.3  | Variazione di investimenti e manual asset        | 1. Portfolio, 30g. 2. Guarda un ETF comprato prima della finestra e un bene aggiunto ieri.                  | ETF: valore di oggi meno lo stesso valore fiat al tasso BTC di 30 giorni fa (BTC salito → variazione negativa in sats). Bene nuovo: variazione = intero valore. Senza tasso storico: "—" (mai stimato) e niente totale di sezione. Subito dopo un cambio di periodo: "—" finché non arrivano i tassi del nuovo giorno di inizio (mai i tassi del periodo precedente). Passività: 0. | [auto] PortfolioSheetTest.`investment change uses today's fiat value at the start-of-period BTC rate` + `manual asset bought inside the window changes by its whole value` + `missing start rate leaves the change unknown, never guessed` + `stale rates from the previous period leave the change unknown` |
| 51.4  | % del net worth                                  | 1. Portfolio: colonna "% of NW".                                                                            | Percentuale con segno (passività negative); riga Net worth 100%. Con net worth ≤ 0: "—".                                                                                                       | [auto] PortfolioSheetTest.`percent of net worth is signed and relative to the net worth` |
| 51.5  | Colonne a finestra stretta                       | 1. Finestra 1440 px. 2. Riduci a 1024 px. 3. Riduci a ~800 px.                                              | 1440 e 1024: tutte le colonne essenziali (Nome, Valore, Variazione, % NW). Più stretto: spariscono prima "Updated", poi "Type", poi "% of NW", poi "Change"; Nome e Valore mai.                  | [auto] PortfolioSheetTest.`a 1024 px window keeps the essentials` + `columns give way in priority order` |
| 51.6  | Vendita: tre metodi a confronto                  | 1. Portfolio → Vanguard (FIFO) → Sell. 2. Quantità 30, prezzo 135.                                          | Sezione "Lot selection: compare methods" con FIFO (selezionato, "configured"), HIFO e Specific ID: per ognuno i lotti consumati (#id · data · quantità @ costo unitario) e "Realized P&L" in € e in bitcoin ("no rate" se manca un tasso). Specific ID: "Select to pick the lots yourself." | [auto] LotMethodComparisonTest.`FIFO consumes the oldest lots first` + `HIFO consumes the highest unit cost first` + SellDialogTest.`comparison shows the three methods and picking HIFO books its lots` |
| 51.7  | Vendita con un metodo diverso da quello configurato | 1. Come 51.6. 2. Clicca la colonna HIFO. 3. Record sell. 4. Riapri l'investimento.                       | Compare la nota "Differs from this investment's method (FIFO)…". La vendita consuma i lotti HIFO mostrati (lotto più caro). Il metodo dell'investimento resta FIFO. | [auto] LotMethodComparisonTest.`overriding the configured method sends that method's lots` + Rust `lot_selection_overrides_the_method_of_a_fifo_investment` |
| 51.8  | P&L di confronto = P&L registrato                | 1. Annota il P&L della colonna scelta. 2. Registra. 3. Espandi l'investimento → vendite.                   | Il P&L realizzato registrato coincide con quello mostrato (commissioni ripartite pro-rata, lotti comprati dopo la data di vendita esclusi, HIFO su prezzo + commissione per unità). | [auto] LotMethodComparisonTest.`the comparison agrees with the single-method preview` + `HIFO ranks by price plus the purchase fee per unit, like the registry` |
| 51.9  | "Always do this?" dopo un cambio di categoria    | 1. Transactions: riga senza categoria del wallet "Daily spending". 2. Categoria → "Groceries" → Save.      | Si apre il pannello "Always do this?" con "Always categorise transactions of wallet "Daily spending" as "Groceries"?", scelta Wallet / Exact amount e "Existing uncategorised transactions it applies to: N". Cambiando criterio N si aggiorna. La stessa tx vista dal mirror del wallet non è contata. "Exact amount" vale solo nella direzione della tx modificata (una spesa da 21.000 sat non cattura un rimborso da 21.000 sat). Nessun pannello se una regola attiva fa già la stessa cosa. | [auto] RuleSuggestionsTest.`preview counts uncategorised matching transactions except the edited one` + `preview excludes the mirror row of the edited transaction` + `amount preview ignores events in the other direction` + `offer only for a real change no enabled rule already covers` |
| 51.10 | Creare la regola dalla modifica                  | 1. Come 51.9, lascia "Also categorise them now" spuntato → Create rule. 2. Budget → Auto-categorization.   | La regola appare in cima alla lista (1.) e le N transazioni senza categoria ricevono "Groceries"; le categorie messe a mano non cambiano. Con la casella tolta la regola vale solo per le prossime. | [auto] RuleSuggestionsTest.`a new rule goes on top` + `preview counts a txid seen by two wallets once but applies to both rows` |
| 51.11 | Ordine esplicito delle regole                    | 1. Budget → Auto-categorization. 2. Premi ▼ sulla prima regola. 3. Reapply.                                | Righe numerate 1., 2., …; testo "Rules run top to bottom: the first match wins.". La regola scende di un posto; a parità di condizioni vince quella più in alto. ▲ disabilitato sulla prima, ▼ sull'ultima. Durante il salvataggio tutte le frecce sono disabilitate (un doppio clic non annulla lo spostamento); il nuovo ordine è scritto in una sola transazione del registry. Nessuno slider "Priority" nel pannello di modifica. | [auto] RuleSuggestionsTest.`first match in evaluation order wins` + `move returns the full new order for one atomic reorder` + `repeating a move on the stale list writes the same order, never an undo` + Rust `reorder_rules_refuses_a_stale_order_and_writes_nothing` |
| 51.12 | Chip di stato del budget                         | 1. Budget → Expense / Income breakdown.                                                                     | Colonna "Status" con testo: "Under budget" (< 80%), "At risk" (80–100%, anche esattamente 100%), "Over" (> 100%); entrate: "Reached" / "At risk" / "Behind". Colore coerente con la barra, mai solo colore. | [auto] BudgetInsightsTest.`expense status thresholds` + `income status thresholds` + `status compares against the prorated budget, not the monthly one` |
| 51.13 | Perché il budget in sats si è mosso              | 1. Budget, periodo 30g o 90g, valuta fiat attiva. 2. Riga con budget (es. Groceries).                       | Sotto il titolo di Expense: nota "Budgets are in sats…". Sotto la riga (se la parte prezzo è ≥ 1%): "vs previous 90g: +X sat · price +Y sat (BTC −Z%) · spending +W sat"; X = Y + W. Ogni transazione valutata al prezzo del suo giorno, nessuna media mobile. Periodo All o nessuna valuta: nessuna nota per riga. | [auto] BudgetInsightsTest.`same fiat spending after a BTC drop is all price effect` + `mixed move splits and always adds up` + `each event is valued at its own day, never an average` |
| 51.14 | Stringhe in italiano                             | 1. Settings → Language → Italiano. 2. Portfolio, vendita, pannello regola, Budget.                         | "STATO PATRIMONIALE", "Variazione · 90g", "Scelta dei lotti: confronto tra metodi", "Farlo sempre?", "Sotto budget / A rischio / Sforato", nota sui sats in italiano. | [ ]  |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.PortfolioSheetTest" \
  --tests "io.bhodl.desktop.screens.LotMethodComparisonTest" \
  --tests "io.bhodl.desktop.screens.SellDialogTest" \
  --tests "io.bhodl.desktop.screens.RuleSuggestionsTest" \
  --tests "io.bhodl.desktop.screens.RulesEngineTest" \
  --tests "io.bhodl.desktop.screens.BudgetInsightsTest"
cargo test -p bhodl-wallet-onchain lot_selection_overrides_the_method_of_a_fifo_investment
cargo test -p bhodl-wallet-onchain reorder_rules
```

## 52. UX review 2026-10 — Importi unificati e i18n completa (2026-10-11)

> Raccomandazioni 2–3 di `docs/ux-review-2026-10.md` §4 (P0).
> **Importi**: ogni importo bitcoin passa da `BitcoinAmountFormat`
> (`rememberAmountFormatter` / `displayAmount` / `Money`): unità di
> Settings (Sats / BTC / Auto, BIP177), separatori della lingua, cifre
> tabulari. Nelle **tabelle** l'Auto è risolto una volta per colonna (BTC
> se un valore della colonna arriva a 1 BTC, altrimenti sats: mai sats e
> BTC mescolati, decimali fissi) e l'unità sta nell'intestazione
> ("Importo (sat)", "Entrate (sat)", "VALORE (BTC)") con numeri nudi
> nelle celle (Transazioni wallet, UTXO, Report, foglio Portfolio, tabella
> wallet); dove le celle sono doppie BTC/fiat (Investimenti, Asset
> manuali, Budget) l'unità resta nella cella ma è la stessa per tutta la
> tabella. Grafici: forma compatta ("4,2M sat", "0,0423 BTC"), un'unità
> per asse. Export CSV invariato (sat interi). Importi **fiat** con i
> separatori della lingua e, in italiano, il simbolo dopo il numero come
> `NumberFormat` it-IT ("143.823,51 €"; inglese "€143,823.51"); le valute
> senza simbolo a un carattere tengono il codice ISO davanti ("CHF
> 3.200,00"); arrotondamento half-up.
> **i18n**: tutte le stringhe visibili sono in `composeResources`
> (`strings*.xml`, EN + IT con le stesse chiavi). Chip periodo "7D/30D/
> 90D/1Y/All" ↔ "7g/30g/90g/1a/Tutto"; "Last sync" ↔ "Ultimo sync".

| #     | Test                                    | Passi                                                                                                   | Atteso                                                                                                                                                                                         | Pass |
|-------|-----------------------------------------|---------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 52.1  | Unità Sats ovunque                      | 1. Settings → Unità bitcoin = Sats. 2. Wallet (Transazioni, UTXO), Tracker (Home, Portfolio, Budget, Report). | Tutti gli importi in sats con separatori ("4,230,118 sat" in EN, "4.230.118 sat" in IT); mai "4230118 sat"; BTC solo nelle etichette "Δ BTC" / "P&L BTC". | [auto] AmountStringGuardTest + BitcoinAmountFormatTest |
| 52.2  | Unità BTC ovunque                       | 1. Unità = BTC. 2. Stesse schermate.                                                                    | Importi con 8 decimali ("0.04230118 BTC"); KPI "OPP COST", ciambella del NW, entrate/uscite, Sankey e assi dei grafici seguono l'unità.                                                         | [auto] KpiStripV7OppCostTest.`OPP_COST follows the BTC unit setting` |
| 52.3  | Auto: una sola unità per colonna        | 1. Unità = Auto. 2. Report → "Per wallet" con un wallet ≥ 1 BTC e uno piccolo. 3. Portfolio.            | In una colonna tutte le celle in BTC (8 decimali) se almeno un valore ≥ 1 BTC, altrimenti tutte in sats; mai righe miste. Intestazione "Saldo (BTC)" / "Saldo (sat)".                          | [auto] BitcoinAmountFormatTest.`auto resolves once per column, never mixing sats and BTC` |
| 52.4  | Unità nell'intestazione                 | 1. Wallet → Transazioni. 2. Wallet → UTXO. 3. Tracker → Report.                                         | "Importo (sat)", "Entrate (sat)" ecc.; celle con solo numero e segno ("+12,500", "−3,000"), a destra; zero senza segno (mai "−0"). Con BIP177 l'intestazione mostra "(₿)".                   | [auto] HistoryScreenTest.`incoming amounts carry a plus sign, outgoing a minus` + BitcoinAmountFormatTest.`bare cells keep fixed decimals and drop the unit` |
| 52.6  | Nessun importo costruito a mano         | —                                                                                                       | Fallisce se un file Kotlin costruisce "$x sat", "${y} BTC", "%,d sat" o "₿$x" fuori dai formatter (eccezioni motivate: nome di regola salvato, BIP21, CSV).                                    | [auto] AmountStringGuardTest |
| 52.7  | App in inglese senza italiano           | 1. Language → English. 2. Home, Transazioni, Budget, Portfolio, Report, palette Ctrl+K, Wallet (tutti i tab), Settings. | Nessuna parola italiana: "Last sync", chip "7D/30D/90D/1Y/All", "Category", "Last", "In/Out", Sankey "Income" / "Expenses + Savings", palette "Go to …", "Never synced". | [auto] StringsLanguageTest.`EN values contain no Italian words` + HardcodedUiStringScanTest |
| 52.8  | App in italiano senza inglese           | 1. Language → Italiano. 2. Schermate di 52.7, più Welcome / PIN dispositivo / Add watch / LegacyCleanup. | Tutto in italiano (esclusi nomi propri e termini tecnici: BTC, UTXO, xpub, Tor); separatori italiani negli importi bitcoin ("4.230.118 sat", "0,04230118 BTC").        | [auto] StringsLanguageTest.`IT values contain no English words` + `long IT values are translated, not copied from EN` |
| 52.9  | Chiavi EN = chiavi IT                   | —                                                                                                       | Stesso insieme di chiavi in `values/` e `values-it/`, nessun valore vuoto o duplicato, segnaposto %1$s / %2$d identici e posizionali.                                                          | [auto] StringsParityTest |
| 52.10 | Nessun testo hardcoded nella UI         | —                                                                                                       | Fallisce su `Text("…")`, `label =`, `placeholder =`, `contentDescription =`, `title =` con parole che non siano token tecnici (eccezioni motivate: schermata debug, nomi di wallet salvati).    | [auto] HardcodedUiStringScanTest |
| 52.11 | Importi fiat nella lingua dell'app       | 1. Valuta fiat = EUR. 2. Language → Italiano: Home (hero), Wallet (saldo), Portfolio (Investimenti, totali), Vendita. 3. Language → English. 4. Valuta = CHF. | IT: "143.823,51 €", "-12,50 €" (simbolo dopo, spazio non separabile, come `NumberFormat` it-IT); EN: "€143,823.51", "-€12.50". CHF in entrambe: "CHF 3.200,00" / "CHF 3,200.00". Centesimi arrotondati half-up (1,005 → 1,01). Export CSV invariato. | [auto] AmountFormatTest.`Italian follows NumberFormat it_IT - glyph after the number` + `English puts the glyph before the number` + `ISO-code currencies keep the code prefix in every language` + `formatFiat uses the locale separators` |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.i18n.*" \
  --tests "io.bhodl.desktop.ui.money.*" \
  --tests "io.bhodl.desktop.screens.HistoryScreenTest" \
  --tests "io.bhodl.desktop.screens.KpiStripV7OppCostTest"
```

## 53. UX review 2026-10 — Privacy mode, Security Hub, accessibilità, unità, mobile readiness (2026-10-11)

> Raccomandazioni 13–17 di `docs/ux-review-2026-10.md` §4.
> **Privacy mode** globale: Ctrl+Shift+H (Cmd+Shift+H su macOS), icona occhio
> nella barra di stato, palette comandi, Settings › Privacy mode. La
> maschera è dentro i formatter centrali (`BitcoinAmountFormat`, `Money`,
> `formatFiat*`, `formatBtcLike`, `shortSat`, tick dello scatter, assi dei
> grafici): larghezza fissa `•••••`, nessun segno, unità visibile
> (`••••• sat`, `€•••••`). Eccezioni volute: campi di input, URI BIP21, esempi
> fissi in Settings. "Start with balances hidden" arma la modalità
> all'avvio e a ogni blocco (un blocco non la spegne mai).
> **Security Hub**: righe PIN dispositivo, blocco automatico, Tor /
> clearnet, wallet watch-only, export etichette BIP-329; punteggio "N di M
> controlli OK" (le righe Info non contano). In `settings.json` solo date.
> (Fino al 2026-10-23 c'erano anche le righe backup frase / file di backup
> cifrato e i promemoria "backup non verificato": rimossi con il passaggio
> a watch-only, insieme alle righe 53.5, 53.9, 53.11 e 53.23.)
> **Accessibilità**: vista tabella per ogni grafico, descrizioni
> accessibili, righe di tabella raggiungibili da tastiera con contorno di
> focus diverso dalla selezione, altezze di riga dalla densità ovunque.
> **Mobile readiness**: priorità di colonna per ogni tabella, finestra
> minima 960×640 (apertura 1280×800), astrazione di navigazione rail ↔
> bottom bar (desktop sempre rail).

Dati di esempio: wallet watch-only aperto con qualche transazione, almeno
un modulo tracker attivo (Reports visibile), valuta fiat EUR.

| #     | Test                                              | Passi                                                                                                   | Atteso                                                                                                                                                                                                 | Pass |
|-------|---------------------------------------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 53.1  | Privacy mode da tastiera                          | 1. Wallet › Transactions. 2. Ctrl+Shift+H. 3. Clicca nel campo di ricerca e ripeti Ctrl+Shift+H.         | Tutti gli importi diventano `••••• sat` (o BTC / ₿ secondo l'unità), stessa larghezza per ogni valore, nessun +/−; l'equivalente fiat dell'header `≈ €•••••`. Funziona anche con il focus in un campo di testo. | [auto] PrivacyModeTest.`ctrl shift H is the privacy shortcut` + AmountMaskTest.`bitcoin formatter masks every unit with a fixed width and no sign` |
| 53.2  | Icona occhio nella barra di stato                 | 1. Clicca l'occhio in basso a destra. 2. Passa il mouse sopra.                                          | L'icona diventa "occhio barrato"; il tooltip dice "Show amounts (Ctrl+Shift+H)". Secondo clic: importi visibili.                                                                                       | [auto] PrivacyModeTest.`footer eye icon toggles the privacy mode` |
| 53.3  | Copertura: grafici, tooltip, tabelle tracker      | 1. Privacy on. 2. Home (donut, inflow/outflow, hero), Reports (Sankey, scatter, Balance, Inflow vs outflow, By category), Portfolio, Budget. 3. Passa il mouse su donut e sparkline. | Nessun importo leggibile: assi Y, etichette del Sankey, tick dello scatter, centro dei donut, tooltip, celle delle tabelle e la loro vista tabella. Le percentuali restano. | [auto] PrivacyModeTest.`privacy mode hides every amount in representative composables` + `chart table view is masked too` + AmountMaskTest.`chart and legacy helpers are masked too` |
| 53.4  | Hero net worth                                    | 1. Privacy on, Home. 2. Tieni premuto sull'importo.                                                     | Hero mascherato con "Hold to reveal" e niente riga delta; tenendo premuto compaiono importo e delta, al rilascio si rinascondono.                                                                      | [auto] NetWorthHeroBitcoinLensTest.`privacy mode on shows Hold to reveal hint` + `delta row hidden when privacy mode is on and not revealing` |
| 53.6  | Export con privacy attiva                         | 1. Privacy on. 2. Tracker › palette (Ctrl+K) › Esporta CSV.                                             | Prima del file dialog: "Privacy mode is on — The export contains the real amounts. Save it anyway?". No = nessun file; Sì = CSV con valori reali. Privacy off: nessuna domanda.                       | [auto] PrivacyModeTest.`export asks for confirmation only while privacy mode is on` |
| 53.7  | Start with balances hidden                        | 1. Settings › Privacy mode › "Start with balances hidden" ON. 2. Riavvia l'app. 3. Togli la privacy, poi Lock e sblocca. | Al riavvio gli importi sono mascherati. Dopo il Lock la privacy è di nuovo attiva. Con l'opzione OFF un Lock non cambia lo stato scelto a mano.                                                         | [auto] AmountMaskTest.`start hidden arms the session flag but a lock never reveals` |
| 53.8  | Security Hub: apertura e righe                    | 1. Shield nella barra di stato (o Settings › Open Security Hub, o palette). 2. Leggi le righe.           | Cinque righe con icona + testo di stato (OK / Attention / Info, mai solo colore) e azione: PIN dispositivo, blocco automatico ("After 15 min… 60 s…"), connessione, wallet "N watch-only (BHODL never holds private keys)", etichette BIP-329. Punteggio "N of M checks OK". | [auto] SecurityHubTest.`hub renders rows and routes the actions` + `there is one row per check, in display order` + `clearnet mainnet needs attention` |
| 53.10 | Data di export etichette                          | 1. Wallet settings › Export labels. 2. Torna all'Hub.                                                   | "BIP-329 labels export" mostra "Last exported on …" e diventa OK.                                                                                                                                     | [auto] SecurityHubTest.`exported labels and Tor make every counted check OK` + `labels export timestamps round-trip through settings json` |
| 53.12 | Grafico ↔ tabella                                 | 1. Home: icona tabella su "Net worth breakdown", "Inflow / outflow", hero. 2. Reports: Sankey, scatter, Balance, Inflow vs outflow, By category. 3. Budget: Cash flow forecast (se presente). | Ogni grafico ha l'icona; la tabella mostra gli stessi dati (periodo / categoria / data, importi nell'unità scelta, quote %), righe all'altezza della densità. Di nuovo l'icona → torna il grafico. | [auto] AccessibilityTablesTest.`chart card toggle swaps the chart for its table` + `table builders mirror the chart data` |
| 53.13 | Tastiera nelle tabelle                            | 1. Wallet › Transactions: Tab fino alla prima riga. 2. ↓ ↓ ↑. 3. Invio. 4. Ripeti in UTXOs, Portfolio, Rules. | Riga a fuoco con contorno arancione, distinto dallo sfondo di selezione; ↑/↓ spostano il fuoco di riga, Invio apre il dettaglio dove esiste.                                                         | [auto] AccessibilityTablesTest.`arrow keys move row focus, other keys do not` |
| 53.14 | Screen reader (NVDA)                              | 1. Avvia NVDA. 2. Naviga barra di stato, un grafico, la sua tabella.                                     | Occhio e scudo annunciano "Hide amounts" / "Open Security Hub"; il grafico "Cash flow chart. Switch to the table view to read the values."; ogni riga di tabella letta come frase unica.               | [ ]  |
| 53.15 | Densità ovunque                                   | 1. Settings › Density › Ultra-dense. 2. Investments, Manual assets, Budget, Bills, Recurring, Net worth breakdown, tabelle dei grafici. | Tutte le righe si abbassano (24 px a una riga; righe a due righe 38 px invece di 46); nessun testo tagliato.                                                                                         | [auto] AccessibilityTablesTest.`chart table rows follow the density setting` + `multi-line rows scale with density` |
| 53.16 | Spiegazione unità al primo avvio                  | 1. Data dir vuota, avvia. 2. Passa il mouse su "250,000 sat". 3. "Got it". 4. Riavvia.                   | Card "Sats and bitcoin" con esempi in sats; il tooltip mostra "0.00250000 BTC". Dopo "Got it" la card non torna. Unità di default sempre sats; BIP177 resta solo in Settings.                         | [auto] UnitsOnboardingTest.`card shows sats by default and disappears for good after Got it` + `examples are sats with the BTC equivalent, never masked` |
| 53.17 | Finestra minima e colonne                         | 1. Riduci la finestra al minimo. 2. Wallet › Transactions con il pannello dettaglio aperto. 3. Portfolio.  | La finestra non scende sotto 960×640. A larghezza stretta la tabella transazioni perde prima "Confs", poi "Status", poi "Date"; Label e Amount restano. Portfolio come §51.5.                            | [auto] AccessibilityTablesTest.`transactions table drops confirmations then status then date` + `every table keeps at most three columns on a 360dp phone` |
| 53.18 | Rail invariata                                    | 1. Naviga tutte le voci della rail.                                                                      | Stesse voci, icone e ordine di prima (Settings in fondo); la rail è resa dalla nuova astrazione di navigazione.                                                                                        | [auto] AccessibilityTablesTest.`navigation maps the rail to a bottom bar only on compact hosts` |
| 53.19 | Stringhe in italiano                              | 1. Settings → Language → Italiano. 2. Security Hub, Privacy mode, icone tabella, card unità.             | "Modalità privacy", "Avvia con i saldi nascosti", "Security Hub", "N controlli OK su M", "Mostra come tabella", "Sats e bitcoin".                                                                     | [ ]  |
| 53.20 | Nessun segno/colore con privacy attiva          | 1. Privacy on. 2. Portfolio (colonna Variazione, valori negativi), Home (KPI Opportunity cost), Reports (Net), Wallet › Transactions. | Celle `•••••` senza +/− e in colore neutro (niente verde/rosso, niente rosso passività). Le etichette costanti (Entrate "+", Uscite "−") restano. | [auto] SignedAmountScanTest + AmountMaskTest.`amount signs disappear while masked` + PrivacyModeTest.`masked columns keep sats in the header and neutral sign colours` |
| 53.21 | Auto + privacy: nessun indizio di ≥ 1 BTC       | 1. Settings › Unità = Auto. 2. Privacy on. 3. Reports › Per wallet (un wallet ≥ 1 BTC), Portfolio, assi dei grafici. | Intestazioni "(sat)" e celle `•••••`/`••••• sat` ovunque; mai "BTC" scelto dall'Auto mentre gli importi sono nascosti. | [auto] AmountMaskTest.`masked Auto never switches a column, axis or header to BTC` |
| 53.22 | Regole senza importi in chiaro                  | 1. Crea una regola "Importo esatto" da una modifica. 2. Privacy on. 3. Budget › Auto-categorization. | Nome salvato "Exact amount → <categoria>" (nessun numero); condizione mostrata con il formatter (`••••• sat` con privacy). Regole vecchie "21000 sat → cat" mostrate nell'unità scelta e mascherate. | [auto] RuleAmountPrivacyTest |
| 53.24 | Casi limite di sicurezza                        | 1. Tieni premuto Ctrl+Shift+H. 2. Reset locale completo. 3. Corrompi `settings.json` e riavvia. | 1: un solo cambio di stato. 2: date del Security Hub azzerate. 3: avvio con importi nascosti. | [auto] SecurityHubTest.`local wipe forgets the Security Hub dates` + PrivacyModeTest.`holding the shortcut toggles once, not on every key repeat` + `an unreadable settings file starts with amounts hidden` |
| 53.25 | Tabelle alla finestra minima, nessuna colonna tagliata | 1. Riduci la finestra a 960×640. 2. Wallet › Addresses (Receive e Change), UTXOs. 3. Home › tabella wallet. 4. Portfolio › Investments, Manual assets. 5. Budget › Income / Expense e Auto-categorization. | A 960 px tutte le colonne restano visibili (le tabelle si nascondono solo sotto ~470–670 dp); intestazioni allineate alle celle, importi a destra, nessun testo che esce dalla cella o dal pannello. | [ ]  |
| 53.26 | Colonne che si nascondono su schermo stretto    | Solo automatico: la finestra desktop non scende sotto 960 px, il comportamento è il contratto per host compatti (telefono 360 dp). | Addresses: via "#", poi "Label · Category" (restano indirizzo, Used/Unused, Show). UTXOs: via Type, Address, Outpoint (restano Status, Amount). Wallet: via Network, poi Type. Investments: via Type, Qty, Cost, P&L fiat (restano Name, Value, P&L BTC; anche nella riga Totale). Manual assets: via Type, Value at buy (restano Name, Value now, Δ BTC). Rules: via solo il numero di posizione. Budget: via Progress, Variance, Status (restano Categoria, Budget cliccabile, Actual). Net worth breakdown: via la quota %. Le colonne di rango 0 non spariscono mai e a 360 dp ci stanno. | [auto] TableColumnHidingTest (set visibili per larghezza + ogni tabella resa a 1000 dp e a 300–400 dp) + AccessibilityTablesTest.`every table keeps at most three columns on a 360dp phone` |
| 53.27 | Dati nascosti ancora raggiungibili              | 1. Su un host stretto (o leggendo 53.26): tocca una riga Investments / Manual assets / Rules. 2. Wallet › Addresses › Show. | Il dialog di modifica mostra tipo, quantità, costo, valore d'acquisto; la posizione di una regola resta leggibile dall'ordine della lista; l'espansione lotti mostra costi e P&L fiat; Show apre l'indirizzo nel tab Receive. | [ ]  |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.ui.money.AmountMaskTest" \
  --tests "io.bhodl.desktop.screens.PrivacyModeTest" \
  --tests "io.bhodl.desktop.screens.SecurityHubTest" \
  --tests "io.bhodl.desktop.screens.AccessibilityTablesTest" \
  --tests "io.bhodl.desktop.screens.UnitsOnboardingTest" \
  --tests "io.bhodl.desktop.screens.NetWorthHeroBitcoinLensTest" \
  --tests "io.bhodl.desktop.screens.RuleAmountPrivacyTest" \
  --tests "io.bhodl.desktop.ui.money.SignedAmountScanTest" \
  --tests "io.bhodl.desktop.screens.TableColumnHidingTest"
```

## 54. Costo medio BTC (2026-10-24)

> PLAN 4.5.8. Scheda "Costo medio BTC" in Tracker › Portfolio, sopra gli
> investimenti. **Metodo**: costo medio ponderato sui wallet mainnet inclusi
> nel tracker. Ogni entrata è un acquisto al prezzo BTC del suo giorno UTC
> (storico `btc_rate_history`, via Tor se attivo); ogni spesa o vendita è una
> cessione a quel prezzo, toglie BTC al costo medio e realizza la differenza.
> I lati di una stessa txid sui tuoi wallet si sommano (il resto verso un
> altro tuo wallet non è una cessione); i `send_to_self` sono esclusi in
> automatico. Prezzo personalizzato ed esclusione per transazione nel dialog
> della transazione (registry v5, tabella `tracking_event_cost_basis`). Le
> transazioni non confermate entrano quando confermano. Non è consulenza
> fiscale.

Dati di esempio: valuta fiat EUR, un wallet mainnet con almeno due entrate in
giorni diversi e una uscita.

| #    | Test                                    | Passi                                                                                         | Atteso                                                                                                                                                                                  | Pass |
|------|-----------------------------------------|-----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 54.1 | Scheda con prezzi di mercato            | 1. Tracker › Portfolio. 2. Attendi "Caricamento dei prezzi storici…".                         | BTC conteggiati, Prezzo medio, Costo totale, Valore attuale, Guadagno/perdita non realizzato (con %) e realizzato. Nota metodo + "Non è consulenza fiscale".                            | [auto] BtcCostBasisTest |
| 54.2 | Elenco transazioni                      | 1. "Mostra transazioni (N)".                                                                  | Più recenti in alto: data, Entrata/Uscita, importo, "@ prezzo", stato (Prezzo di mercato / Prezzo tuo / Trasferimento interno / Esclusa / Senza prezzo / Non confermata).                | [ ]  |
| 54.3 | Cambiare il prezzo                      | 1. Clicca un'entrata. 2. Sezione "Costo medio BTC": leggi il prezzo di mercato del giorno. 3. Scrivi 10000 e Salva. | La riga passa a "Prezzo tuo @ €10.000,00", prezzo medio e costo si aggiornano. Riaprendo il dialog il campo contiene 10000; svuotandolo e salvando torna il prezzo di mercato. | [auto] BtcCostBasisTest.`user price replaces the market price and exclusion drops the tx` + registry `event_cost_basis_round_trip_and_reset` |
| 54.4 | Escludere una transazione               | 1. Dialog di una transazione › "Escludi dal costo medio" › Salva.                             | Riga "Esclusa", nota "N transazioni escluse", totali ricalcolati senza di essa. Togliendo la spunta torna conteggiata.                                                                   | [auto] BtcCostBasisTest |
| 54.5 | Trasferimento tra i tuoi wallet         | 1. Due wallet mainnet; sposta fondi dall'uno all'altro. 2. Portfolio.                          | La transazione appare come "Trasferimento interno" e non cambia prezzo medio né realizzato.                                                                                              | [auto] BtcCostBasisTest.`send_to_self transfers are excluded automatically` + `legs of one txid are netted…` |
| 54.6 | Uscite oltre lo stack conteggiato       | 1. Escludi l'entrata più vecchia. 2. Portfolio.                                               | Nota "… usciti oltre quanto coperto dalle entrate conteggiate…"; nessun valore negativo di BTC o costo.                                                                                  | [auto] BtcCostBasisTest.`outflows beyond the counted stack are reported, not costed` |
| 54.7 | Senza valuta / privacy                  | 1. Settings › valuta fiat Nessuna. 2. Torna in Portfolio. 3. Rimetti EUR, attiva la privacy.   | Senza valuta: messaggio "Scegli una valuta fiat…" e nel dialog nessuna sezione prezzo. Con privacy: importi `•••••`, niente +/−, colori neutri, nessuna percentuale.                       | [auto] SignedAmountScanTest |
| 54.8 | Wallet signet/testnet                   | 1. Solo wallet signet. 2. Portfolio.                                                          | "Ancora nessuna transazione mainnet…": le monete di test non hanno prezzo.                                                                                                                | [ ]  |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.BtcCostBasisTest" \
  --tests "io.bhodl.desktop.ui.money.SignedAmountScanTest"
cargo test -p bhodl-wallet-onchain --lib event_cost_basis
```

## 55. Campagna di test sui casi d'uso (2026-10-24)

> Inventario completo in `docs/use-cases.md` (660 casi in cinque aree, con la
> revisione dei gap). Ogni caso ha il suo test o la motivazione del perché
> no. I test nuovi si chiamano `Uc*Test` (Kotlin) e `uc_*` (Rust), con l'ID del
> caso nel nome. Scenari di portafoglio end-to-end su registry vero:
> `PortfolioScenarioTest` (FIFO, HIFO, specific-id, vendita oltre la
> quantità, cancellazione della vendita, net worth con investimenti, asset
> manuali e debiti, costo medio BTC).

Correzioni trovate dai test e da verificare a mano una volta:

| #    | Test                                   | Passi                                                                                  | Atteso                                                                                                   | Pass |
|------|----------------------------------------|----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|------|
| 55.1 | Costo residuo di un lotto venduto in parte | 1. Investimento: lotto 10 @ 60 con 10 di commissioni. 2. Vendi 5.                   | Costo residuo 305 (5 × (60 + 1)): conta solo la quota di commissioni dei pezzi rimasti.                    | [auto] PortfolioScenarioTest |
| 55.2 | Stesso xpub in formato vpub/tpub       | 1. Aggiungi un wallet da tpub. 2. Riprova con lo stesso account in formato vpub.        | Il secondo inserimento è rifiutato come duplicato.                                                       | [auto] UcAddWatchWalletTest.`WAL-020 a vpub of an account saved as tpub is caught as a duplicate` |
| 55.3 | Export CSV filtrato                    | 1. Activity: filtra per categoria. 2. Esporta CSV.                                     | Il file contiene solo le righe filtrate.                                                                 | [auto] UcTransactionsTrackerTest.`TRK-161 the CSV export follows the active filters` |
| 55.4 | Trasferimenti tra i propri wallet      | 1. Sposta fondi tra due tuoi wallet. 2. Guarda breakdown, report e flussi mensili.     | Il trasferimento non conta come spesa.                                                                   | [auto] UcAggregationsTrackerTest.`TRK-110 send_to_self contributes only its fee to the period KPIs` |
| 55.5 | Seconda istanza                        | 1. Avvia BHODL. 2. Avvialo di nuovo.                                                   | Il secondo avvio mostra "BHODL è già aperto" e si chiude.                                                | [auto] SingleInstanceLockTest — manual: dialog |
| 55.6 | Home: KPI, previsione, revisione mensile | 1. Tracker › Home con transazioni nel mese.                                          | Sotto il patrimonio: striscia KPI, previsione di cassa a 90 giorni, banner di revisione mensile che apre Reports. | [ ] |
| 55.7 | Debiti                                 | 1. Aggiungi un debito di tipo carta di credito con nota "Visa".                        | Sottotitolo "Credit card · Visa" (IT: "Carta di credito · Visa"); il core rifiuta tipi sconosciuti.       | [auto] UcPortfolioNetWorthTest + registry `liability_kind_is_validated_on_add_and_update` (cargo) |
| 55.8 | Errori tradotti                        | 1. Lingua italiana. 2. Provoca un errore di sync (endpoint irraggiungibile).            | Il messaggio è in italiano, con il dettaglio tecnico dopo i due punti.                                   | [auto] UcRuntimeStringsSecurityTest |

### Verifiche automatizzate

```
cargo test --workspace
./gradlew :shared:jvmTest :apps:desktop:jvmTest
./gradlew :apps:desktop:integrationTest   # Docker: UcWalletRegtestIntegrationTest
```

## 56. Recupero del PIN (2026-10-24)

> Progetto in `docs/pin-recovery.md`. La chiave che cifra i dati è avvolta dal
> PIN (descrittore `registry.kdf.json` v3) e, se lo crei, da un codice di
> recupero (`registry.recovery.json`). Cambiare o recuperare il PIN non
> ricifra nessun file. Al primo sblocco dopo l'aggiornamento il descrittore
> passa a v3 da solo.

Dati di esempio: registry con almeno un wallet e qualche dato del tracker.

| #    | Test                                   | Passi                                                                                                  | Atteso                                                                                                                       | Pass |
|------|----------------------------------------|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|------|
| 56.1 | Migrazione al primo sblocco            | 1. Con un registry creato da una build precedente, sblocca. 2. Apri `registry.kdf.json`.               | Si apre come prima; il file ha `"version": 3`, `nonce_hex` e `wrapped_key_hex`.                                               | [auto] (cargo) `h1_legacy_v1_mac_is_accepted_once_then_rewrapped_as_v3` |
| 56.2 | Hub: codice mancante                   | 1. Hub sicurezza.                                                                                      | Riga "Codice di recupero" in Attenzione, azione "Crea"; il punteggio la conta.                                                | [auto] PinRecoveryScreensTest.`SEC-145 the hub flags a missing code and flips to OK once it is saved` |
| 56.3 | Creare il codice                       | 1. Crea. 2. Copia il codice. 3. Scrivi un gruppo sbagliato, poi quello chiesto. 4. "L'ho salvato".     | Codice in 6 gruppi da 4; "L'ho salvato" si abilita solo col gruppo giusto; la riga passa a OK con "Gestisci".                 | [auto] PinRecoveryScreensTest.`SEC-141 the code is shown once and closing needs one group typed back` |
| 56.4 | Rigenerare                             | 1. Gestisci › Crea un nuovo codice. 2. Blocca e prova il vecchio codice.                               | Avviso che il vecchio smette di funzionare; il vecchio codice viene rifiutato, il nuovo funziona.                            | [auto] (cargo) `sec_044_regenerating_the_code_revokes_the_previous_one` |
| 56.5 | PIN dimenticato, con codice            | 1. Blocca. 2. "Hai dimenticato il PIN?" › "Ho il codice di recupero". 3. Codice in minuscolo con spazi, nuovo PIN due volte. | Si entra nell'app con tutti i dati e i wallet; al blocco successivo il vecchio PIN è rifiutato e il nuovo funziona.          | [auto] PinRecoveryScreensTest.`SEC-143 end to end - recovery code sets a new PIN and opens the real registry` |
| 56.6 | Codice errato                          | 1. Come 56.5 con un codice sbagliato, più volte.                                                       | "Codice di recupero errato."; dopo alcuni tentativi compare l'attesa in secondi. Un codice troppo corto dà l'errore di formato e non conta. | [auto] PinRecoveryScreensTest.`SEC-142 a wrong code is counted, a malformed one is not` |
| 56.7 | PIN dimenticato, senza codice          | 1. Su un'installazione senza codice: "Hai dimenticato il PIN?". 2. "Non ho il codice di recupero". 3. Scrivi DELETE e conferma. | Avviso che non c'è un codice; spiegazione di cosa resta e cosa si perde; i file finiscono in `~/.bhodl-trash/<AAAAMMGG-hhmmss>/` (recuperabili) e si riparte dal Welcome.          | [auto] UcPinScreensSecurityTest.`SEC-044 a forgotten PIN without a code reaches the explained local reset` |
| 56.8 | Cambia PIN                             | 1. Hub › riga PIN › Cambia. 2. PIN attuale sbagliato. 3. PIN attuale giusto e nuovo PIN.               | Prima "PIN del dispositivo errato." (conta nel blocco); poi "PIN del dispositivo cambiato."; al blocco vale il nuovo PIN.      | [auto] PinRecoveryScreensTest.`SEC-144 a wrong current PIN is counted and a right one confirms` |
| 56.9 | Reset di fabbrica                      | 1. Settings › Reset di fabbrica.                                                                       | Anche `registry.recovery.json` viene cancellato.                                                                             | [auto] PinRecoveryScreensTest.`SEC-146 the factory reset also removes the recovery file` |

### Verifiche automatizzate

```
cargo test -p bhodl-uniffi uc_pin_recovery
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.PinRecoveryScreensTest" \
  --tests "io.bhodl.desktop.screens.UcPinScreensSecurityTest"
```

## 57. Audit 2026-10 — reset pre-auth e clipboard (2026-10-21, adattato al watch-only)

> Due finding residui dell'audit 2026-10.
> **Reset pre-auth** (Welcome › "Erase ALL local wallets…", raggiungibile dal
> gate PIN bloccato con Back): se esiste un registry serve il **PIN del
> dispositivo** (tentativi contati come sul gate, stesso lockout) per la
> cancellazione definitiva. "I forgot the device PIN" (o nessun registry)
> **non cancella**: i file vengono spostati in `~/.bhodl-trash/<AAAAMMGG-hhmmss>/`
> (UTC, stesso percorso relativo di `~/.bhodl`) dopo la spunta "I can add every
> wallet again from its xpub, descriptor or addresses". Il cestino non
> viene mai svuotato dall'app. Settings › Factory reset (sessione
> autenticata) resta solo `DELETE`.
> **Clipboard**: tutte le copie di dati del wallet (indirizzo, URI BIP21,
> txid, xpub / descriptor) passano da `SensitiveClipboard`: su Windows
> pubblicano anche i formati `ExcludeClipboardContentFromMonitorProcessing`,
> `CanIncludeInClipboardHistory` = 0 e `CanUploadToCloudClipboard` = 0, quindi
> non finiscono nella cronologia Win+V né nel clipboard cloud. xpub e
> descriptor mantengono l'auto-clear a 60 s.

| #     | Test                                              | Passi                                                                                                   | Atteso                                                                                                                                                                                                 | Pass |
|-------|---------------------------------------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 57.1  | Reset pre-auth con PIN del dispositivo            | 1. Install con registry, app chiusa. 2. Avvio → gate PIN → Back → Welcome › "Erase ALL local wallets…". 3. `DELETE` senza PIN. 4. PIN sbagliato. 5. PIN giusto. | 3: bottone disabilitato. 4: "Wrong device PIN.", nessun file toccato, il tentativo conta (stesso lockout del gate). 5: "Wiped N items.", `~/.bhodl` senza registry né wallet; nessuna cartella `~/.bhodl-trash`. | [auto] SecretActionsUiTest.`Welcome reset with a registry requires the device PIN and counts wrong attempts` |
| 57.2  | "Ho dimenticato il PIN" → cestino recuperabile    | 1. Come 57.1 punto 2. 2. Spunta "I forgot the device PIN". 3. `DELETE` senza spunta backup. 4. Spunta "I can add every wallet again…" → "Move wallets to recovery folder". | 2: il campo PIN sparisce, il testo indica la cartella `…/.bhodl-trash`. 3: bottone disabilitato. 4: "Moved N items to …/.bhodl-trash/<timestamp>". L'app riparte da Welcome come installazione nuova. | [auto] SecretActionsUiTest.`forgot device PIN switches to the trash path` + LocalWipeTrashTest.`move to trash empties the data dir and keeps every file restorable` |
| 57.3  | Ripristino dal cestino                            | 1. Dopo 57.2 chiudi l'app. 2. Sposta il contenuto di `~/.bhodl-trash/<timestamp>/` dentro `~/.bhodl/`. 3. Riavvia e inserisci il PIN ricordato. | Il gate PIN accetta il PIN e i wallet tornano tutti, con storico e tracking. | [ ]  |
| 57.4  | Reset senza registry (legacy / nessun PIN device) | 1. Data dir con solo wallet legacy. 2. Welcome › Erase ALL local wallets…. | Nessun campo PIN né "forgot PIN": solo `DELETE` + spunta backup, file spostati nel cestino. Due reset nello stesso secondo creano cartelle distinte (`…-1`). | [auto] SecretActionsUiTest.`Welcome reset without a registry moves the files to trash after the seed acknowledgement` + LocalWipeTrashTest.`a second reset in the same second gets its own folder` |
| 57.5  | Clipboard fuori dalla cronologia Windows          | 1. Windows 10/11, Impostazioni › Appunti: cronologia (e sincronizzazione) attive. 2. Copia un testo qualsiasi da un'altra app. 3. Receive › Copy address; Settings › Show watch-only descriptor › Copy xpub; dettaglio tx › Copy txid. 4. Win+V. 5. Incolla subito in un editor. | 4: nella cronologia c'è il testo del punto 2 ma **nessuno** dei valori di BHODL. 5: incolla il valore corretto. Dopo 60 s l'xpub / descriptor non è più negli appunti (gli indirizzi restano). | [auto] SensitiveClipboardTest (formati e auto-clear su clipboard privata) — la verifica Win+V resta manuale |
| 57.6  | Clipboard su macOS / Linux                        | 1. Copia indirizzo e xpub. 2. Incolla.                                                                   | Incollato il testo esatto; l'xpub si svuota dopo 60 s; nessun formato extra (solo testo).                                                                                                              | [auto] SensitiveClipboardTest.`elsewhere only plain text is offered` |
| 57.7  | Stringhe in italiano                              | 1. Lingua Italiano. 2. Welcome › Cancella TUTTI i wallet locali….                                        | "Ho dimenticato il PIN del dispositivo", "Posso aggiungere di nuovo ogni wallet…", "Sposta i wallet nella cartella di recupero", "Spostati N elementi in …". | [ ]  |

> Nota watch-only: il blocco inattività del wizard di setup (seed a schermo)
> non esiste più, perché il wizard non mostra seed. Anche "Hai dimenticato il
> PIN?" › "Non ho il codice di recupero" (§56.7) usa lo stesso cestino.

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.SecretActionsUiTest" \
  --tests "io.bhodl.desktop.wizard.LocalWipeTrashTest" \
  --tests "io.bhodl.desktop.ui.SensitiveClipboardTest"
cargo test -p bhodl-wallet-onchain --lib tor
```

## 58. Portafoglio a schede (2026-10-25)

> Proposte B + D + E della tela di design. Tracker › Portfolio apre la vista
> **Schede**: quattro card di classe (Bitcoin, Investimenti, Asset manuali,
> Debiti) con valore, peso sul netto, variazione nel periodo, andamento e le
> prime tre voci; sotto, l'elenco della classe e la card della voce scelta.
> Ogni tipo ha i suoi dettagli facoltativi (registry v6, colonna
> `details_json`), modificabili con "Dettagli". "Tabella" riporta il
> bilancio a righe con le card investimenti / asset manuali sotto.

Dati di esempio: un ETF con lotti e una vendita, un conto deposito, una casa con
mutuo collegato, una carta di credito.

| #    | Test                               | Passi                                                                                          | Atteso                                                                                                                      | Pass |
|------|------------------------------------|------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|------|
| 58.1 | Apertura                           | 1. Tracker › Portfolio.                                                                        | Vista Schede; si apre la prima classe con voci e la sua voce più grande.                                                     | [auto] PortfolioClassViewTest.`the first class with items is open and its biggest item is shown` |
| 58.2 | ETF                                | 1. Investimenti › ETF. 2. Dettagli: ISIN, TER 0,22, Accumulazione.                              | Card con valore, quantità, prezzo medio, non realizzato; TER e "Costo annuo del fondo"; lotti con P&L e vendite; Vendi apre la vendita. | [auto] PortfolioClassViewTest.`an ETF card shows its lots, sales and TER cost and offers Sell` |
| 58.3 | Mutuo e casa                       | 1. Debiti › Mutuo › Dettagli: importo iniziale, tasso, fine, bene collegato = Casa. 2. Asset manuali › Casa. | Mutuo: barra "Rimborsato" verde, tempo residuo, bene collegato. Casa: equity e barra LTV.                                    | [auto] PortfolioClassViewTest.`a mortgage card resolves its linked house and the house shows the LTV` |
| 58.4 | Editor dei dettagli                | 1. Una voce › Dettagli. 2. Scrivi la rata con la virgola, una data, scegli un'opzione. 3. Salva. | I valori restano dopo il riavvio; numeri con virgola accettati; date non valide scartate.                                    | [auto] PortfolioClassViewTest.`the details editor saves typed values as canonical JSON` + (cargo) `details_round_trip_on_every_portfolio_table` |
| 58.5 | Conto deposito                     | 1. Liquidità con tasso e scadenza.                                                             | Interesse annuo, scadenza, barra "Vincolo trascorso".                                                                        | [auto] PortfolioDetailsTest |
| 58.6 | Schede ⇄ Tabella                   | 1. Clic su Tabella. 2. Clic su Schede.                                                         | Tabella: bilancio a righe + costo medio BTC + investimenti + asset manuali. Schede: il costo medio BTC sta sotto la classe Bitcoin. | [auto] PortfolioClassViewTest.`the Cards and Table toggle switches the portfolio view` |
| 58.7 | Migrazione                         | 1. Avvia con un registry v5.                                                                    | Si apre; i dati ci sono; i dettagli sono vuoti.                                                                              | [auto] (cargo) `v5_registry_gains_details_columns_and_keeps_its_rows` |
| 58.8 | Finestra stretta                   | 1. Restringi la finestra sotto ~900 px.                                                        | Card di classe su due colonne, elenco sopra la card di dettaglio.                                                           | [ ] |
| 58.9 | Italiano                           | 1. Lingua italiana.                                                                            | Classi, campi e opzioni tradotti (es. "Tasso d’interesse (%)", "Abitazione principale").                                     | [ ] |

### Verifiche automatizzate

```
cargo test -p bhodl-wallet-onchain --lib details
cargo test -p bhodl-uniffi portfolio_details
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.PortfolioDetailsTest" \
  --tests "io.bhodl.desktop.screens.PortfolioClassViewTest"
```

Gli screenshot della revisione grafica finiscono in `apps/desktop/build/screenshots/`.

## 59. Home a cruscotto (2026-10-25)

> Proposta A scelta per la Home. In alto il patrimonio netto con, a destra,
> **Composizione** (barra bitcoin / investimenti / asset manuali, debiti come
> quota, link al Portafoglio) e **Da fare** (revisione del mese, bolletta entro
> 7 giorni, ricorrenti da confermare, transazioni senza categoria, valori non
> aggiornati da 6 mesi). Sotto una sola striscia di indicatori (entrate, uscite,
> risparmio, FIRE, autonomia, costo opportunità), poi entrate/uscite e
> previsione affiancati, poi i wallet. Lo stato della sincronizzazione è solo
> nella striscia sopra la sezione: niente banner "dati vecchi" nella Home.
> Ogni indicatore ha il suo "?".

| #    | Test                     | Passi                                                                 | Atteso                                                                                           | Pass |
|------|--------------------------|-----------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|------|
| 59.1 | Disposizione             | 1. Tracker › Home a finestra piena, poi sotto ~1000 px.               | Patrimonio e colonna destra affiancati; sotto 1000 px uno sotto l'altro. Una sola striscia KPI.  | [auto] HomeCockpitTest.`render the home for the visual review` |
| 59.2 | Da fare                  | 1. Una bolletta che scade tra 3 giorni, una transazione senza categoria. | Righe "Luce in scadenza tra 3 giorni" e "Transazioni senza categoria: 1"; clic → Budget / Transazioni. | [auto] HomeCockpitTest + UcHomeTrackerTest.`TRK-168 to-do rows open the section that fixes them` |
| 59.3 | Revisione del mese       | 1. Primi giorni del mese, con transazioni. 2. "Apri".                 | La riga sparisce e si apre Report.                                                               | [ ] |
| 59.4 | Tutto in ordine          | 1. Nessuna cosa in sospeso.                                           | "Tutto in ordine."                                                                               | [auto] UcHomeTrackerTest.`TRK-168 the home shows no stale banner…` |
| 59.5 | Tooltip                  | 1. Passa sui "?" di Composizione, Da fare e di ogni indicatore. 2. Tab fino a un "?". | Spiegazione di cosa misura e come è calcolato; con il Tab compare la stessa spiegazione.           | [ ] |
| 59.6 | Tema chiaro              | 1. Settings › Tema Chiaro.                                            | Tutto leggibile, colori del tema.                                                                | [auto] screenshot `home-light.png` |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.HomeCockpitTest" \
  --tests "io.bhodl.desktop.screens.UcHomeTrackerTest"
```

## 60. Transazioni a cronologia (2026-10-25)

> Proposta C scelta per le Transazioni. In alto **Entrate e uscite** (due
> barre e quanto è rimasto) e **Dove sono andati i sat** (barra a segmenti per
> categoria, le cinque più grandi con nome). Poi ricerca, wallet inclusi come
> pillole e filtri per categoria. Sotto, la cronologia per giorno con riquadro
> colorato, pillola della categoria e totale del giorno; a lato **Da rivedere**
> con le categorie più usate da applicare con un clic. Ora la sezione segue il
> periodo globale (un intervallo dal grafico lo sostituisce) e mostra 50
> righe con "mostra altre" al posto del limite fisso.

| #    | Test                  | Passi                                                                  | Atteso                                                                                 | Pass |
|------|-----------------------|------------------------------------------------------------------------|----------------------------------------------------------------------------------------|------|
| 60.1 | Riepiloghi            | 1. Tracker › Transazioni con entrate e uscite nel periodo.             | Barre entrate/uscite e "Risparmiato nel periodo"; segmenti per categoria con percentuali. | [auto] TransactionsTimelineTest |
| 60.2 | Cronologia            | 1. Scorri.                                                             | In attesa per prime, poi i giorni dal più recente, con il totale di ogni giorno.        | [auto] TransactionsTimelineTest.`days come newest first…` |
| 60.3 | Da rivedere           | 1. Una transazione senza categoria. 2. Clic su una pillola.             | La transazione prende la categoria e sparisce dalla colonna.                            | [auto] TransactionsTimelineTest.`summaries, review lane…` |
| 60.4 | Ricerca               | 1. Scrivi parte di categoria, wallet, txid o importo.                   | Restano solo le righe che corrispondono; l'export CSV segue la ricerca.                | [auto] TransactionsTimelineTest.`the ledger follows the period…` |
| 60.5 | Periodo               | 1. Cambia periodo (7g / 30g / …).                                       | Riepiloghi e cronologia seguono il periodo.                                             | [ ] |
| 60.6 | Mostra altre          | 1. Più di 50 transazioni.                                               | "Mostrate 50 di N · mostra altre" aggiunge 50 righe.                                    | [auto] UcTransactionsTrackerTest.`TRK-142…` |
| 60.7 | Wallet inclusi        | 1. Clic su una pillola wallet.                                          | Diventa "Escluso" e i totali non lo contano più.                                        | [auto] UcTransactionsTrackerTest.`TRK-027…` |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.TransactionsTimelineTest" \
  --tests "io.bhodl.desktop.screens.UcTransactionsTrackerTest"
```


## 61. Obiettivi a card (2026-10-25)

> Proposta A scelta per gli Obiettivi. In alto i conteggi (obiettivi, in linea,
> in ritardo, raggiunti) e il filtro per tipo (Accumulo, Spese future,
> Allocazione). Ogni obiettivo è una card con anello di avanzamento, stato
> (IN LINEA / IN RITARDO / IN ANTICIPO / SCADUTO / RAGGIUNTO), etichetta del
> bucket collegato e quattro riquadri: accumulato, obiettivo, quanto serve al
> mese (spese future), scadenza o quota del netto. Clic sulla card: modifica.

| #    | Test              | Passi                                                      | Atteso                                                                         | Pass |
|------|-------------------|------------------------------------------------------------|--------------------------------------------------------------------------------|------|
| 61.1 | Conteggi          | 1. Obiettivi con stati diversi.                            | "N obiettivi · X in linea · Y in ritardo · Z raggiunti" coerente con le card.  | [auto] GoalsCardsTest.`counts split on track, behind and reached` |
| 61.2 | Filtro per tipo   | 1. Clic su "Spese future".                                 | Restano solo le spese future.                                                  | [auto] GoalsCardsTest.`the type filter narrows the cards` |
| 61.3 | Spesa futura      | 1. Obiettivo con scadenza.                                 | "Al mese serve" calcolato, scadenza e giorni mancanti.                         | [auto] screenshot `goals-dark.png` |
| 61.4 | Allocazione       | 1. Obiettivo "% del netto".                                | Riquadro "Quota del netto"; etichetta di scostamento oltre ±5 pp.              | [ ] |
| 61.5 | Finestra stretta  | 1. Restringi la finestra.                                  | 3 → 2 → 1 card per riga.                                                       | [ ] |

### Verifiche automatizzate

```
./gradlew :apps:desktop:jvmTest --tests "io.bhodl.desktop.screens.GoalsCardsTest" \
  --tests "io.bhodl.desktop.screens.SavingsGoalsCard*"
```

## 62. Budget e bollette a card (2026-10-25)

> Proposta C scelta. In alto il riepilogo con due anelli (esterno: speso sul
> budget del periodo; interno: entrate ricevute su quelle previste, se ci sono
> budget di entrata) e quattro numeri (budget, speso, speso senza budget,
> effetto prezzo). Poi una card per categoria con anello, "restano" o
> "sforato di", la parte di prezzo e di spesa, e "Vedi transazioni"; le
> categorie senza budget sono pillole "+". Poi il calendario delle bollette dei
> prossimi 30 giorni, i ricorrenti e le regole. Il grafico a lecca-lecca delle
> spese è stato tolto: le card lo sostituiscono.

| #    | Test                 | Passi                                                          | Atteso                                                                   | Pass |
|------|----------------------|----------------------------------------------------------------|--------------------------------------------------------------------------|------|
| 62.1 | Riepilogo            | 1. Budget su alcune categorie, spese nel periodo.              | Totali dei soli budget; "restano" al centro dell'anello.                 | [auto] BudgetCardsTest.`summary totals only the budgeted categories…` |
| 62.2 | Card categoria       | 1. Clic su una card. 2. "Vedi transazioni".                    | 1: apre il budget. 2: Transazioni filtrate su quella categoria.          | [auto] UcRegistryWiringTrackerTest.`TRK-099…` |
| 62.3 | Senza budget         | 1. Spese in una categoria senza budget.                        | Pillola "Categoria · importo +" che apre il budget.                     | [ ] |
| 62.4 | Bollette             | 1. Bollette nei prossimi 30 giorni. 2. "Segna pagata".         | Evidenziate se entro 3 giorni; pagata passa al mese dopo se entro 30 giorni. | [auto] BudgetCardsTest.`upcoming bills…` + UcRegistryWiringTrackerTest |
| 62.5 | Tema chiaro          | 1. Tema chiaro.                                                | Leggibile.                                                               | [auto] screenshot `budget-light.png` |

## 63. Impostazioni a due colonne (2026-10-25)

> Proposta A scelta. A sinistra l'indice dei gruppi (Generale, Aspetto, Rete e
> privacy, Bitcoin Lens, Tracker, Sicurezza, Dati) con una riga di sottotitolo;
> a destra ogni gruppo è una card con il suo titolo. Clic su un gruppo: la
> colonna destra scorre fino a lì. Rete, Esplora, Tor, prezzi e privacy sono
> ora nello stesso gruppo; la colonna non è più larga solo 480 px.

| #    | Test              | Passi                                       | Atteso                                                   | Pass |
|------|-------------------|---------------------------------------------|----------------------------------------------------------|------|
| 63.1 | Indice            | 1. Settings. 2. Clic su "Dati".             | Scorre fino a impostazioni wallet e reset di fabbrica.   | [auto] SettingsGroupsTest.`every group has a card and the index scrolls to it` |
| 63.2 | Gruppi            | 1. Scorri tutta la colonna.                 | Ogni controllo di prima c'è, nel gruppo giusto.          | [auto] SettingsScreenTest (tutti) |
| 63.3 | Finestra stretta  | 1. Restringi la finestra.                   | Indice e card restano leggibili.                         | [ ] |
| 63.4 | Tema chiaro       | 1. Tema chiaro.                             | Leggibile.                                               | [auto] screenshot `settings-light.png` |

## 64. Report con elenco a sinistra e torte (2026-10-25)

> Proposta "A + torte di C". A sinistra l'elenco dei report (Per categoria,
> Per wallet); a destra un report alla volta. Per categoria: totali del periodo
> (uscite, entrate, netto, risparmiato) con la variazione sul periodo prima,
> tre insight (cresciuta di più, scesa di più, prima voce), torta delle uscite
> con legenda cliccabile e barre "questo periodo / periodo prima", poi la
> tabella. Il segnaposto "Controparti" è stato tolto.

| #    | Test              | Passi                                                  | Atteso                                                                   | Pass |
|------|-------------------|--------------------------------------------------------|--------------------------------------------------------------------------|------|
| 64.1 | Confronto         | 1. Periodo 30 giorni, spese anche nei 30 prima.        | Percentuali di variazione per categoria e sui totali.                     | [auto] ReportsOverviewTest.`categories compare with the previous window` |
| 64.2 | Periodo "Tutto"   | 1. Periodo Tutto.                                      | Nessuna variazione, nessuna barra sottile.                                | [ ] |
| 64.3 | Legenda           | 1. Clic su una categoria nella legenda della torta.    | Fetta e barra di quella categoria evidenziate.                            | [ ] |
| 64.4 | Elenco report     | 1. Clic su "Per wallet".                               | Solo la tabella per wallet.                                               | [auto] ReportsOverviewTest.`the report list switches…` |
| 64.5 | Tema chiaro       | 1. Tema chiaro.                                        | Leggibile.                                                                | [auto] screenshot `reports-light.png` |

## 65. Wallet: card dei conti e mappa delle monete (2026-10-25)

> Mix delle proposte A e C. Sopra il workspace una fila di card, una per
> wallet tracciato: nome, tipo, rete, saldo, sparkline a 30 giorni e stato
> (sincronizzato / non aggiornato, con "?"). Il wallet aperto è evidenziato,
> clic su un altro lo apre; l'ultima card aggiunge un wallet. Nell'header c'è
> "Ricevi". Nella scheda UTXO, sopra la tabella, la mappa delle monete: un
> blocco per UTXO largo in proporzione all'importo, confermati e in attesa
> con colori diversi. Il tasto Wallet della barra laterale, senza wallet
> aperto, apre il primo wallet tracciato.

| #    | Test                | Passi                                                   | Atteso                                                          | Pass |
|------|---------------------|---------------------------------------------------------|-----------------------------------------------------------------|------|
| 65.1 | Card dei conti      | 1. Apri un wallet con almeno due wallet tracciati. 2. Clic sull'altra card. | Si apre l'altro wallet.                          | [auto] WalletCardsTest.`cards show every wallet…` |
| 65.2 | Sparkline           | 1. Wallet con movimenti negli ultimi 30 giorni.        | Linea che segue il saldo; nessuna linea se il saldo non è cambiato. | [auto] WalletCardsTest.`balance series…` |
| 65.3 | Non aggiornato      | 1. Fai fallire la sync di un wallet.                    | Card "non aggiornato" con "?" che spiega cosa fare.            | [ ] |
| 65.4 | Ricevi              | 1. Clic su "Ricevi" nell'header.                        | Scheda Ricevi. Assente sui wallet a lista di indirizzi.         | [auto] WalletCardsTest.`the workspace shows the cards…` |
| 65.5 | Mappa delle monete  | 1. Scheda UTXO.                                         | Un blocco per UTXO, i più grandi prima; legenda confermato / in attesa. | [auto] screenshot `wallet-dark.png` |
| 65.6 | Tasto Wallet        | 1. Blocca e sblocca. 2. Tasto Wallet nella barra.       | Si apre il primo wallet tracciato; senza wallet, la Home.       | [auto] UcNavigationSecurityTest.`SEC-145…` |
| 65.7 | Tema chiaro         | 1. Tema chiaro.                                         | Leggibile.                                                      | [auto] screenshot `wallet-light.png` |

## 66. Portafoglio come proposta B (2026-10-25)

> Tale e quale alla proposta B del canvas. A sinistra: titolo "Portafoglio"
> (con Schede / Tabella), patrimonio "sat netti" con la variazione nel periodo,
> l'anello delle classi; le quattro card di classe in griglia 2×2 (riga di
> dettaglio tipo "3 titoli · 4 lotti · FIFO", linea a 30 giorni senza riempimento,
> prime tre voci in forma breve "120,0M"); i pulsanti tratteggiati
> "+ Investimento / + Asset manuale / + Debito / + Wallet (xpub)". A destra il
> pannello "Dettaglio" della classe con la × per chiuderlo: Valore, Costo, Non
> realizzato, vs HODL ("—" dove il dato non c'è), "Voci · ordinate per peso"
> con barra, peso e P&L (per i debiti la quota rimborsata), "Registra acquisto /
> vendita" per gli investimenti. Clic su una voce: si apre la sua pagina con il
> percorso "Portafoglio › Classe › Voce" (proposta D, card di E).

| #    | Test              | Passi                                                         | Atteso                                                                  | Pass |
|------|-------------------|---------------------------------------------------------------|-------------------------------------------------------------------------|------|
| 66.1 | Pannello classe   | 1. Clic su "Investimenti".                                    | Costo, non realizzato, vs HODL; voci per peso con P&L.                   | [auto] PortfolioClassViewTest.`class panel numbers…` |
| 66.2 | vs HODL           | 1. Investimento con lotti che hanno il cambio BTC del giorno. | Percentuale; "—" se manca il cambio su un lotto.                         | [auto] PortfolioClassViewTest.`class panel numbers…` |
| 66.3 | Pagina voce       | 1. Clic su una voce del pannello. 2. Clic su "Portafoglio".   | 1: pagina della voce con il percorso. 2: torna al pannello.              | [auto] PortfolioClassViewTest.`the first class with items is open…` |
| 66.4 | Chiudi pannello   | 1. × sul pannello. 2. Clic su una card.                       | 1: le card occupano tutta la larghezza. 2: il pannello torna.            | [auto] PortfolioClassViewTest.`the first class with items is open…` |
| 66.5 | Registra          | 1. "Registra acquisto" con più titoli.                        | Chiede quale titolo, poi apre il lotto nuovo; con un solo titolo lo apre subito. | [ ] |
| 66.6 | Aggiungi          | 1. I quattro pulsanti tratteggiati.                           | Si apre il modulo giusto; "+ Wallet (xpub)" apre l'aggiunta wallet.      | [ ] |
| 66.7 | Finestra stretta  | 1. Restringi sotto 1100 px.                                   | Il pannello va sotto le card.                                            | [ ] |
| 66.8 | Tema chiaro       | 1. Tema chiaro.                                               | Leggibile.                                                               | [auto] screenshot `portfolio-light-liabilities.png` |

## 67. Pulsante Indietro sempre presente (2026-10-25)

> In cima alla barra laterale c'è sempre "←". Chiude prima la pagina interna
> aperta (per esempio la voce del Portafoglio), poi torna alla schermata
> precedente, poi alla sezione visitata prima (fino a 30). Grigio quando non
> c'è dove tornare.

| #    | Test                | Passi                                                        | Atteso                                                        | Pass |
|------|---------------------|--------------------------------------------------------------|---------------------------------------------------------------|------|
| 67.1 | Sezioni             | 1. Home → Budget → Report. 2. ← due volte.                   | Report → Budget → Home; poi il pulsante è grigio.              | [auto] UcNavigationSecurityTest.`rail Back returns to the section visited before` |
| 67.2 | Pagina interna      | 1. Portafoglio, clic su una voce. 2. ←.                      | Torna al pannello della classe, resta in Portafoglio.          | [auto] UcNavigationSecurityTest.`rail Back closes an in-screen page first` |
| 67.3 | Wallet              | 1. Da Home apri un wallet. 2. ←.                             | Torna alla Home.                                              | [ ] |

## 68. Categoria senza domande, regola come opzione (2026-10-25)

> Dopo aver messo una categoria l'app non chiede più "Farlo sempre?". Nella
> finestra della categoria, sotto il campo, c'è "Crea anche una regola per i
> prossimi movimenti" (spenta di default): accesa, si sceglie se abbinare per
> wallet o per importo esatto, si vede a quante transazioni senza categoria si
> applica e si può categorizzarle subito. La categoria salvata compare subito
> nell'elenco, anche mentre una sincronizzazione lenta è in corso.

| #    | Test                 | Passi                                                         | Atteso                                                           | Pass |
|------|----------------------|---------------------------------------------------------------|------------------------------------------------------------------|------|
| 68.1 | Spesa singola        | 1. Transazioni, clic su una categoria. 2. Scrivi e Salva.     | Nessuna domanda dopo; la categoria compare subito.               | [auto] UcRegistryWiringTrackerTest.`TRK-034 a single category save asks nothing…` |
| 68.2 | Con regola           | 1. Spunta "Crea anche una regola". 2. Wallet. 3. Salva.       | Regola in cima all'elenco; le transazioni abbinate senza categoria ricevono la categoria. | [auto] UcRegistryWiringTrackerTest.`TRK-034 TRK-072 the category editor can also create a rule…` |
| 68.3 | Importo esatto       | 1. Spunta la regola, scegli "Importo esatto".                 | L'anteprima conta solo le transazioni con lo stesso importo e direzione. | [ ] |
| 68.4 | Sync lenta           | 1. Durante una sync lunga cambia una categoria.               | La categoria compare subito, senza aspettare la fine della sync. | [ ] |

## 69. Categorie precaricate, nuove categorie e colori (2026-10-25)

> La finestra della categoria ha un menu a tendina (▾) con le categorie classiche
> (Cibo, Trasporti, Casa, Bollette, Salute, Svago, Shopping, Viaggi, Abbonamenti,
> Istruzione, Regali, Stipendio, Ristoranti, Investimenti, Altro), quelle aggiunte
> e quelle già usate, ognuna col suo pallino colorato. Scrivendo il menu si filtra;
> un nome nuovo propone "+ Nuova categoria". Sotto c'è la riga dei colori: il
> colore scelto vale per quella categoria ovunque (righe, barra "dove sono andati
> i sat", torta dei Report). Le classiche hanno colori diversi tra loro; un nome
> alternativo (spesa, alimentari → Cibo; carburante → Trasporti) prende il colore
> della classica.

| #    | Test                 | Passi                                                         | Atteso                                                           | Pass |
|------|----------------------|---------------------------------------------------------------|------------------------------------------------------------------|------|
| 69.1 | Colori diversi       | 1. Cambia una transazione da Cibo a Trasporti.                | Il pallino e la barra cambiano colore (verde → blu).             | [auto] UcCardsTrackerTest.`category colours…` |
| 69.2 | Menu                 | 1. Apri la finestra, ▾.                                       | Tutte le categorie con il colore; scrivendo si filtra.           | [auto] UcCardsTrackerTest.`TRK-033 the category menu filters…` |
| 69.3 | Nuova categoria      | 1. Scrivi "Palestra". 2. "+ Nuova categoria". 3. Un colore. 4. Salva. | Compare nel menu la volta dopo, col colore scelto, anche nella barra. | [auto] UcCardsTrackerTest.`TRK-033 a new category keeps the colour…` |
| 69.4 | Cambia colore        | 1. Categoria esistente, scegli un altro colore, Salva.        | Tutte le transazioni di quella categoria cambiano colore.        | [ ] |
| 69.5 | Riavvio              | 1. Chiudi e riapri l'app.                                     | Categorie aggiunte e colori restano.                              | [ ] |
| 69.6 | Tema chiaro          | 1. Tema chiaro.                                               | Colori leggibili.                                                 | [auto] screenshot `category-picker-light.png` |

# Recupero del PIN — proposta di flusso

Stato: **implementato** (2026-10-24). Riferimenti: `docs/use-cases.md`
SEC-044 e SEC-140…SEC-146; codice in `crates/bhodl-uniffi/src/commonMain/rust/kdf.rs`
(descrittore v3, codice di recupero) e
`apps/desktop/src/jvmMain/kotlin/io/bhodl/desktop/screens/PinRecovery.kt`.

Differenze rispetto alla proposta:

- Il descrittore v3 non ha un MAC separato: il tag AEAD
  (XChaCha20-Poly1305) autentica salt, parametri e scopo (PIN o codice)
  come dati associati.
- La migrazione da v1/v2 riusa come chiave dati la chiave attuale e la
  riavvolge con un salt nuovo (un Argon2id in più, una sola volta).
- Il codice si crea e si rigenera dall'Hub sicurezza, che lo segnala come
  "Attenzione" finché manca; "Cambia PIN" è nella riga del PIN dell'Hub.
  Non c'è una schermata obbligatoria dopo la creazione del PIN.
- La conferma della cancellazione senza codice usa la stessa frase del
  reset di fabbrica (`DELETE`).

## Il problema

Oggi la chiave che cifra tutti i dati locali (registry SQLCipher, file
`watch-state/*.sqlite`, cache dei tassi) è **derivata direttamente dal
PIN** con Argon2id (`kdf.rs`, `registry.kdf.json`). Conseguenze:

- PIN dimenticato = dati illeggibili, per costruzione. Nessuno (neanche
  noi) può recuperarli: è voluto.
- Dalla schermata di sblocco non c'è nemmeno un modo per ripartire da
  zero: l'utente resta bloccato.
- Non esiste "cambia PIN": cambiarlo vorrebbe dire ri-cifrare ogni file.

Cosa si perde davvero senza PIN: gli **xpub sono pubblici** e si
re-importano, il saldo e le transazioni si ricostruiscono dalla chain.
Si perdono i dati del tracker che esistono solo qui: categorie, regole,
budget, investimenti, lotti, vendite, asset manuali, debiti, obiettivi,
etichette BIP-329, contatti, prezzi personalizzati del costo medio.

## Soluzione proposta: chiave dati + codice di recupero

### 1. Chiave dati separata dal PIN (key wrapping)

- Una **chiave dati** (DEK, 32 byte casuali) cifra tutti i file, come oggi
  fa la chiave derivata dal PIN.
- Il PIN non cifra più i dati: cifra la DEK. `registry.kdf.json` v3
  contiene la DEK cifrata (AEAD, es. XChaCha20-Poly1305) con la chiave
  derivata da Argon2id(PIN, salt).
- **Migrazione senza ri-cifrare nulla**: per chi ha già i dati, la DEK è
  semplicemente la chiave attuale (Argon2id del PIN corrente). Al primo
  sblocco dopo l'aggiornamento si scrive il descrittore v3 con quella
  chiave avvolta. Stesso schema `.next` + promozione già usato per le
  migrazioni KDF (H2), quindi un crash a metà non blocca nessuno.
- Bonus: **"Cambia PIN"** diventa banale (si riavvolge la DEK, nessun
  file ri-cifrato).

### 2. Codice di recupero

- Un secondo involucro della stessa DEK: `registry.recovery.json` con la
  DEK cifrata dalla chiave Argon2id(codice di recupero, salt proprio).
- Il codice: 24 caratteri base32 Crockford in gruppi
  (`K7QM-3XH9-PD2R-…`), ~120 bit di entropia → un attacco offline sul
  file è irrealizzabile anche con il file in mano. Generato dal core Rust
  (`OsRng`), mai salvato in chiaro.
- Mostrato **una volta**, con: copia, stampa / salva come testo, e
  conferma obbligatoria (riscrivere un gruppo scelto a caso) prima di
  proseguire.
- Rigenerabile da Impostazioni → Sicurezza (il vecchio smette di
  funzionare). Lo stato "codice creato sì/no" compare nell'Hub
  sicurezza.

### 3. Flussi utente

**Nuova installazione** — dopo "Crea PIN": schermata "Salva il codice di
recupero" (saltabile con avviso esplicito; l'Hub sicurezza poi lo segnala
come rischio aperto).

**Utente esistente** — al primo sblocco dopo l'aggiornamento: banner
"Crea un codice di recupero: se dimentichi il PIN è l'unico modo per
riaprire i dati", che porta alla stessa schermata.

**PIN dimenticato** — nella schermata di sblocco, link "Ho dimenticato il
PIN":

1. **Ho il codice di recupero** → campo codice (tollerante a spazi,
   trattini, maiuscole/minuscole, `O`/`0`, `I`/`1`) → nuovo PIN + conferma
   → la DEK viene riavvolta col nuovo PIN, si entra nell'app. I tentativi
   sbagliati passano dal `PinAttemptTracker` (scope dedicato, stesso
   lockout persistente del PIN).
2. **Non ho il codice** → spiegazione di cosa si perde e cosa no (lista
   sopra) → conferma scrivendo una parola (es. `CANCELLA`) → cancellazione
   dei dati locali con `LocalWipe` (lo stesso del reset di fabbrica) → si
   riparte dal Welcome e si re-importano gli xpub.

Nessuna scorciatoia via email, cloud o server: BHODL resta locale e
senza account. Il codice è l'unica porta di servizio, ed è in mano
all'utente.

### 4. Sicurezza

- Codice ad alta entropia + Argon2id: il file di recupero sul disco non
  indebolisce la cifratura in modo pratico.
- La DEK resta in memoria solo come oggi (`Zeroizing`, azzerata al
  blocco).
- MAC del descrittore v3 come in v2 (dominio separato); il file di
  recupero ha il proprio MAC.
- Perdere **sia** PIN **sia** codice = dati del tracker persi. Va detto
  chiaramente all'utente nella schermata del codice.

## Piano di implementazione (dopo approvazione)

1. Rust `kdf.rs`: descrittore v3 con DEK avvolta, migrazione v2→v3,
   `recovery.json`, API FFI `create_recovery_code`,
   `unlock_with_recovery_code(code, new_pin)`, `change_device_pin(old,
   new)`, `has_recovery_code()`. Test: migrazione, crash a metà, codice
   errato, codice rigenerato, MAC manomesso.
2. Kotlin: schermata codice di recupero, link "Ho dimenticato il PIN" con
   i due rami, "Cambia PIN" in Impostazioni, voce nell'Hub sicurezza,
   banner per gli utenti esistenti. Stringhe EN/IT.
3. Riattivare il test `SEC-044` (oggi `@Ignore`) e aggiungere i casi
   d'uso nuovi a `docs/use-cases.md` e al test-book.

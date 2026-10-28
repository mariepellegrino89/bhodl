# BHODL — UX/UI Research: Bitcoin wallet + net-worth tracker

> **Note (2026-10-23):** since 2026-10-23 BHODL is watch-only: it holds no private keys
> and cannot send bitcoin. The parts of this document about seeds, signing, sending,
> PayJoin, backups or signing↔tracker mirrors are historical; see `docs/handbook.md`
> for the current design.

**Date of research / access date for every URL below: 2026-10-10.**

**Scope.** BHODL is a desktop-first (Compose Desktop) self-custodial Bitcoin wallet with a built-in net-worth and spending tracker. It takes an opinionated bitcoin-standard line: BTC/sats are the unit of account and fiat appears only on operational surfaces. Its chosen design direction is dense, dark, Bloomberg/Sparrow-like screens with Kubera-style tables, Material Symbols icons and a flat default theme in the Primer style, with a neon theme available as an opt-in.

**Method.** I used primary sources wherever possible: bitcoin.design and its GitHub repo, W3C WCAG 2.2 Understanding docs, Carbon, GOV.UK, Android/kotlinlang docs, vendor docs, help centres, GitHub release notes and official blogs. Third-party reviews were used only for reported weaknesses.

**Confidence markers.**
- **[unverified]** marks a claim from background knowledge that could not be re-confirmed from a fetched page during this research.
- Some release pages show day/month without a year. Years were inferred from version order and are flagged *(year inferred)*.
- Two sources could not be fetched as text: m3.material.io and Apple HIG both need JavaScript to render. Material 3 claims therefore lean on developer.android.com and Flutter's migration docs.

---

## 1. Bitcoin wallet design best practices (Bitcoin Design Guide and related)

The Bitcoin Design Guide (BDG) is mobile- and Lightning-first. **It has no desktop-specific pages.** The nearest thing is the Bitcoin Core App case study (a QML UI shared between desktop and mobile, with a "block clock" that compresses node/sync status into one visual) — https://bitcoin.design/guide/case-studies/bitcoin-core-app/. Most guidance still transfers to desktop. Each subsection lists the patterns (**P**) and anti-patterns (**AP**).

### 1.1 Onboarding / first use
- **P — flexible happy path.** Steer users to best practice but let them skip or customise steps.
  - "Create new wallet" is the primary action and "Recover" the secondary one.
  - Keys are generated locally.
  - Source: https://bitcoin.design/guide/daily-spending-wallet/first-use/
- **P — state responsibility up front.** Make it explicit that "we cannot recover your funds". Users otherwise assume centralized-app customer support exists. (same URL)
- **P — risk-scaled reminders.** Re-prompt for backup and security setup after meaningful deposits. Introduce advanced features only when they become relevant. (same URL)
- **AP:** rigid setups that cannot be skipped, jargon walls, unexplained fees, and hidden responsibility caveats. (same URL)
- The life-cycle and common-flow pages also cover import, watch-only, maintenance (dust sweeps, testing hardware wallets) and problem resolution:
  - https://bitcoin.design/guide/designing-products/usage-life-cycle/
  - https://bitcoin.design/guide/designing-products/common-user-flows/

### 1.2 Backup and recovery-phrase UX
- **Cloud vs manual.** For small-balance spending wallets the BDG defaults to automatic encrypted cloud backup, because accidental loss is a bigger risk there than theft. Manual phrase backup is offered for larger balances. https://bitcoin.design/guide/daily-spending-wallet/backup-and-recovery/landing-page/
- **Cloud backup pattern.**
  - Encrypt client-side, using a user passphrase or a third-party key holder, so that neither the cloud provider nor the key holder can use the backup alone.
  - Suitable for beginners and small amounts, with an upgrade path as balances grow.
  - Not suitable for high values.
  - Downside: without a phrase, the wallet cannot be exported to other software.
  - Source: https://bitcoin.design/guide/how-it-works/private-key-management/cloud-backup/
  - *Desktop has no iCloud/Google-Drive keychain equivalent, so BHODL's "encrypted backup file" is the closest analogue. My inference.*
- **Manual phrase flow.**
  1. Explain what the phrase is before revealing it, with a way back.
  2. Show 12 or 24 numbered words.
  3. Offer an optional printable template.
  4. Verify by having the user tap the words back into order from a shuffled set. Wrong taps are flagged; it is not a multiple-choice quiz.
  - Block screenshots on screens that show the phrase.
  - Source: https://bitcoin.design/guide/daily-spending-wallet/backup-and-recovery/manual-backup/
- **Print template** (Figma: https://www.figma.com/community/file/1110806582648546839).
  - Pre-fills the non-secret metadata: wallet name, derivation path, and the descriptor as a QR.
  - The words themselves are handwritten.
  - Source: https://bitcoin.design/guide/daily-spending-wallet/backup-and-recovery/manual-backup/
- **AP:** screenshots, plain-text copies (notes, Google Docs), onboarding outside the home, and expecting beginners to manage manual backups. https://bitcoin.design/guide/how-it-works/private-key-management/manual-backup/
- **Backup "levels".**
  - The levels run 0 (outsourced) → paper → metal → passphrase stored separately → geographic redundancy.
  - Leave next-of-kin documentation that contains no secrets.
  - The page advises against Shamir sharding for most users.
  - Source: https://bitcoin.design/guide/how-it-works/backups/
- **Interoperability.**
  - Use BIP39 plus output descriptors.
  - Offer a downloadable PDF with wallet name, software and version, script type and descriptors.
  - Source: https://bitcoin.design/guide/designing-products/interoperability/
  - Muun's "Emergency Kit" PDF is an example of this pattern in a shipping product: https://apps.apple.com/us/app/muun-wallet/id1482037683
- **Restore UX.**
  - Autocomplete words from the 2048-word list.
  - Validate each word inline (invalid in red, a complete phrase in green).
  - Show explicit progress states: searching → restoring → restored.
  - Set expectations about how long scanning takes.
  - **AP:** validating only at the end.
  - Source: https://bitcoin.design/guide/daily-spending-wallet/backup-and-recovery/recovery/
  - Wasabi users were confused by long syncs and "missing balance after restore": https://stacker.news/items/470744. Wasabi later added a dedicated sync-progress screen (2.8.1): https://github.com/WalletWasabi/WalletWasabi/releases

### 1.3 Receiving
- **P:** a new address per receive, easy generation of more addresses, and a warning before *sending to* an already-used address. https://bitcoin.design/guide/how-it-works/wallet-privacy/
- **P:** don't show the balance on the request screen, because it leaks to onlookers. https://bitcoin.design/guide/daily-spending-wallet/requesting/
- **P — amount field units.**
  - The amount field toggles between sats, BTC and fiat.
  - **Clear the field when the unit changes** so a value isn't silently reinterpreted.
  - Zero-amount requests are allowed.
  - Source: (same URL)
- **P — unified requests.**
  - Make a unified BIP21/BIP321 request (on-chain plus Lightning) the default, while still allowing a plain address.
  - Call a Lightning invoice a "payment request", not an "address".
  - Sources: https://bitcoin.design/guide/how-it-works/payment-request-formats/ and https://bitcoinqr.dev/
- **P — QR and address rendering.**
  - Uppercase bech32 inside QR codes for more efficient encoding.
  - Use animated QR for PSBTs.
  - Show addresses in a monospace face with chunking or colour, and always offer the full untruncated form.
  - Sources: https://bitcoin.design/guide/designing-products/interoperability/, https://bitcoin.design/guide/glossary/address/
- Shipping implementations of chunked addresses:
  - Sparrow 2.4.0 shows 4-character address chunks (can be turned off): https://github.com/sparrowwallet/sparrow/releases
  - BlueWallet 8.0.0 highlights address segments: https://github.com/BlueWallet/BlueWallet/releases
- **P — label-first receive.** Liana v15 asks for a label before generating the address: https://github.com/wizardsardine/liana/releases. The BDG also asks that metadata be captured at request time and included in backups: https://bitcoin.design/guide/daily-spending-wallet/requesting/
- **Accessibility:** label QR images for screen readers ("QR code to copy the receive address"). https://bitcoin.design/guide/designing-products/accessibility/

### 1.4 Sending and fee selection
- **P — input parsing.**
  - Accept many inputs (paste, scan, URI, contact).
  - Auto-detect the format and give human-readable errors.
  - Warn on a type mismatch.
  - Show amounts in BTC/sats and fiat with a toggle.
  - Source: https://bitcoin.design/guide/daily-spending-wallet/sending/
- **P — review and consent.**
  - Always show a review screen and highlight anything unusual.
  - Authentication (PIN) comes **last**, as final consent.
  - Don't block the UI while a transaction is pending.
  - Let the user label the transaction while it waits.
  - Show an explorer link as proof on success.
  - Source: (same URL)
- **P — fee presets.**
  - Fees are computed dynamically.
  - Offer 2–3 **urgency presets** framed as *total cost + time*.
  - Custom sat/vB is a secondary path.
  - Warn when the fee is ≥ ~50% of the amount (threshold configurable).
  - Offer "fee included in amount" for send-max.
  - Source: https://bitcoin.design/guide/daily-spending-wallet/send-fees/
- **AP:** silent overpayment, custom fee as the default path, and a buried fee-included option. (same URL)
- **Competitor state of the art for power users.** Sparrow's Send tab includes:
  - a fee-rate chart / block-target selector;
  - a **Recent Blocks** view (2.2.0);
  - multiple estimator sources (mempool.space, block.xyz, bitview.space);
  - a high-fee guard (2.4.0).
  - Sources: https://sparrowwallet.com/features/, https://www.nobsbitcoin.com/sparrow-wallet-v2-2-1/, https://github.com/sparrowwallet/sparrow/releases
  - Sub-1 sat/vB fees are now accepted by Electrum 4.6.2 (relay floor 0.1 sat/vB), BlueWallet 7.2.0 and Wasabi 2.8.0:
    - https://raw.githubusercontent.com/spesmilo/electrum/master/RELEASE-NOTES
    - https://github.com/BlueWallet/BlueWallet/releases
    - https://github.com/WalletWasabi/WalletWasabi/releases
- **P — batching into an unconfirmed transaction.** Electrum 4.6's **batching dropdown** lets the user join a new payment to an unconfirmed transaction of their own. https://raw.githubusercontent.com/spesmilo/electrum/master/RELEASE-NOTES

### 1.5 RBF / CPFP and transaction states
- **What the BDG covers.** It gives only one paragraph: offer "cancel (RBF)" and "speed up (CPFP)" between broadcast and inclusion in a block. https://bitcoin.design/guide/daily-spending-wallet/sending/
  - That mapping is too loose. RBF can both speed up and cancel; CPFP is the receiver-side or child-based bump. https://bitcoinops.org/en/scaling/fee-bumping/
- **Industry wording.**
  - Trezor uses "Speed up": https://trezor.io/learn/supported-assets/bitcoin/speed-up-a-stuck-bitcoin-transaction-with-replace-by-fee-rbf
  - Ledger's "cancel" is an RBF send-back-to-self: https://help.enterprise.ledger.com/helpcenter2/network-specific-guidance/bitcoin-replace-by-fee
  - Blockstream uses "Increase fee": https://help.blockstream.com/hc/en-us/articles/900002241863-Speed-up-transaction-confirmation
- **Sparrow 2.5.5:** includes descendant fees when bumping and allows replacing CPFP children. https://github.com/sparrowwallet/sparrow/releases
- **The state lifecycle** is created → signed → broadcast → pending (0 conf) → confirmed (+1 per block). The BDG treats 6 confirmations as final. https://bitcoin.design/guide/how-it-works/transactions/
- **Activity list rules.**
  - Completed transactions need **no badge**. Show status only when a transaction is pending, has failed, or has just completed.
  - Show received amounts with "+".
  - **AP: red for sent amounts.** Reserve red for errors.
  - Never use colour as the only signal.
  - Keep technical details (txid, inputs) in an expandable section.
  - Offer search, category views and export.
  - Source: https://bitcoin.design/guide/daily-spending-wallet/activity/
- **Gap:** the BDG doesn't define *replaced* or *failed/evicted* states.
  - Recommended treatment: collapse a replaced transaction under its replacement, with a "Replaced by fee bump" chip and a link.
  - Show "Dropped from mempool" as a recoverable error with a "Rebroadcast / Bump" call to action.
  - This is design inference, not a cited standard.

### 1.6 Coin control and labels
- **P — who gets coin control.**
  - Auto coin selection is the default for introductory wallets.
  - Manual coin control is opt-in (a review-screen toggle or a "privacy mode") and paired with labels.
  - Let users choose an optimisation goal: cost, speed or privacy.
  - Source: https://bitcoin.design/guide/how-it-works/coin-selection/
- **P — freeze, consolidation and dust.**
  - Explain the trade-offs of freezing.
  - Make consolidation deliberate, with its cost and privacy impact explained.
  - Surface dust (<~500 sats).
  - **AP:** manual control as the default, or letting novices pick tiny or unknown UTXOs (dust-attack risk).
  - Source: (same URL)
- **Sparrow "Optimize: Efficiency / Privacy".**
  - Privacy mode builds a transaction shaped like a 2-party coinjoin.
  - An **Analysis…** tooltip explains the result.
  - Source: https://sparrowwallet.com/docs/spending-privately.html
- **Nunchuk coin control** is the best-in-class organisational model:
  - coins inherit the parent transaction's note;
  - colour **tags**;
  - **Collections with auto-rules** (auto-lock new coins, auto-tag by address);
  - bulk Selection Mode;
  - search by note, tag, address, txid, amount or date *inside the send flow*;
  - export as BIP329 or a superset format.
  - Source: https://nunchuk.io/blog/coin-control
- **BIP329.**
  - Status: Draft, Informational.
  - Format: JSON Lines. Record types: `tx`, `addr`, `pubkey`, `input`, `output`, `xpub`. `spendable` applies to outputs only.
  - A malformed line must not abort the import.
  - Labels are suggested to be at most 255 characters, with a warning on truncation.
  - Exports are privacy-sensitive; encryption is recommended.
  - Source: https://github.com/bitcoin/bips/blob/master/bip-0329.mediawiki
  - Adoption: Sparrow (since 1.7.2) https://bitcoinops.org/en/topics/wall-labels/, BlueWallet 8.0.2 https://github.com/BlueWallet/BlueWallet/releases, Nunchuk.
  - The BDG itself does not mention BIP329.

### 1.7 Units: sats vs BTC, and the BIP177 status
- **BDG defaults.**
  - Default to BTC with 8 decimals on-chain and sats for Lightning, with an app-wide or contextual switch.
  - Group digits with thin spaces or colour. "Satcomma" is a proposal without consensus.
  - Use **monospace/tabular digits and trailing zeros in right-aligned columns**, plus slashed zeros.
  - Format per locale.
  - Source: https://bitcoin.design/guide/designing-products/units-and-symbols/
- **Fiat rounding rules.**
  - Never show "€0.00" for a non-zero amount.
  - Keep significant digits for sub-cent values.
  - Use "~" for rounded approximations (e.g. ~$0.39).
  - Source: (same URL)
- **"₿-only" format (the BDG's application of BIP177).**
  - Amounts are integers prefixed with ₿, so 0.00005449 BTC → **₿5,449** and 3.25 BTC → **₿325M**.
  - Fiat appears beneath.
  - The guide suggests deprecating decimals and the word "sats" in the UI, with explanatory copy when users switch.
  - Adopters listed "as of January 2026": Bitkit, Wallet of Satoshi, Blitz, Boardwalk, StashPay, Cashu.me, Lexe, Agicash, **Cash App**, Alby Hub, Ecash App. **Square POS** (US) has used ₿-only by default since Nov 2025.
  - Sources: https://bitcoin.design/guide/designing-products/units-and-symbols/ and repo history https://github.com/BitcoinDesign/Guide/commits/master
- **BIP177 itself.**
  - Status: Draft, Informational, assigned 2025-04-23, author John Carvalho. No consensus change.
  - The ₿ symbol is optional.
  - It recommends a toggle between legacy and integer display, and a phased rollout: dual display for 3–6 months, then default.
  - Source: https://github.com/bitcoin/bips/blob/master/bip-0177.mediawiki
- **The debate.**
  - Critics cite: losing the "sat" name, confusion over BTC vs "bitcoin" in speech and translation, and muddling the "21M" scarcity narrative. Jack Dorsey publicly backed it.
  - Sources: https://cointelegraph.com/news/sats-vs-bits-debate-bitcoin-adoption-amid-base-unit-change-bip, https://decrypt.co/320942/goodbye-satoshi-jack-dorsey-wants-bitcoin-rebrand, https://blog.bitfinex.com/education/what-is-bip177-and-the-bitcoin-name-debate/
- **Tension with dense tables.** The BDG activity page calls repeating a bitcoin symbol on every amount "visually distracting". https://bitcoin.design/guide/daily-spending-wallet/activity/
  - For dense tables, put the unit in the **column header** ("Amount (₿)" or "Amount (sats)") and keep cells bare.
- **Sparrow 2.5.5 "auto" unit.** Wallets set to "auto" switch their tables from sats to BTC once they receive ≥1 BTC. This is an interesting middle ground. https://github.com/sparrowwallet/sparrow/releases
- **AQUA 0.4.0** offers a BTC / Bits / Sats picker. https://jan3.com/blog/aqua-wallet-redesigned

### 1.8 Privacy
- **P — privacy defaults.**
  - Fresh addresses and a reuse warning.
  - Visible labels and coin control.
  - A Tor toggle, with an honest note that startup will be slower.
  - Provider choice and kill switches for third-party services (exchange rates).
  - The ability to hide balances, addresses and history.
  - Inform users at the point a transaction is created.
  - Source: https://bitcoin.design/guide/how-it-works/wallet-privacy/
- **AP:** moving funds between your own wallets on-chain, and static identifiers. (same URL)
- **Node choice.**
  - Let users connect their own node.
  - Prefer compact filters (BIP157/158 / Neutrino) over SPV.
  - Source: https://bitcoin.design/guide/designing-products/interoperability/
  - Sparrow's status bar shows the server type (public / Bitcoin Core / private Electrum) as a coloured toggle: https://sparrowwallet.com/docs/quick-start.html, https://sparrowwallet.com/docs/best-practices.html
- **PayJoin case study.**
  - On by default for the sender, and nearly identical to a normal send.
  - A **fee range** (three options) replaces an exact fee.
  - A visible "handshake" step, then a final-fee review.
  - The receiver adds the endpoint to every BIP21.
  - **AP:** round fee rates (a chain-analysis fingerprint), fee drift during the handshake, silent failure when the hot wallet is empty, and dead ends for watch-only/cold wallets.
  - Source: https://bitcoin.design/guide/case-studies/payjoin/
- **PayJoin in the field.**
  - Bull Bitcoin was the first Play Store wallet with BIP77 async PayJoin and shows a "Payjoin invoice" on Receive: https://nobsbitcoin.com/bull-bitcoin-wallet-v0-4-0
  - Its 6.13.0 release (Aug 2026) adds a **minimum amount** and **session lifetime** setting: https://github.com/SatoshiPortal/bullbitcoin-mobile/releases
  - A UTXO-probing leak was reported against PayJoin receivers: https://uncensoredtech.substack.com/p/utxo-probing-attack-using-payjoin
  - Sparrow 2.5.4 tightened PayJoin proposal validation and disables PayJoin when there are silent-payment outputs: https://github.com/sparrowwallet/sparrow/releases
- **Hide balances.**
  - Put a toggle next to the balance: one tap hides, a **long-press reveals** (to avoid accidental reveals). Optionally gate the reveal with a PIN, or start hidden by default.
  - Source: https://bitcoin.design/guide/daily-spending-wallet/privacy/
  - Sparrow masks amounts in both coin and fiat controls **and strips the tooltips and context menus that would leak values**: https://code.sparrowwallet.com/sparrowwallet/sparrow/commit/73da024645913aa9139afe40497dc69c44735034 (View-menu toggle in 2.3.1: https://github.com/sparrowwallet/sparrow/releases)

### 1.9 Security UX
- **PIN and biometrics.**
  - A 4–6 digit PIN to open the app *and* for sensitive actions: viewing the phrase, large sends, deleting the wallet.
  - Block trivial PINs (1234, 1111).
  - Limit attempts per day.
  - Optionally scramble the keypad.
  - Warn that a forgotten PIN may mean data loss.
  - Source: https://bitcoin.design/guide/daily-spending-wallet/security/
- **Duress options.** A decoy PIN that opens a dummy wallet, and a wipe PIN. (same URL)
- **Spending-limit changes** require a separate password, a time delay, or signing-device approval.
  - https://bitcoin.design/guide/daily-spending-wallet/security/
  - https://bitcoin.design/guide/savings-wallet/
- **P:** a security checklist with spaced, dismissible reminders. **AP:** interrupting a send with reminders. (same URL)
- **Gap:** the BDG does not specify auto-lock on inactivity or lockout durations, so BHODL must define its own policy.
- **"Security Hub" pattern.**
  - Bitkey 2025.10.0 puts all security and recovery settings on one screen: https://bitkey.world/releases
  - Blockstream Desktop 3.0.0 has a dedicated Security tab: https://blog.blockstream.com/blockstream-desktop-app-3-0-0-less-friction-more-clarity/
- **WCAG 3.3.8 (Accessible Authentication, AA).**
  - A memorised PIN counts as a cognitive function test unless there is an alternative or a "mechanism", such as paste or a password manager.
  - OS authentication (Windows Hello, Touch ID) qualifies as an alternative.
  - Blocking paste fails the criterion unless an alternative exists.
  - Source: https://www.w3.org/WAI/WCAG22/Understanding/accessible-authentication-minimum.html
  - **Implication:** let users paste into the PIN/passphrase field, or offer OS biometric unlock.

### 1.10 Errors and accessibility
- **Errors** should say what happened, **the state of the funds**, how to fix it, and what to do next. Categorise them as identifiable, needing user action, or auto-fixable. When a fix has a cost, let the user choose. https://bitcoin.design/guide/daily-spending-wallet/sending/
- **NN/g error guidance:** place the message next to its source, use plain language without raw error codes, and preserve the user's input. https://www.nngroup.com/articles/error-message-guidelines/
- **BDG accessibility guidance.**
  - Functional labels on every control.
  - A logical focus order (key for desktop keyboard use).
  - Never colour alone; use +/−.
  - Respect reduced motion and increased contrast.
  - Defer to WCAG/WCAG2ICT for numbers.
  - Source: https://bitcoin.design/guide/designing-products/accessibility/
- **State of the field:** a July 2025 AppleVis survey found major screen-reader blockers in nearly every bitcoin wallet tested. That makes accessibility a real differentiator. https://applevis.com/forum/accessibility-advocacy/bitcoin-wallets-accessibility-survey

### 1.11 Reference designs worth reading for BHODL's roadmap
- Savings wallet (2-of-3, PSBT on SD card, limit changes gated by the signing device): https://bitcoin.design/guide/savings-wallet/
- Upgradeable wallet: https://bitcoin.design/guide/upgradeable-wallet/
- Multiple wallets: https://bitcoin.design/guide/multiple-wallets/
- Inheritance: https://bitcoin.design/guide/inheritance-wallet/
- **Personal finance** (directly relevant to the tracker): https://bitcoin.design/guide/designing-products/personal-finance/
- SeedSigner case study (Apr 2026). Its lesson is that **half of the signing UX lives in the coordinator app**, which applies to BHODL's PSBT review: https://bitcoin.design/guide/case-studies/seedsigner/
- Ecash/Cashu pages in the guide repo (`guide/how-it-works/ecash/`), relevant to the Cashu roadmap.
- Bitcoin UI Kit (https://www.bitcoinuikit.com/) and icon set https://bitcoinicons.com, both linked from https://bitcoin.design/guide/resources/design-files/. I could not verify the kit's contents.

---

## 2. General UX/UI best practices relevant to BHODL

### 2.1 Data-dense financial tables
- **Density modes (IBM Carbon).**
  - Row heights: xs 24 / sm 32 / **md 40 (default)** / lg 48 / xl 64 px. xs is for "highly dense layouts".
  - The header row height must match the row height.
  - The toolbar height follows the row height, with at most 5 toolbar actions plus overflow.
  - Zebra stripes are optional, to aid horizontal scanning.
  - Source: https://carbondesignsystem.com/components/data-table/usage/
  - → BHODL should offer **Compact (32) / Comfortable (40)** density, plus possibly an xs 24 for power users. But see target-size note §2.6.
- **Sorting.**
  - Three states (none / asc / desc).
  - Only the active column shows its arrow; unsorted columns show an arrow on hover.
  - Source: (same URL)
- **Row actions.**
  - Fewer than 3 actions → inline icon buttons. More → an overflow (⋮) menu, optionally shown only on hover or focus.
  - When a batch-action bar is active, disable per-row actions.
  - Source: (same URL)
- **Column titles.** One or two words; wrap to 2 lines, then truncate with a tooltip. (same URL)
- **Alignment.**
  - **Right-align numbers you compare**: https://design-system.service.gov.uk/components/table/
  - Left-align "nominal numbers" (IDs, block heights used as identifiers, txids): https://spectrum.adobe.com/page/table
- **Decimal alignment.**
  - True decimal alignment is not natively available. The practical substitute is a **fixed number of decimals plus tabular figures**.
  - For BTC that means always 8 decimals, or integer sats, never mixed within one column.
  - Sources: https://lists.w3.org/Archives/Public/public-html-bugzilla/2012Jan/0655.html, https://mattstromawn.com/writing/tables/
  - The BDG makes the same point about trailing zeros: https://bitcoin.design/guide/designing-products/units-and-symbols/
- **Tabular figures.**
  - OpenType `tnum` gives equal-width digits. `zero` gives a slashed zero, which helps with txids and addresses.
  - Source: https://developer.mozilla.org/en-US/docs/Web/CSS/font-variant-numeric
  - Compose `TextStyle(fontFeatureSettings = "tnum")` is available: https://composables.com/jetpack-compose/androidx.compose.ui/ui-text/classes/TextStyle *(exact syntax [unverified])*. It only works if the font ships `tnum`.
  - **Recommendation:** use a proportional UI font with `tnum` (Inter / IBM Plex Sans have it) for amounts, and **monospace only for addresses, txids, xpubs and descriptors**.
- **Number formatting.**
  - Accounting-style negatives use parentheses: `currencySign:"accounting"`.
  - `signDisplay:"exceptZero"` gives P&L deltas ("+5.2%").
  - Compact notation ("988M").
  - Set `maximumFractionDigits` explicitly (the default is 3, or 2 for currency).
  - **Truncate rather than round spendable balances**, so the app never shows more than is spendable.
  - Source: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl/NumberFormat/NumberFormat
  - JVM equivalents: `DecimalFormat` / ICU4J *[unverified mapping]*.

### 2.2 Colour for gains/losses, colour-blind safety
- **Prevalence:** red-green colour-vision deficiency affects ~8% of Caucasian males, ~5% of Asian males and ~4% of African males. https://jfly.uni-koeln.de/color/
- **Dark-background warning.** Dark red reads almost as black to protanopes: "avoid red characters on dark background". Use vermilion or light red instead. (same URL) This matters directly for a dark-heavy UI.
- **Okabe-Ito palette:** #E69F00, #56B4E9, **#009E73** (bluish green), #F0E442, **#0072B2** (blue), **#D55E00** (vermillion), #CC79A7. https://search.r-project.org/CRAN/refmans/see/html/okabeito_colors.html
  - A safe gain/loss pair: bluish-green or blue for gains vs vermillion for losses. Lighten them on dark surfaces to meet 4.5:1.
- **WCAG 1.4.1 Use of Color (A):** colour must never be the only signal, so always add +/−, ▲/▼ or parentheses. https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html
- **Regional conventions.** Red = up in China, Japan and Taiwan; Korea varies.
  - https://www.benzinga.com/general/education/21/10/23258001/did-you-know-you-dont-want-to-be-in-the-green-if-youre-trading-in-china-japan-or-taiwan
  - https://data.europa.eu/apps/data-visualisation-guide/colour-connotations
  - Brokers expose a swap setting: https://moomoo.com/support/topic3_22
- **BDG-specific rule:** received = "+" in green, but **sent ≠ red**. Red is reserved for errors. https://bitcoin.design/guide/daily-spending-wallet/activity/
  - This deliberately conflicts with the trading-terminal convention. BHODL should apply it to the **wallet activity** list and use the gain/loss palette only for **P&L/valuation** columns.

### 2.3 Dashboard information architecture
- **Dashboard types.** Dashboards are at-a-glance, single-view, low-interaction surfaces. They come in **operational** (act now) and **analytical** (prompt investigation) forms. https://www.nngroup.com/articles/dashboards-preattentive/
- **Chart forms.** Length and 2-D position are the most accurate visual encodings; area, angle and colour are weaker.
  - Avoid pies/donuts and 3-D.
  - Prefer **bullet charts** to radial gauges. Bullet charts fit savings goals, FIRE progress and budget burn well.
  - Source: (same URL)
- **Colour** should reinforce meaning, not carry magnitude. (same URL)
- **KPI tiles** (practitioner sources): top-row KPIs with trend indicators, about 5 metrics rather than 15, readable in about 5 seconds.
  - https://datapad.io/docs/guides/dashboard-best-practices
  - https://www.geckoboard.com/blog/dashboard-design/
- **Cognitive load:** remove clutter, use familiar patterns and smart defaults. https://www.nngroup.com/articles/minimize-cognitive-load/

### 2.4 Desktop app conventions (Compose Desktop)
- **Keyboard handling.**
  - `Modifier.onPreviewKeyEvent` handles screen-wide shortcuts (it is passed down to children first). `onKeyEvent` handles focused elements.
  - Window-level handlers: `Window(onPreviewKeyEvent=…)`.
  - Source: https://kotlinlang.org/docs/multiplatform/compose-desktop-keyboard.html
- **Menus, tray and window state.**
  - `MenuBar { Menu { Item } }` builds the menu bar.
  - `Tray(...)` adds a tray icon. "Hide to tray on close" is done with `onCloseRequest = { isVisible = false }`.
  - Persist window size and position with `snapshotFlow`.
  - Source: https://kotlinlang.org/docs/multiplatform/compose-desktop-top-level-windows-management.html
- **Compose Multiplatform 1.12** (Sept 2026, experimental Window API v2) adds **`minSize`/`maxSize`**, `CenteredOnScreen` and `requestBounds()`. https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html
  - Use these to enforce a minimum window size, e.g. 1024×640.
- **Windows accelerators.**
  - Ctrl+F find, F3 next, F5/Ctrl+R refresh, Ctrl+N new, F2 rename, Del delete (with undo), **F6/Shift+F6 to cycle panes**, Ctrl+±/0 zoom, Ctrl+W close.
  - Show shortcuts in menus and tooltips ("Copy (Ctrl+C)").
  - Source: https://learn.microsoft.com/en-us/windows/apps/design/input/keyboard-accelerators
  - Don't override the macOS ⌘ standards *[unverified — HIG not fetchable]*.
  - Sparrow precedent: Ctrl+Shift+←/→ switches tabs (2.3.1). https://github.com/sparrowwallet/sparrow/releases
- **Window size classes.**
  - Width: Compact <600dp, Medium 600–839, Expanded 840–1199, **Large 1200–1599, Extra-large ≥1600 ("desktop")**. Large and XL are opt-in via `supportLargeAndXLargeWidth = true`.
  - Height: Compact <480, Medium 480–899, Expanded ≥900.
  - Source: https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
- **Canonical layouts.**
  - **List-detail** (`ListDetailPaneScaffold`) — transactions → detail.
  - **Supporting pane** (70/30 at expanded, bottom sheet at compact) — e.g. a goals sidebar.
  - **Feed** — e.g. a card dashboard.
  - Source: https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts
- **Navigation.** `NavigationSuiteScaffold` gives a bar on compact screens, a rail otherwise, and an opt-in permanent drawer at ≥ expanded width. https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation
  - → BHODL's sidebar can become a rail on medium widths and a bottom bar on mobile, using the same destinations.
- **Desktop accessibility.**
  - Fully supported on macOS.
  - On Windows it works via Java Access Bridge, which is **off by default**. Native distributions must include `modules("jdk.accessibility")`.
  - Linux is unsupported.
  - Test with NVDA or JAWS.
  - Source: https://kotlinlang.org/docs/multiplatform/compose-desktop-accessibility.html
  - Traversal order can be set with `isTraversalGroup` / `traversalIndex`: https://kotlinlang.org/docs/multiplatform/compose-accessibility.html
- **Compose Multiplatform versions.**
  - Current CMP is 1.12.1, with Material3 1.12.0-alpha03 and Material3 Adaptive 1.3.0-rc01: https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html
  - CMP 1.10 brought stable Hot Reload and Navigation 3 on desktop: https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html

### 2.5 Material 3 relevant guidance
- **Surface colours.** M3 replaced elevation overlays with **tone-based surface roles**: `surfaceContainerLowest` … `surfaceContainerHighest`. Elevation +1 → `surfaceContainerLow`, +2 → `surfaceContainer`. https://docs.flutter.dev/release/breaking-changes/new-color-scheme-roles (M3 blog title: https://m3.material.io/blog/tone-based-surface-color-m3)
  - Use these roles to give dense panels hierarchy without shadows. This fits the "Primer-flat" look.
- **Dark theme.** The legacy M2 dark guidance was #121212 rather than pure black, with desaturated accents and 87/60/38% text emphasis. https://m2.material.io/design/color/dark-theme.html *(body text [unverified])*
- **M3 Expressive (2025).** Google reported key actions found up to 4× faster, based on 46 studies. https://chromeunboxed.com/google-leaks-the-reason-and-process-behind-their-new-material-3-expressive-design-language
  - Its focus on shape and motion is mostly irrelevant to a Bloomberg-style UI. Keep M3 tokens and components, and skip the expressive shapes.

### 2.6 WCAG 2.2 AA checklist for BHODL
| SC | Requirement | BHODL implication |
|---|---|---|
| 1.4.3 | Text contrast 4.5:1 (3:1 for ≥24px or ≥18.5px bold) — https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html | Dim secondary text in dense tables is the usual failure. Test the "muted" token on every surface tone. |
| 1.4.11 | Non-text contrast 3:1 for UI parts, focus rings and chart elements; 2.999 fails — https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html | Chart series lines, gridlines that carry meaning, toggle borders, and neon-on-dark accent outlines. |
| 2.4.7 | Visible keyboard focus — https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html | Table rows need a visible focused-row state distinct from the selected state. |
| 2.4.11 | Focus not entirely obscured; a sticky header covering focus is failure F110 — https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html | With sticky table headers, scroll the focused row clear of the header. |
| 2.5.8 | Target ≥24×24 px, or spacing exception — https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html | xs (24px) rows with icon buttons are at the limit. Inline row icons need a 24px hit area. |
| 3.3.7 (A) | Redundant entry — https://www.w3.org/WAI/WCAG22/Understanding/redundant-entry.html | "Confirm new PIN" is fine (security exception). Prefill the address and amount when editing a draft send. |
| 3.3.8 | Accessible authentication — https://www.w3.org/WAI/WCAG22/Understanding/accessible-authentication-minimum.html | Allow paste and password managers for the PIN/passphrase, or provide OS-auth unlock. |

### 2.7 Empty, loading and error states; progressive disclosure
- **Loading (NN/g).**
  - Under 1 s: no indicator.
  - 2–10 s for a single module: a spinner on that module.
  - Full page under 10 s: a **skeleton**. Avoid frame-only skeletons.
  - Over 10 s: a progress bar with an estimate.
  - Source: https://www.nngroup.com/articles/skeleton-screens/
  - → A wallet sync or Esplora history backfill needs a progress bar with block height or percentage.
- **Empty states (Carbon).**
  - Three types: no data (first use), user action (no search results), and error management.
  - Anatomy: title, body and primary/secondary CTA.
  - **Replace** the empty table rather than rendering bare headers.
  - Never a dead end. Use tertiary buttons when several empty states are on screen.
  - Source: https://carbondesignsystem.com/patterns/empty-states-pattern/
- **Progressive disclosure (NN/g):** show frequent options up front and advanced ones behind a clearly labelled path, with **no more than 2 levels**. https://www.nngroup.com/articles/progressive-disclosure/
- **Privacy mode.**
  - **Revolut** blurs balances on shake or flip. Hidden accounts need biometrics and are excluded from totals.
    - https://help.revolut.com/en-MX/help/profile-and-plan/security-and-personal-data/why-is-my-balance-blurry/
    - https://www.revolut.com/blog/post/new-revolut-security-features/
  - **Monarch** has a "Demo Mode" (mobile only) that masks balances, net worth, transactions and charts and blocks edits. https://help.monarch.com/hc/en-us/articles/46153716425876-Demo-Mode
  - **BlueWallet** lets you hide a wallet per wallet ("for use in public places"). https://bluewallet.io/docs/manage-wallets/
  - Balance-only hiding is weak privacy; transactions and addresses still leak. https://hackmd.io/@peakshift/rJ8EqI7Aw
- **Trust signals.** No authoritative source was found; only practitioner consensus: consistency, transparent fees and data practices, plain errors, and easy-to-find security information. https://phenomenonstudio.com/article/fintech-ux-design-patterns-that-build-trust-and-credibility/ *(low authority)*
  - In bitcoin wallets the stronger trust signals are concrete:
    - visible server/node type (Sparrow status bar);
    - open-source and reproducible builds;
    - showing **what is being signed** (inputs/outputs diagram).

---

## 3. Competitor teardown: Bitcoin wallets

Ordered by relevance to BHODL.

### 3.1 Sparrow Wallet (2.5.5, Sept 2026) — the primary reference
- **Platform and target user.** Desktop (JavaFX), Windows/macOS/Linux. Power users, with a stated path for beginners: "full control and transparency… simple enough for beginners to start and grow". https://pretalx.com/abcpt26/speaker/SENMHM/
- **Visual style.** Dense and utilitarian.
  - Light and dark themes, with a **System theme following the OS** as the new default in 2.5.5. https://github.com/sparrowwallet/sparrow/releases
  - Wallet tables and charts (a balance chart covering a full year as of 2.5.2/2.5.5). (same URL)
- **IA and navigation.**
  - One top-level tab per open wallet.
  - Inside each wallet, left sub-tabs: **Transactions / Send / Receive / Addresses / UTXOs / Settings**. They are grey until the wallet is valid, then blue. https://sparrowwallet.com/docs/quick-start.html (Send/UTXOs naming *[unverified]*)
  - A status bar with an online toggle colour-coded by server type. (same URL)
  - Ctrl+Shift+←/→ moves between tabs.
- **Standout patterns.**
  - Fee-rate chart plus a **Recent Blocks** view in Send. https://www.nobsbitcoin.com/sparrow-wallet-v2-2-1/
  - An editable input/output **transaction diagram**. https://sparrowwallet.com/features/
  - **Optimize: Efficiency/Privacy** with an Analysis tooltip. https://sparrowwallet.com/docs/spending-privately.html
  - UTXO treemap *(third-party description)*: https://www.spark.money/tools/bitcoin-utxo-visualization-tool-comparison
  - Hide amounts from the View menu.
  - 4-character address chunking.
  - Unit-aware CSV exports.
  - **Auto unit switching at ≥1 BTC.**
  - BIP329 import/export, including QR export (2.1.3: https://www.nobsbitcoin.com/sparrow-wallet-v2-1-3/).
  - Silent payments send (2.3.0) and receive (2.5.0).
  - Built-in Tor; Cormorant (a Bitcoin Core-backed server).
  - TOFU certificate pinning; checkpointed headers and Merkle proof verification (2.5.4).
  - Sources: https://github.com/sparrowwallet/sparrow/releases, https://sparrowwallet.com/features/
- **Weaknesses.**
  - Intimidating density for novices (widely held; no 2025 primary source found).
  - Desktop-only.
  - Late security hardening (owner-only directories and log redaction only in 2.5.4).
  - JDK regressions. https://github.com/sparrowwallet/sparrow/releases
- **For BHODL.**
  - **Borrow:** wallet sub-tabs, the server-type status bar, the fee chart plus Recent Blocks, the privacy optimizer with its explanation, hide amounts that also kill tooltips, chunked addresses, and the auto unit.
  - **Beat it on:** finance context (labels → categories → reports), friendlier first run, and global search.

### 3.2 Electrum (4.8.2, Sept 2026)
- **Platform and target user.** Desktop Qt6 plus Android. Veterans and Lightning/CLI users.
- **IA.**
  - Tabs: History / Send / Receive, plus optional Addresses / Coins / Contacts / Channels via the View menu *[unverified]*.
  - Addresses beyond the gap limit are shown red.
  - Freeze address. https://electrum.readthedocs.io/en/latest/faq.html
- **Standout patterns.**
  - **Batching dropdown** (join an unconfirmed transaction).
  - "Accounting addresses" for force-close sweeps.
  - Submarine payments (4.7.0).
  - A Nostr cosigner plugin.
  - Timelock-recovery plugin.
  - Sources: https://raw.githubusercontent.com/spesmilo/electrum/master/RELEASE-NOTES
- **Weaknesses.**
  - Historic phishing via malicious servers rendering rich-text "errors". https://www.schneier.com/?p=11903
  - Servers can still censor. https://bitcoinelectrum.com/blog
  - A 2026 anchor-channel backup bug forced users to re-export backups (4.8.2).
  - Dated UI (anecdotal).
- **For BHODL.**
  - Borrow batching and the "accounting addresses" concept, so internal sweeps don't distort the tracker's P&L.
  - **Never render server-supplied text.**

### 3.3 Nunchuk
- **Platform and target user.** iOS, Android and a Qt desktop app. https://www.linuxlinks.com/nunchuk-desktop-qt-based-bitcoin-wallet-client/ Multisig, families, inheritance.
- **Standout patterns.**
  - Coin control with **tags, collections, auto-rules, note inheritance and in-flow search**. https://nunchuk.io/blog/coin-control
  - **Group Wallet** (rebuilt Feb 2025): descriptor-derived encryption, E2E chat, MuSig2 Taproot, BSMS recovery. https://nunchuk.io/blog/group-wallet
  - Miniscript (Aug 2025). https://nunchuk.io/blog/miniscript-programmable-bitcoin
  - Wallet Rollover with randomised broadcasts. https://nobsbitcoin.com/nunchuk-introduces-automated-wallet-rollover-with-advanced-coin-control
- **Weaknesses.** Proprietary sync backend; advanced features behind a subscription. https://reclaimthenet.org/recommended-tool/nunchuk
- **For BHODL.**
  - Nunchuk's coin "collections with auto-rules" are the wallet-side twin of BHODL's tracker rules engine. **Unify them**: one rule system that tags UTXOs *and* categorises transactions.

### 3.4 Liana (v15, 2026)
- **Platform and target user.** Desktop (Rust/iced). Inheritance and treasury.
- **Standout patterns.**
  - Miniscript with a primary key plus timelocked recovery path(s); timelocks on by default. https://lianawallet.com/
  - **Refresh coins** shortcut before the relative timelock expires. https://blog.bitbox.swiss/en/exploring-bitcoin-miniscript-with-liana-and-the-bitbox02/
  - v14 reworked home, sidebar and payment history.
  - v15 adds **label-first receive**.
  - Encrypted descriptor backup (v13).
  - Sources: https://github.com/wizardsardine/liana/releases
- **Weaknesses.** Users must understand timelock upkeep; the Safety Net service is not available in the EU. https://lianawallet.com/
- **For BHODL.**
  - Label-first receive and encrypted descriptor backup are direct borrows.
  - A "coin age / expiry" column pattern is useful if BHODL ever adds inheritance.

### 3.5 Wasabi Wallet (2.8.3, Sept 2026)
- **Platform and target user.** Desktop privacy wallet.
- **Coordinator change.** After zkSNACKs shut down its coordinator (June 2024), users had to choose a coordinator themselves (2.0.8), with fee caps added in 2.0.8.1.
  - https://www.nobsbitcoin.com/wasabi-wallet-v2-0-8/
  - https://www.nobsbitcoin.com/wasabi-wallet-v2-0-8-1/
- **Releases since.**
  - 2.7.0: no longer bundles bitcoind; full RBF by default.
  - 2.8.0: **P2P compact-filter sync with no backend**; batching.
  - 2.8.1: sync-progress screen; Taproot receive by default.
  - 2.8.3: PayJoin to Taproot over Tor; an always-visible coinjoin "music box" status widget.
  - Source: https://github.com/WalletWasabi/WalletWasabi/releases
- **Weaknesses.**
  - Churn: anonymity score added then reverted; features removed.
  - Restore and sync confusion. https://stacker.news/items/470744
- **For BHODL.**
  - Borrow the persistent background-process widget (sync, Tor bootstrap, PayJoin session) and the explicit sync-progress screen.
  - Avoid single-coordinator dependencies.

### 3.6 Blockstream App (formerly Green; Desktop 3.5.0, Aug 2026)
- **Platform and target user.** Mobile and desktop. Mainstream to intermediate users; Bitcoin plus Liquid with Jade integration. https://www.businesswire.com/news/home/20250528480512/en
- **Desktop 3.0.0.**
  - Home dashboard with BTC and Liquid side by side, price chart with **1D/1W/1M/1Y/5Y chips**.
  - Dedicated **Security tab**.
  - Buy flow.
  - Source: https://blog.blockstream.com/blockstream-desktop-app-3-0-0-less-friction-more-clarity/
- **Desktop 3.5.0:** air-gapped Jade QR signing for descriptor watch-only wallets. https://blog.blockstream.com/blockstream-app-desktop-3-5-0/
- **Weaknesses.**
  - Lightning backend churn: Greenlight → Liquid/Boltz swaps → Greenlight beta.
  - Stuck LN balances due to inbound liquidity. https://stacker.news/items/1332089
- **For BHODL.**
  - Borrow timeframe chips and a Security tab.
  - **Hide L2 plumbing behind stable surfaces** so backend changes don't reshape the UI.

### 3.7 Phoenix (ACINQ)
- **Platform and target user.** Mobile Lightning; one splicing channel per user. Users who want Lightning that "just works".
- **Standout patterns.**
  - **Pending swap-in that waits until the fee rate is ≤ a user-set max fee**, with a warning. https://www.nobsbitcoin.com/phoenix-wallet-v2-2-0/
  - Multi-wallet with name, photo and per-wallet PIN (2.7.0).
  - "Pay with full balance" (2.8.0).
  - Sources: https://github.com/ACINQ/phoenix/releases
- **Weaknesses.** Expensive first deposit (channel open), e.g. 2,065 sats on a small payment; liquidity fees are opaque to novices. https://stacker.news/items/232900
- **For BHODL.**
  - The "deferred until fee ≤ X" state applies to on-chain consolidation and L2→cold sweeps.
  - Per-wallet identity (name, avatar, colour) helps multi-wallet navigation.

### 3.8 Bull Bitcoin Mobile (6.13, Aug 2026)
- **Platform and target user.** iOS/Android; exchange-linked.
- **Two wallets from one seed.** "Instant Payments" (Liquid, with Lightning via Boltz) and "Secure Bitcoin" (on-chain).
  - **Auto-swap** moves funds to on-chain at a threshold (default 0.01 BTC).
  - https://stacker.news/items/1258276
  - https://blog.liquid.net/bull-wallet-by-bull-bitcoin-now-available-worldwide-on-ios/
- **PayJoin.**
  - BIP77 async PayJoin since v0.4.0: https://nobsbitcoin.com/bull-bitcoin-wallet-v0-4-0
  - 6.13 adds a minimum amount and session lifetime. https://github.com/SatoshiPortal/bullbitcoin-mobile/releases
  - *Whether PayJoin is on by default is [unverified]; 6.13 describes it as something the user enables.*
- **Other 2026 releases.**
  - Coins view with sort, filter and persistent freeze.
  - Deterministic swap secrets (recoverable).
  - A consolidation warning near Liquid's input limit.
  - RecoverBull encrypted vault backup.
- **Weaknesses.**
  - Boltz dependency (a stand-in provider was used during a Boltz outage).
  - "Do not install unless instructed" rescue builds.
  - A PayJoin UTXO-probing leak. https://uncensoredtech.substack.com/p/utxo-probing-attack-using-payjoin
- **For BHODL.**
  - Borrow the **L2 → cold auto-sweep threshold**, which matches BHODL's multi-L2 plan.
  - Borrow PayJoin with a minimum amount and expiry.
  - Avoid single swap-provider lock-in.

### 3.9 BlueWallet (8.0.2, Oct 2026)
- **Platform and target user.** iOS/Android; mainstream to intermediate.
- **Releases.**
  - **Ark-powered Lightning wallet** and BIP86 (7.2.2, *year inferred* 2025).
  - Shows the maximum sendable amount; highlighted address segments (8.0.0).
  - iOS 26 Liquid Glass; RBF for watch-only (8.0.1).
  - **BIP329 import/export**, receive labels, editable watch-only fingerprint/derivation, raw PSBT view (8.0.2).
  - Source: https://github.com/BlueWallet/BlueWallet/releases
- **Per-wallet hide.** https://bluewallet.io/docs/manage-wallets/
- **Weaknesses.** Basic coin control; no PayJoin or silent payments; the custodial LNDHub was sunset in 2023. https://bitcoinmagazine.com/business/bluewallet-to-sunset-custodial-lightning-wallet-service
- **For BHODL.**
  - BlueWallet is the first mainstream Ark Lightning implementation, so it is the reference for BHODL's Ark roadmap.
  - It is also a watch-only UX reference: editable fingerprint and derivation, raw PSBT view.

### 3.10 Bitkey (Block)
- **Platform and target user.** Mobile app plus hardware plus server key (2-of-3). Mainstream users.
- **Release timeline.**
  - Hide balance (2024.61.1); CSV and descriptor export (Oct 2024).
  - Inheritance (Feb 2025) with a **6-month claim window**; the beneficiary can't see the balance. https://www.businesswire.com/news/home/20241118370113/en
  - **Security Hub** (2025.10.0) and a performance graph (2025.8.0).
  - Wallet privacy hides balance *and* history (2025.21.0).
  - Configurable security waiting period (Oct 2026).
  - Source: https://bitkey.world/releases
- **Weakness.** Four recovery protocols are complex. Nunchuk makes this critique, but Nunchuk is a competitor. https://nunchuk.io/blog/bitkey
- **For BHODL.** Borrow a Security Hub with a readiness score, and privacy that covers history, not just the balance.

### 3.11 Others in brief
- **ZEUS v13.**
  - Embedded LDK Node or LND, BOLT12, Cashu, NWC with budgets, a circular rebalance tool.
  - Its iOS multi-device keychain sync can lose wallet configs.
  - Sources: https://github.com/ZeusLN/zeus/releases, https://newreleases.io/project/github/ZeusLN/zeus/release/v0.12.0
  - Lesson: attach Cashu and NWC *to* a primary wallet; don't cloud-sync configuration.
- **AQUA 0.4.0 (Feb 2026).**
  - Redesign with a home price chart, multi-wallet, and a BTC/Bits/Sats unit picker.
  - USDt focus, which runs against BHODL's ethos.
  - Source: https://jan3.com/blog/aqua-wallet-redesigned
- **Specter Desktop.**
  - v2.1.11 is stable, with slow maintenance; it runs as a Flask web app.
  - A cautionary example of a web-wrapped Core RPC UI being overtaken by Sparrow.
  - Source: https://github.com/cryptoadvance/specter-desktop/releases
- **Casa and Unchained.**
  - Collaborative-custody health and alert dashboards; Unchained has vault and loan health alerts and account lockdown.
  - https://www.unchained.com/blog/introducing-the-unchained-ios-app
  - https://bitcoinmagazine.com/?p=18969
- **Muun.** In maintenance mode (2.11.0, Oct 2025). The Emergency Kit PDF is still worth copying. https://apps.apple.com/us/app/muun-wallet/id1482037683

---

## 4. Competitor teardown: net-worth and finance trackers

### 4.1 Kubera — the main IA reference for tables
- **Platform and price.** Web, iOS and Android; paid only (~$250/yr). https://kubera.com/why-kubera
- **IA.**
  - "Everything you own and owe on one page": a spreadsheet-like **Assets / Debts** balance sheet, organised into user-defined sheets and sections.
  - Ownership tags, Nested Portfolios, Club Benchmarks.
  - Source: https://kubera.com/how-kubera-works
- **Net-worth history.**
  - A daily snapshot from your start date; every value change is kept, so you can "go back in time".
  - Recap gives daily/monthly/quarterly statements *(third-party)*.
  - https://kubera.com/how-kubera-works
  - https://alearesearch.substack.com/p/kubera-crypto-portfolio-tracking
- **BTC as the reporting unit — strong validation for BHODL.**
  - Kubera markets "change the unit to gold, bitcoin or oil". https://www.kubera.com/blog/179-is-usd-the-best-way-to-track-your-net-worth
  - Their worked example is the same portfolio over 4 years: USD $770K → $6.23M (8.1×, 75.77% CAGR), but **₿80 → ₿125 (1.6×, 12.79% CAGR)**. (same URL)
  - No sats unit found.
- **Investments.**
  - Holdings are entered as ticker plus quantity ("BTC 1"). https://help.kubera.com/article/48-kubera-supports-all-major-currency-stocks-around-the-world
  - Returns are computed as **cash-flow IRR**, not lots. https://help.kubera.com/article/79-irr-of-my-investment-in-kubera
- **Planning and estate.**
  - **Fast Forward** scenario planning (a default 7% rate is applied).
  - Dead Man's Switch / beneficiary.
  - Proof of Wealth.
  - Source: https://kubera.com/how-kubera-works
- **Weaknesses.**
  - No free tier. https://thecollegeinvestor.com/36895/kubera-review/
  - No budgeting. https://toolradar.com/tools/kubera
  - No documented hide-balances mode; no lots/FIFO.
- **For BHODL.**
  - Borrow: the sheet → section → row tables with inline-editable values, the "time machine" history, and the unit-of-account switcher (marketed exactly the way BHODL thinks).
  - Add what Kubera lacks: sats, lots/cost basis, budgets.
  - The dead-man's-switch idea maps onto bitcoin inheritance (Bitkey's claim window).

### 4.2 Monarch Money
- **Dashboard.**
  - Customisable cards (budget, net worth, recurring, spending, transactions, investments…) that can be reordered or hidden.
  - Web and mobile orders are set independently.
  - Source: https://help.monarch.com/hc/en-us/articles/360058127551-Customize-Your-Dashboard
- **Budgets.**
  - **Flex** (the default) uses three groups: Fixed / Flexible (one "Flex number") / Non-monthly (annual costs spread monthly with rollovers). https://www.monarch.com/blog/flex-budgeting-simplify-your-spending-with-just-one-number
  - Classic per-category budgets are available as an alternative. https://www.monarch.com/blog/flex-vs-category-budgeting-how-to-choose-whats-right-for-you
- **Rules.**
  - "If transaction matches…" on merchant (exact/contains, original statement), amount and account.
  - "Then…" actions: rename merchant, set category, add tags, hide, smart split.
  - A **Preview changes** tab.
  - A rule widget **pops up automatically when you edit a transaction**.
  - Rules apply to new, non-pending transactions only.
  - Source: https://help.monarch.com/hc/en-us/articles/360048393372
- **Recurring.** Auto-detected, with calendar and list views. https://www.monarch.com/features/tracking
- **Reports.**
  - **Sankey** (income → expenses) under Reports → Cash Flow, plus bar charts.
  - Break down by category, group or merchant; monthly, quarterly or yearly.
  - Source: https://help.monarch.com/hc/en-us/articles/20504904768020-Cash-Flow
  - Hover details, click-through to transactions, saved reports, image download. https://help.monarch.com/hc/en-us/articles/21846787088916-Using-Reports
  - **Sharing can hide amounts.** https://www.monarch.com/blog/visualize-your-cash-flow-like-never-before
- **Goals 3.0.**
  - Save-up vs pay-down goals.
  - Target, date and monthly contribution drive **on track / ahead / at risk**.
  - Source: https://help.monarch.com/hc/en-us/articles/44373110771860-Introducing-Goals-3-0
- **Investments.**
  - Holdings, performance vs benchmark, TWR. https://help.monarch.com/hc/en-us/articles/41855507661076-Investments-in-Monarch
  - **Gains & Losses** tab with per-lot date, quantity, price, holding period (short/long term) and per-lot gain; no method selector. https://help.monarch.com/hc/en-us/articles/45946555058964-Adding-Gains-Losses-to-Investments
- **Privacy.**
  - Demo Mode (mobile only). https://help.monarch.com/hc/en-us/articles/46153716425876-Demo-Mode
  - Per-account "exclude from net worth / cash flow". https://help.monarch.com/hc/en-us/articles/4407859794580
- **Weaknesses.**
  - Bank-sync fragility; price ($99.99, Plus $199). https://envelopebudgeting.com/articles/monarch-money-review
  - Android lags iOS. https://unstar.app/it/blog/ynab-vs-rocket-money-vs-monarch-budgeting-apps-ranked-2026
  - Weak support for the EU. https://freenance.io/products/monarch-money-review-2026-mint-replacement-budget-app/

### 4.3 Copilot Money
- **Platform and price.** Apple platforms plus web; $95/yr; no ads. https://copilot.money/
- **Home.** A "follow the line" daily-spend chart, upcoming bills and pending refunds. (same URL)
- **Categorisation.**
  - "Copilot Intelligence" ML learns from your corrections.
  - Income and internal transfers can't be categorised.
  - Source: https://help.copilot.money/en/articles/10761907-transactions-faq
- **Recurrings.**
  - Created from an existing transaction.
  - Filters with an **amount range plus a date window** (e.g. "$9–$10 around the 23rd–24th").
  - Weekly, bi-weekly, monthly or non-monthly.
  - Sources: https://help.copilot.money/en/articles/10244751-recurrings-faq, https://help.copilot.money/en/articles/9778259-recurrings-tab-overview
- **Investments.**
  - A Returns vs Balances toggle.
  - A **dashed "Live Balance Estimate" line**.
  - **Benchmark vs S&P or Bitcoin**.
  - Source: https://help.copilot.money/en/articles/5377645-investments-tab-overview
- **Accounts.** Per-account % change follows the time range selected on the net-worth graph. https://help.copilot.money/en/articles/6213732-accounts-tab-overview
- **Weaknesses.**
  - No Android; no zero-based budgeting. https://envelopebudgeting.com/articles/copilot-money-review
  - The web app lags the apps. https://www.thepennyhoarder.com/budgeting/budgeting-copilot-money-review/
  - US banks only.

### 4.4 YNAB
- **Method.** Zero-based / envelope: "Give every dollar a job" (Ready to Assign), "true expenses", "age your money". https://www.thepennyhoarder.com/budgeting/ynab-review/
- **Targets.**
  - Weekly, monthly, by date, or savings balance.
  - Underfunded targets show yellow; **Snooze** a target for one month.
  - Sources: https://support.ynab.com/en_us/snooze-a-target-HyS4E5rZT.md, https://www.ynab.com/blog/budget-smarter-with-smarter-goals
- **Reflect tab.** Spending Breakdown, Trends, **Net Worth** (monthly asset/debt bars), and Income v Expense. https://support.ynab.com/en_us/reflect-in-ynab-B1GJsrWkj
- **Weaknesses.**
  - Learning curve; no investment performance. https://thecfoclub.com/tools/ynab-review/
  - No EU bank sync. https://freenance.io/products/ynab-review-2026-budgeting-app-worth-it-european-investors/

### 4.5 Lunch Money
- **Budgets and organisation.**
  - Custom budget periods.
  - Tags alongside categories.
  - **160+ currencies with a home currency.**
  - A calendar view.
  - Source: https://lunchmoney.app/blog/11-lunch-money-features-you-should-be-using-and-why
- **Rules.**
  - If/then rules with priority levels; some are auto-created. https://lunchmoney.app/features/rules
  - The priority semantics confuse users (20 runs first, 1 wins). https://feedback.lunchmoney.app/rules/p/clarify-priority-level-tooltip-for-rules
- **Recurring items.** "Convert to recurring item" from a transaction row. https://support.lunchmoney.app/finances/recurring-items/creating-recurring-items
- **Net worth.** A month-end snapshot of each balance, and the user can edit it. https://support.lunchmoney.app/home/net-worth
- **Crypto.**
  - Users request "crypto as a currency": https://feedback.lunchmoney.app/transactions/p/crypto-wallet-transactions
  - A bug showed crypto history in USD whatever the home currency: https://feedback.lunchmoney.app/bugs/p/historical-balances-of-synced-crypto-accounts-incorrectly-display-in-usd
  - Together these show unmet demand for a BTC denomination.
- **Sankey.** Requested by users but missing. https://feedback.lunchmoney.app/net-worth-stats-trends/p/sankey-diagram-for-spending

### 4.6 Empower Personal Dashboard (formerly Personal Capital)
- **Features.**
  - Free; Yodlee aggregation.
  - Net worth, cash flow, Investment Checkup, Retirement Planner, Fee Analyzer.
  - Source: https://freenance.io/products/empower-personal-capital-review-2026-net-worth-tracker-investing-fees-analyzer/
- **Charts.** A 90-day net-worth trend and a 30-day cash-flow chart on the dashboard. https://www.mezzi.com/blog/empower-free-dashboard-vs-advisor-pitch-reading-real-product
- **Weakness.** Funnels users into advisory upsell (0.49–0.89% fees). https://thecollegeinvestor.com/4757/empower-review/

### 4.7 Actual Budget — the best open-source reference for rules and reports
- **Local-first.**
  - A full copy on each device.
  - Optional per-file E2E encryption with a second password, which can't be turned off once enabled.
  - Source: https://actualbudget.org/docs/getting-started/sync
- **Rules.**
  - **Stages: pre / default / post**, auto-ranked from least to most specific.
  - Operators: is, contains, matches (regex), one-of.
  - **Renaming a payee auto-creates a rule; categorising "learns" a rule** (can be disabled).
  - The rule editor lists matching transactions and has an "Apply actions" button, so it doubles as a batch editor.
  - Source: https://actualbudget.org/docs/budgeting/rules/
- **Goal templates.**
  - A text DSL in category notes: `#template 10000 by 2025-12`, `repeat every week`, `10% of all income`, `schedule Internet`, `average 3 months`, `remainder`.
  - Source: https://actualbudget.org/docs/experimental/goal-templates
  - Schedules accept approximate ("~") amounts. https://actualbudget.org/docs/schedules
- **Reports dashboard.**
  - Widgets: Net Worth (trend/stacked), Cash Flow, Custom, Spending, **Sankey** (experimental), Summary, Calendar, Text.
  - **Crossover Point**: the point where passive income ≥ expenses, i.e. a FIRE card.
  - Source: https://actualbudget.org/docs/reports/

### 4.8 Firefly III
- **Rules.**
  - Rule **groups** with an order; strict (AND) or non-strict (OR).
  - Inverted triggers.
  - "Stop processing" flags.
  - Run a rule against history.
  - Can't mix AND and OR in one rule; failed actions only appear in the logs (a usability weakness).
  - Sources: https://docs.firefly-iii.org/how-to/firefly-iii/features/rules/, https://docs.firefly-iii.org/references/firefly-iii/rule-actions/
- **Other concepts.** Piggy banks (goals), bills with min/max amounts, and budgets with "available budget". https://docs.elfhosted.com/app/fireflyiii/

### 4.9 Wealthfolio
- **Platform.** Open-source desktop (Tauri) plus mobile; local SQLite; **no account needed**. https://wealthfolio.app/, https://www.findmymoat.com/tools/wealthfolio
- **Features.**
  - Dashboards: Investments / Net Worth / Spending / Planning.
  - Goals & Save-Up Planner.
  - TWR and MWR returns.
  - Add-ons; custom price feeds.
- **For BHODL.** The closest *architectural* sibling: local-first desktop, a privacy pitch, and an investments focus.

### 4.10 Ghostfolio
- **Features.**
  - Open-source, self-hosted PWA (Angular/NestJS).
  - ROAI over Today / WTD / MTD / YTD / 1Y / 5Y / Max.
  - Static risk analysis ("X-ray").
  - **Zen Mode** (hides amounts and focuses on performance).
  - Source: https://github.com/ghostfolio/ghostfolio
- **Unconfirmed.** A FIRE calculator and user-chosen base currency are reported only by third parties. https://blog.elest.io/ghostfolio-free-open-source-privacy-first-portfolio-wealth-tracker/

### 4.11 Finary
- **Product.**
  - EU net worth across 20k+ institutions; Plus at $149.99/yr. https://apps.apple.com/app/id1569413444
  - It has drifted into brokerage: MiCA crypto, life insurance.
  - 2026 adds Goals and **allocation targets** ("A target we calculate. An allocation you control."). https://finary.com/en/product-updates
- **Weakness.** Review sentiment is reportedly negative, but the source is weak. https://justuseapp.com/en/app/1569413444/finary-patrimoine-budget/reviews
- **Lesson.** Aggregator-turned-broker is a trust conflict BHODL should avoid.

### 4.12 ProjectionLab (forecast / FIRE)
- **Features.**
  - Milestones (FI, house, retirement).
  - **Chance of Success** via Monte Carlo; historical backtests.
  - Source: https://robberger.com/projectionlab-review
- **Sankey.** A **year-by-year cash-flow Sankey** (income → taxes / savings / spending). https://cdn.projectionlab.com/cash-flow

### 4.13 Bitcoin-native and crypto tracking
- **Clams.**
  - Local-first, single binary (CLI + REST).
  - Node, xpub and descriptor ingestion; integrates with Sparrow, Coldcard, Nunchuk, BTCPay, CLN and LND.
  - Double-entry; **BTC-denominated FIFO** capital-gains reports; HIFO/Spec-ID not shown.
  - An accounting engine, not a dashboard.
  - Source: https://clams.tech/
- **Koinly.**
  - FIFO (default), LIFO, HIFO, ACB, Shared Pool; can be overridden per tax year. https://support.koinly.io/en/articles/9489992-settings-cost-basis-method
  - No true Specific-ID. https://discuss.koinly.io/t/specific-id-cost-basis-possible/5006
- **CoinTracker.**
  - HIFO and LIFO; no unit-level Specific-ID. https://support.cointracker.io/hc/en-us/articles/4413071356177
  - Users complain that they **can't see which lots matched a sale**. https://feedback.cointracker.io/product-feedback/p/support-for-specific-identification-cost-basis-method-for-lots
  - US 1099-DA rules require matching the method the exchange uses. https://sanity.cointracker.io/blog/1099-da-cost-basis-rules
- **River.**
  - Performance page: total returns, total cost, and a **Bitcoin lots table** (acquired date, short/long-term label, value, cost basis including fees).
  - External deposits are excluded until the user enters a cost basis.
  - Source: https://support.river.com/hc/en-us/articles/50079822040083-What-is-the-performance-page-at-River
- **Bag (Android, F-Droid, v1.2.8 Apr 2026).** BTC-only net worth with a **Sats/BTC toggle**, up to 3 fiat currencies, a DCA tracker with P&L. https://f-droid.org/en/packages/app.bitbag/
- **Compass: Bitcoin Finance App (iOS, July 2026).**
  - Net worth in USD and BTC; spending converted to sats; watch-only xpub.
  - Spending tags: **Necessary / Discretionary / Foolish**.
  - **The closest direct conceptual competitor to BHODL.** https://mwm.ai/apps/compass-bitcoin-finance-app/6776933441
- **BTCPay Server.** CSV reporting (on-chain wallets, payments) but no fiat-valuation accounting UI. https://docs.btcpayserver.org/Reporting
- **Not documented.** I found no primary documentation of a sats-denominated net-worth or lots dashboard at Swan, Strike or Bitcoin Well.

---

## 5. Cross-cutting patterns matrix

Legend: ● = has it, best in class; ✓ = has it; ◐ = partial or limited; — = absent or not found; ? = unverified. "BHODL (plan)" is what BHODL's roadmap already includes.

| # | Pattern | Sparrow | Electrum | Nunchuk | Liana | Wasabi | Blockstream | Phoenix | Bull | BlueWallet | Bitkey | Kubera | Monarch | Copilot | YNAB | Actual | River | **BHODL (plan)** |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Per-wallet tabs / multi-wallet switcher | ● | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (2.7) | ◐ (2 fixed) | ✓ | — | n/a | n/a | n/a | n/a | n/a | n/a | ✓ |
| 2 | Fee chart / mempool / recent blocks | ● | ◐ | ? | ? | ◐ | ◐ | ◐ max-fee | ? | ◐ | — | | | | | | | to do |
| 3 | Urgency presets + custom sat/vB | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ◐ | ✓ | ✓ | ✓ | | | | | | | ✓ |
| 4 | High-fee guard | ✓ (2.4) | ? | ? | ? | ✓ caps | ? | ✓ | ? | ? | ? | | | | | | | to do |
| 5 | Manual coin control + freeze | ● | ✓ | ● | ✓ | ✓ | ◐ | — | ✓ | ◐ | — | | | | | | | ✓ |
| 6 | Coin tags / collections / auto-rules | ◐ labels | ◐ | ● | ◐ | ◐ | — | — | ◐ | ◐ | — | | | | | | | via rules engine |
| 7 | Privacy-optimised selection + explanation | ● | — | — | — | ● coinjoin | — | — | — | — | — | | | | | | | to do |
| 8 | BIP329 import/export | ✓ | ? | ● superset | ? | ? | ? | — | ? | ✓ (8.0.2) | — | | | | | | | ✓ |
| 9 | Label-first receive | ◐ | ✓ | ✓ | ● (v15) | ✓ | ◐ | — | ◐ | ✓ | — | | | | | | | to do |
| 10 | Chunked / highlighted addresses | ● (2.4) | — | ? | ? | ? | ? | — | ? | ✓ | — | | | | | | | to do |
| 11 | PayJoin | ✓ | ◐ | — | — | ✓ (2.8.3) | — | — | ● BIP77 | — | — | | | | | | | ✓ BIP78 |
| 12 | RBF / CPFP bump UI | ● (2.5.5) | ✓ | ✓ | ✓ | ✓ | ✓ | ◐ | ✓ | ✓ (also watch-only) | ? | | | | | | | ✓ |
| 13 | Batch into unconfirmed tx | ◐ | ● | — | — | ✓ (2.8) | — | — | — | — | — | | | | | | | — |
| 14 | Server/node type indicator, own node, Tor | ● | ✓ | ◐ | ✓ | ● | ◐ | — | — | ◐ | — | | | | | | | ✓ Tor |
| 15 | Hide amounts / privacy mode | ● (incl. tooltips) | ? | ? | ? | ? | ? | ? | ? | ✓ per-wallet | ● incl. history | — | ◐ mobile demo | ? | ? | ? | ? | to do |
| 16 | Security hub / checklist | — | — | ◐ key health | — | — | ✓ | — | — | — | ● | ◐ | — | — | — | — | — | to do |
| 17 | Encrypted backup file / descriptor PDF | ◐ | ◐ | ✓ BSMS | ● | ◐ | — | ◐ | ✓ RecoverBull | ? | ✓ | | | | | | | ✓ |
| 18 | Unit choice (sats/BTC/auto/₿) | ● auto | ✓ | ✓ | ? | ? | ✓ | ✓ | ? | ✓ | ? | ◐ BTC only | — | — | — | — | — | ✓ |
| 19 | Net worth in BTC as the base unit | — | — | — | — | — | — | — | — | — | — | ● | — | ◐ benchmark | — | — | — | ● core |
| 20 | Net-worth history with timeframe chips | ◐ balance chart | — | — | — | — | ✓ 1D–5Y | — | — | — | ✓ perf graph | ● time machine | ✓ | ✓ | ◐ monthly | ✓ | ✓ | ✓ |
| 21 | Rules with preview / learn-from-edit | — | — | ◐ coin rules | — | — | — | — | — | — | — | — | ● | ✓ ML | — | ● stages + learn | — | ✓ |
| 22 | Recurring detection → bill | — | — | — | — | — | — | — | — | — | — | — | ✓ | ● ranges | ◐ | ✓ schedules | — | ✓ |
| 23 | Flexible vs envelope budgets | — | — | — | — | — | — | — | — | — | — | — | ● Flex | ✓ | ● zero-based | ✓ | — | ✓ |
| 24 | Goals with on-track status / sinking funds | — | — | — | — | — | — | — | — | — | — | — | ● 3.0 | ◐ | ● targets | ✓ templates | — | ✓ |
| 25 | Lots table with method (FIFO/HIFO/Spec-ID) | — | — | — | — | — | — | — | — | — | — | — IRR | ◐ lots, no method | — | — | — | ● lots (FIFO?) | ● (gap in market) |
| 26 | Sankey cash flow | — | — | — | — | — | — | — | — | — | — | — | ● | ◐ | — | ◐ exp. | — | ✓ |
| 27 | FIRE / runway / crossover KPI | — | — | — | — | — | — | — | — | — | — | ◐ Fast Forward | — | — | — | ● crossover | — | ✓ |
| 28 | Local-first / no account | ● | ● | ◐ | ● | ● | ◐ | ◐ | ◐ | ● | — | — | — | — | — | ● | — | ● |

**Best-in-class examples per pattern**
- Fee UX: Sparrow.
- Coin organisation: Nunchuk.
- Label-first receive: Liana.
- PayJoin receive: Bull.
- Hide amounts: Sparrow (desktop) and Bitkey (history too).
- Security overview: Bitkey.
- BTC-as-unit net worth: Kubera.
- Rules: Monarch for UX, Actual for model.
- Recurring: Copilot.
- Budgets: Monarch Flex and YNAB.
- Goals: Monarch 3.0 and YNAB targets.
- Lots: River for the display; nobody yet offers a lot-matching view for HIFO/Spec-ID.
- Sankey: Monarch, and ProjectionLab for a yearly forecast Sankey.
- FIRE: Actual Crossover and ProjectionLab Monte Carlo.

Sources for each cell are in sections 3–4.

---

## 6. Implications for BHODL: top 20 prioritised recommendations

Each item lists: **Evidence** → **Fit with BHODL's direction** (✔ supports / ⚠ challenges).

### P0 — foundational: do before more features

1. **Unit typography system.**
   - Amounts in a proportional font with `tnum`.
   - **A fixed number of decimals per column**: integer sats, or 8-decimal BTC, never mixed.
   - The unit goes in the column header, not repeated in every cell.
   - Monospace with chunking only for addresses, txids and xpubs.
   - Truncate (floor) spendable balances.
   - **Evidence:** BDG units page; GOV.UK right-align; MDN `tnum`/`zero`; Sparrow chunking; BDG activity page ("symbol per amount is distracting").
   - **Fit:** ✔ core to data density and BTC-as-UoA.
2. **Decide the ₿ / sats display policy explicitly, and ship a toggle.**
   - Recommended default: sats, with "₿-only (BIP177)" as an option.
   - Offer an Auto mode à la Sparrow 2.5.5 (sats below 1 BTC, BTC above it, per wallet), with dual display (sats plus BTC on hover) during onboarding.
   - **Evidence:** BIP177 is still Draft but adoption is spreading (Cash App, Square POS, Bitkit, BDG Jan 2026). Critics cite confusion over "BTC vs bitcoin". BIP177 itself recommends a toggle and dual display.
   - **Fit:** ✔ opinionated BTC-standard. ⚠ Committing hard to ₿-only now risks confusing users who learned "sats", so keep it a setting.
3. **Gain/loss colour tokens that are colour-blind safe and dark-safe, plus redundant encoding.**
   - Okabe-Ito-derived bluish-green vs vermillion, each verified at ≥4.5:1 on every surface tone.
   - Always a ±, ▲/▼ or parentheses.
   - An "invert colours (East Asian convention)" setting.
   - In the wallet activity list, received = "+"/green but **sent is neutral, not red**; red is reserved for errors.
   - **Evidence:** jfly (dark red on dark backgrounds fails protanopes); WCAG 1.4.1; BDG activity page; moomoo swap setting.
   - **Fit:** ⚠ challenges the "neon" theme. Neon greens and reds on black often fail 1.4.11 / 1.4.3 or collapse for CVD users, so audit the neon palette.
4. **WCAG 2.2 AA pass for dense tables.**
   - A focused-row state distinct from selected.
   - Focus is never hidden under sticky headers (2.4.11).
   - Row icon hit areas ≥24px (2.5.8).
   - Muted text ≥4.5:1.
   - Bundle `jdk.accessibility` and test with NVDA.
   - **Evidence:** W3C Understanding docs; Compose desktop a11y docs; AppleVis survey (wallets broadly fail).
   - **Fit:** ⚠ Density and a 24px xs row conflict with 2.5.8. Default to 32px Compact and offer 24px as opt-in "Ultra-dense". Accessibility can be a differentiator against Sparrow and the rest of the field.
5. **Privacy mode that is truly global.**
   - One shortcut (e.g. Ctrl+Shift+H) plus a status-bar eye icon.
   - Masks *every* amount: wallet, tracker, chart axes, tooltips, context menus and exports preview.
   - Optional "start hidden" and PIN-to-reveal.
   - **Evidence:** Sparrow (also strips tooltips and context menus); Bitkey (history too); BDG privacy page (long-press reveal, PIN-gated); Monarch's Demo Mode is mobile-only and masks charts — a gap on desktop.
   - **Fit:** ✔ dark, Bloomberg-on-a-shared-screen use; ✔ privacy ethos.

### P1 — wallet flows

6. **Send screen modelled on Sparrow, with BDG guardrails.**
   - Recipients table.
   - Fee panel with 3 urgency presets showing *total sats + ETA*, a custom sat/vB field, and a Recent Blocks / mempool strip.
   - Input/output diagram on review.
   - High-fee guard (≥X% of the amount, or above a sat/vB ceiling).
   - Sub-1 sat/vB allowed.
   - PIN/re-auth **last**.
   - **Evidence:** BDG send-fees and sending pages; Sparrow 2.2–2.4; Electrum 4.6.2, BlueWallet 7.2, Wasabi 2.8 (sub-1 sat/vB).
   - **Fit:** ✔ data-dense. ⚠ Novices need the presets to be the default; keep the chart collapsible (progressive disclosure, ≤2 levels per NN/g).
7. **Coin control unified with the tracker rules engine.**
   - UTXO table columns: label, tag(s), amount, age/confirmations, script type, frozen.
   - Tags and auto-rules ("new coins to address X → tag 'KYC exchange', freeze") reuse the same rule system as transaction categorisation.
   - In-flow search when selecting coins.
   - "Optimize: Cost / Privacy" with an *Analysis* explanation.
   - **Evidence:** Nunchuk collections and auto-rules; Sparrow Optimize and Analysis; BDG coin-selection (auto default, manual opt-in, dust surfaced).
   - **Fit:** ✔ a unique combination nobody ships. ⚠ Keep automatic selection as the default.
8. **Transaction state model that includes replaced, dropped and CPFP.**
   - States: Pending (n/a conf) → Confirming (1–5) → Confirmed (no badge).
   - **Replaced** collapses under its replacement with a "Fee bumped" chip.
   - **Dropped** gives an error with Rebroadcast/Bump call to action.
   - Inline "Speed up" (RBF, or CPFP when receiving) and "Cancel" (RBF to self), with exact cost preview.
   - **Evidence:** BDG transactions/activity pages (no badge on completed; 6 conf = final); Optech fee-bumping; Sparrow 2.5.5 descendant-fee handling; Trezor/Ledger wording.
   - **Fit:** ✔. This fills a gap the BDG leaves open.
9. **Receive: label-first, fresh address, unified QR, chunked monospace address.**
   - Ask for the label/category *before* generating the address.
   - Pre-select a tracker category so incoming funds are categorised on arrival.
   - Warn on reuse.
   - Hide the balance on the Receive screen.
   - When the unit is switched, clear the amount field.
   - **Evidence:** Liana v15; BDG requesting, privacy and payment-request-formats (BIP321) pages; Sparrow/BlueWallet chunking.
   - **Fit:** ✔. Label-first is the bridge between wallet and tracker.
10. **PayJoin as a quiet default for sends, explicit for receives.**
    - The sender path looks like a normal send, with a visible "negotiating" state.
    - Avoid round fee rates.
    - Graceful fallback on failure.
    - Plan for BIP77 (async) after BIP78.
    - Receiver settings: minimum amount and session expiry.
    - **Evidence:** BDG PayJoin case study; Bull 6.13; Sparrow 2.5.4 validation; UTXO-probing caveat.
    - **Fit:** ✔ privacy-first. ⚠ BIP78 needs an online receiver endpoint, which is awkward for a desktop app; BIP77 is the future-proof path.
11. **Status bar: node/server type, Tor state, sync height and background jobs.**
    - Sparrow-style colour by server type.
    - A Tor onion indicator.
    - Sync as a determinate progress bar with block height (NN/g >10 s rule).
    - A "music box" area for Esplora backfill, PayJoin sessions and price-feed status.
    - Click through to a details popover.
    - **Evidence:** Sparrow status bar; Wasabi 2.8.1 sync screen and music box; BDG privacy (Tor slows startup, say so); Bitcoin Core App block clock.
    - **Fit:** ✔ a Bloomberg-like status strip. It is also a trust signal.

### P1 — tracker

12. **Net-worth screen: Kubera table, BTC-first chart.**
    - Sheet → section → row balance sheet: wallets, manual assets, investments, liabilities.
    - Columns: value (₿/sats), 1D / 1M / YTD change, % of NW, last updated.
    - Chart with **1D / 1W / 1M / YTD / 1Y / 5Y / Max** chips.
    - Unit switch: sats ↔ BTC ↔ (fiat as a secondary "lens" overlay only).
    - Per-row change follows the selected chart range (Copilot).
    - **Evidence:** Kubera's balance sheet, daily snapshot and "change the unit to bitcoin" marketing (with ₿80→₿125 vs $770K→$6.23M example); Blockstream 3.0 chips; Copilot accounts-range coupling.
    - **Fit:** ✔ strong validation of BTC-as-UoA and of the "volatility is a feature" stance. Kubera uses exactly that contrast as its pitch.
13. **Rules UX: "create rule from edit" plus preview plus stages.**
    - When a user re-categorises a transaction, offer an inline "Always do this for <payee/address/label>?" prompt.
    - The rule editor shows the matching history and a **Preview changes** diff before "Apply to N existing".
    - Order rules explicitly with pre/default/post stages and **"last applied wins"** semantics.
    - Show conflicts.
    - Avoid Lunch Money's inverted-priority confusion and Firefly's logs-only failures.
    - **Evidence:** Monarch rules; Actual stages and auto-learn; Lunch Money feedback; Firefly docs.
    - **Fit:** ✔ power-user friendly. ⚠ Auto-learn should be visible and undoable.
14. **Recurring and bills.**
    - Detect recurring transactions by **amount range plus date window plus cadence** (weekly, bi-weekly, monthly, non-monthly).
    - "Accept as bill" from a transaction row.
    - Calendar plus list view.
    - Allow approximate amounts ("~").
    - **Evidence:** Copilot Recurrings; Lunch Money "convert to recurring"; Actual schedules "~"; Monarch calendar.
    - **Fit:** ✔. ⚠ Bills are inherently fiat-denominated (rent in EUR). Show them in fiat with a sats-equivalent at today's rate: the "operational surface" exception.
15. **Budgets: offer a Flex-style default plus an envelope mode.**
    - Default: three buckets (Fixed / Flexible / Non-monthly with sinking funds).
    - Optional zero-based "Ready to assign".
    - Use **bullet bars** rather than donuts or gauges.
    - Show underfunded as an amber chip with a text label.
    - "Snooze this month".
    - **Evidence:** Monarch Flex; YNAB targets and snooze; NN/g (bullet over gauges, avoid donuts).
    - **Fit:** ✔. ⚠ Budgeting in sats while expenses are priced in fiat makes the budget drift with price, which is BHODL's opinionated feature. Explain this up front with an inline "why your budget moved" note so novices don't read it as a bug.
16. **Goals and sinking funds with an on-track status.**
    - On track / Ahead / At risk chips, always with text and not colour alone.
    - A required-monthly-rate suggestion.
    - Separate save-up and pay-down goals.
    - **Evidence:** Monarch Goals 3.0; YNAB targets; Actual `#template … by` DSL (power-user shortcut idea).
    - **Fit:** ✔ matches the sinking-fund goals shipped in Phase 7.7.
17. **Lots and cost basis: make lot matching visible. This is the clearest gap in the market.**
    - Lots table: acquired date, sats, cost (fiat, operational), short/long-term, source (wallet/UTXO link).
    - On a disposal, show **which lots were consumed** under FIFO / HIFO / Spec-ID, side by side, with the P&L of each method before commit.
    - Link lots to UTXOs: a UTXO is a natural Spec-ID unit.
    - **Evidence:** CoinTracker users can't see the matched lots; Koinly has no true Spec-ID; Monarch shows lots but no method; River lots table; Clams FIFO only; 1099-DA method consistency.
    - **Fit:** ✔ differentiator, and on-chain UTXO ↔ lot linkage is something only a *wallet*-integrated tracker can do. ⚠ Cost basis is fiat by nature. Keep it on an explicitly "tax/operational" surface, consistent with the BTC-standard stance.

### P2 — reports, polish and readiness

18. **Reports: monthly Sankey plus a forecast Sankey, and FIRE/runway as a crossover chart.**
    - Monarch-style Sankey with hover, click-through to transactions and "share with amounts hidden".
    - The FIRE KPI as an Actual-style **Crossover Point** line chart (passive income vs expenses), with runway in months as a KPI tile.
    - Optional ProjectionLab-style Monte Carlo "chance of success" band later.
    - Keep the dashboard to about 5 KPIs.
    - **Evidence:** Monarch cash-flow and reports; Actual reports; ProjectionLab; NN/g dashboards; KPI practitioner sources.
    - **Fit:** ✔ data-dense. ⚠ Sankeys are hard to make accessible. Provide a table view toggle for every chart, which also suits keyboard and screen-reader users.
19. **Security Hub plus re-auth policy plus accessible PIN.**
    - One screen showing: backup status (phrase verified? encrypted file age?), PIN set, auto-lock timeout, Tor on/off, watch-only vs signing wallets, last BIP329 export.
    - Show a readiness score with dismissible, *spaced* reminders, and never interrupt a send.
    - Re-auth for viewing the seed, exporting xpubs or labels, changing the lock policy, and large sends.
    - Allow paste or a password manager in the PIN field; offer OS auth (Windows Hello / Touch ID) where possible.
    - Escalating lockouts with clear "try again in mm:ss" copy and a warning about data-wipe consequences.
    - **Evidence:** Bitkey Security Hub; Blockstream Security tab; BDG security page (attempt limits, blocked trivial PINs, reminders not interrupting); WCAG 3.3.8.
    - **Fit:** ✔. Adds a novice-friendly layer without diluting density elsewhere.
20. **Onboarding, empty states and mobile readiness.**
    - **Onboarding:**
      - A two-path start (Create / Restore / Watch-only xpub).
      - A responsibility statement.
      - Phrase reveal → shuffled-word verification (skippable, with a reminder after the first deposit).
      - Printable descriptor PDF (Muun Emergency Kit / BDG template).
      - Restore with per-word autocomplete and validation plus a determinate scan progress bar.
    - **Tracker empty states:** Carbon-style, with a positive title, a one-line explanation and a primary CTA ("Add a manual asset", "Import BIP329 labels", "Create your first rule from a transaction"). Never show bare table headers.
    - **Mobile readiness:**
      - Map sidebar → rail → bottom bar with `NavigationSuiteScaffold`.
      - Map transaction list → detail with `ListDetailPaneScaffold`.
      - Map goals/insights panels to `SupportingPaneScaffold` (bottom sheet on compact screens).
      - Design each dense table with a **priority-column order** so it can drop to 2–3 columns on compact widths.
      - Enforce a desktop `minSize` (CMP 1.12 Window v2).
    - **Evidence:** BDG first-use, manual-backup and recovery pages; Carbon empty states; Android window size classes and canonical layouts; kotlinlang CMP 1.12; Wasabi restore confusion.
    - **Fit:** ⚠ This is where the research most clearly *challenges* the direction. A Bloomberg-dense, dark-only, desktop-only feel scares novices (the Sparrow critique), and dense multi-column tables do not survive on mobile without per-column priorities. Mitigations:
      - Ship two densities.
      - Make the light/System theme a first-class option (Sparrow moved to System by default in 2.5.5).
      - Put advanced panels behind ≤2 levels of disclosure.
      - Define column priorities now, before the mobile phase.

### Where the research supports vs challenges BHODL's direction (summary)
- **Supported:**
  - BTC as unit of account: Kubera markets it explicitly; Lunch Money users ask for crypto-as-currency; Bag and Compass show emerging demand.
  - Data-dense desktop for self-custody: Sparrow, Liana and Wasabi all live there, and Sparrow is the category leader.
  - A wallet-integrated tracker, which enables UTXO↔lot linkage, label-first categorisation and unified rules. No competitor combines these. Compass (iOS, 2026) is the nearest and is mobile-thin.
  - Local-first privacy (Actual, Wealthfolio, Clams, Sparrow).
- **Challenged:**
  - **Neon theme accessibility**: red/green on black, CVD users, non-text contrast.
  - **Dark-only defaults**: Sparrow now follows the OS.
  - **24px ultra-dense rows** vs WCAG 2.5.8.
  - **Novice approachability**: the BDG insists on progressive disclosure, auto coin selection by default, presets over custom fees, and a skippable but nudged backup.
  - **Mobile readiness** of Kubera-style multi-column tables.
  - **Fiat-priced realities** (bills, cost basis, taxes) must remain clearly operational surfaces, without breaking the BTC-first frame.
  - **₿-only (BIP177)** is still Draft and contested, so make it a toggle, not the default dogma.

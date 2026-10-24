# Design help wanted

BHODL's backend and desktop app work; what the project lacks most is design. This page
lists scoped design tasks a volunteer can pick up without building the code. Each task will
also be a GitHub issue with the `design` label. Comment there before you start.

**Before you start**

- Product: a watch-only Bitcoin wallet with a net-worth and spending tracker in one
  desktop app. It never holds private keys and cannot send bitcoin: wallets are added by
  xpub, descriptor or address list. Alpha, unaudited.
- Direction already decided (please work within it, or argue against it in the issue):
  dense and data-heavy (Sparrow / Bloomberg / Kubera), bitcoin as the unit of account, fiat
  only on operational surfaces (bills, tax cost basis), the system theme by default, sats as
  the default unit, 32 px table rows by default. Background:
  [`ux-review-2026-10.md`](ux-review-2026-10.md) (Italian),
  [`research-ux-competitors-2026-10.md`](research-ux-competitors-2026-10.md).
- Tokens, theme and contrast report: [`../design/`](../design/). Current screens:
  [`screenshots/`](screenshots/). Need another screen? Ask in the issue.
- Deliverables: Figma or Penpot links (view access), or exported frames. Annotated
  screenshots and written critiques count too.

---

## 1. Lightning and other layer-2 balances in the tracker (before we build them)

**Context.** Tracking Lightning, Liquid, Cashu / Fedimint and Ark balances is the next
big feature (see [`../PLAN.md`](../PLAN.md)). BHODL stays watch-only, so these are
read-only views of balances held elsewhere; nothing is built yet, which is the best
moment for design. Open questions: how a Lightning or Liquid balance sits next to
on-chain wallets in the tracker's wallet list and net worth; how to show that a balance
comes from a third party (custodial or federated) versus self-custody; how stale or
unavailable data looks.

**Deliverable.** Mid-fidelity screens for: the tracker wallet list with mixed layers, a
read-only layer-2 wallet workspace (balance, history), and the "add a layer-2 wallet"
step. Follow the existing Sparrow-style sub-tab layout.

## 2. Mobile layouts for the wallet and tracker

**Context.** The Android app builds and packages the Rust core, but shows a placeholder.
The desktop UI is dense tables behind a left navigation rail. The plan is Material 3
canonical layouts (`NavigationSuiteScaffold`, list-detail). Only the wallet transactions
table applies a column priority for narrow widths; the other tables declare one but do not
use it yet.

**Deliverable.** Phone layouts (360–412 dp wide) for: wallet transactions, receive,
tracker home and portfolio. For each table, which 2–3 columns survive and where the rest
goes (detail pane, expandable row). Navigation mapping from the desktop rail to a bottom bar.

## 3. Onboarding and first run

**Context.** First run is: Welcome (explains that BHODL is watch-only), create a device
PIN, add the first wallet by xpub, then the tracker with a short explanation of sats vs
BTC. Users upgrading from builds that could hold seeds also see a one-time screen that
deletes the old spending wallets after they confirm they have the recovery phrases. It
works but was designed screen by screen, and "xpub" and "script type" mean nothing to
many users.

**Deliverable.** A critique of the current flow plus a proposed flow (wireframes are
enough) that explains what watch-only means and where spending happens, helps the user
find and export the xpub from common signing wallets (Sparrow, hardware wallets, phone
wallets), and introduces the tracker without forcing it. Reference: Bitcoin Design Guide
onboarding chapters.

## 4. App icon, wordmark and visual identity

**Context.** BHODL has a primary amber colour (`#D4820A`) and a token system, but no icon,
no wordmark and no social / README banner. The desktop window and the Android launcher use
defaults.

**Deliverable.** App icon (desktop sizes, Android adaptive icon foreground / background,
monochrome), a wordmark, and a 1280×640 repository social preview. Must work on light and
dark. SVG sources under an MIT / Apache-2.0 compatible licence (or CC BY 4.0).

## 5. Colour accessibility audit: gains / losses and the neon theme

**Context.** Gains and losses use green and red, with arrows and signs as redundant cues;
Okabe-Ito tokens exist in the theme. The opt-in neon theme uses saturated colours on black
that may fail non-text contrast or collapse for colour-blind users. Wallet outgoing amounts
are neutral, red is reserved for errors.

**Deliverable.** An audit of light, dark and neon against WCAG 2.2 AA (text and non-text
contrast), with simulated deuteranopia / protanopia views of the portfolio, budget and
reports screens, and proposed token changes in [`../design/tokens.json`](../design/tokens.json)
format.

## 6. Data visualization review

**Context.** The tracker has a net-worth history chart (in sats, deliberately not
smoothed: volatility against fiat is the point), a net-worth donut, inflow / outflow bars,
a Sankey diagram, lollipop charts for spending by category, and sparklines. Each chart has a
table view. They were built one at a time and do not share a consistent visual grammar
(axes, legends, tooltips, period labels).

**Deliverable.** A short chart style guide (axes, gridlines, legends, tooltips, empty and
"no price data" states, privacy mode with hidden values) applied to the home and reports
screens. A proposal for showing "your euro savings lost X% against bitcoin" without
turning the screen into a trading app.

## 7. Empty states and the first tracker visit

**Context.** A new user's tracker is mostly empty cards: no history, no budgets, no
investments. The cards stay visible with a call to action, but the first impression is a
wall of zeros.

**Deliverable.** Empty-state designs for tracker home, budget, goals, portfolio and reports,
including copy, and a suggestion for an optional guided setup (add a watch-only wallet, set
two budgets, add one manual asset).

## 8. Portfolio and the sell flow (lot selection)

**Context.** Investments track tax lots with FIFO, HIFO or Specific-ID. When you sell, a
panel compares the three methods side by side with realized P&L. This is a feature no
other tracker shows, but it currently sits in a narrow slide-over. Cells without a
historical BTC rate show "no rate".

**Deliverable.** A redesign of the sell panel that makes the method comparison the main
content, and a better treatment of missing-rate cells in the portfolio balance sheet.
Mid or high fidelity.

## 9. Component library in Figma or Penpot

**Context.** The tokens in [`../design/tokens.json`](../design/tokens.json) follow the W3C
Design Tokens format (Tokens Studio compatible), but there is no design file. Every task
above would be faster with one.

**Deliverable.** A public Figma or Penpot library with the tokens imported and the core
components rebuilt from the screenshots: navigation rail, status bar, dense table row
(32 px and 24 px), chips and badges, amount cell (sats / BTC / fiat, privacy-hidden),
slide-over panel, buttons, inputs, dialogs. Light and dark.

---

Have an idea that is not here? Open a
[design feedback issue](https://github.com/mariepellegrino89/bhodl/issues/new?template=design_feedback.yml).

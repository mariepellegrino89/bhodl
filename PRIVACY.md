# Privacy policy

BHODL has no account, no cloud and no telemetry. Everything you enter (wallets, labels,
transactions, budgets, notes) lives in an encrypted database on your computer, and the
BHODL project never receives any of it.

The app does connect to third-party servers for two things. Both can be changed or
turned off in **Settings → Network**.

## 1. Wallet sync (Esplora server)

To show balances and transactions, BHODL asks an Esplora server about the addresses of
the wallets you add. The server sees those addresses and, unless you use Tor, your IP
address, so it can link them to you.

| Network | Default server |
| --- | --- |
| Mainnet | `https://mempool.space/api` ([privacy policy](https://mempool.space/privacy-policy)) |
| Testnet4 | `https://mempool.space/testnet4/api` |
| Signet | `https://blockstream.info/signet/api` ([privacy policy](https://blockstream.com/privacy/)) |

Nothing is synced until you add a wallet. You can:

- point BHODL at your own Esplora server;
- route every request through Tor;
- or both.

## 2. Prices (Online prices)

To show fiat values and the BTC value of past entries, BHODL downloads BTC prices.
Only a currency and, for past prices, a date are sent; addresses, amounts and balances
never are.

- **Price providers:**
  - [CoinGecko](https://www.coingecko.com/en/privacy)
  - [mempool.space](https://mempool.space/privacy-policy)
  - [blockchain.com](https://www.blockchain.com/legal/privacy) (`api.blockchain.info`)
  - [Frankfurter](https://frankfurter.dev/) (European Central Bank rates)
- **Investment quotes:** [Yahoo Finance](https://legal.yahoo.com/us/en/yahoo/privacy/index.html),
  for investments with a ticker. This is off by default; turn it on with "Auto-fetch
  investment prices". The ticker is sent.

Turn **Online prices** off and no price request leaves your computer. Fiat values then
use the prices already saved, or show no price. When Tor is on, price requests go
through Tor too.

## 3. What BHODL never does

- It sends no analytics, crash reports or usage data.
- It never contacts the BHODL project or its developers.
- It never holds private keys and cannot send bitcoin.

Links you open yourself (for example a transaction on mempool.space) open in your
browser, under that site's own policy.

## Contact

Questions: open an issue on [GitHub](https://github.com/mariepellegrino89/bhodl/issues).
Security issues: see [`SECURITY.md`](SECURITY.md).

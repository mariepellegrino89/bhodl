package io.bhodl.desktop.data

import io.bhodl.desktop.prices.TrackerBtcRate
import io.bhodl.ffi.DemoEventFfi
import io.bhodl.ffi.Network
import io.bhodl.ffi.RatePointFfi
import io.bhodl.ffi.RegistryHandle
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * Test mode — the project's test data, built on a fresh test profile:
 * two signet wallets with eight months of transactions, eight years of
 * BTC rates, budgets, bills, savings goals, rules, off-wallet spending,
 * investments with lots, manual assets and debts. Every date is
 * relative to [populate]'s `today` and every amount is deterministic,
 * so two builds on the same day are identical.
 *
 * Names are Italian; classic categories are the presets
 * (`cat_preset_*`), shown in the app language.
 */
internal object DemoSeed {

    // Public signet / testnet example addresses: never synced in test mode.
    private const val SPENDING_ADDRESS = "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj"
    private const val SAVINGS_ADDRESS = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    private const val CONFIRMED_HEIGHT = 300_000u
    private const val FEE_SAT = 200uL

    fun populate(registry: RegistryHandle, today: LocalDate) {
        TestMode.writeProfileSettings(registry)
        val rates = rates(today)
        rates.forEach { (currency, points) -> registry.putDemoRates(currency, points) }
        val eur = rates.getValue("EUR").associate { it.dateUnix to it.rate }
        fun eurPerBtc(date: LocalDate): Double = eur[day(date)] ?: 95_000.0

        val spending = registry.addWatchAddresses("Spese quotidiane", listOf(SPENDING_ADDRESS), Network.SIGNET).id
        val savings = registry.addWatchAddresses("Risparmi", listOf(SAVINGS_ADDRESS), Network.SIGNET).id
        seedWallets(registry, today, spending, savings)
        seedTracking(registry, today)
        seedPortfolio(registry, today)
        seedManualTxns(registry, today, ::eurPerBtc)
    }

    private fun day(date: LocalDate): Long = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

    private fun noon(date: LocalDate): Long = date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC)

    /** A txid-looking id, stable for the same wallet, day and key. */
    private fun txid(wallet: String, date: LocalDate, key: String): String =
        MessageDigest.getInstance("SHA-256").digest("bhodl-demo|$wallet|$date|$key".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------
    // BTC rates
    // ------------------------------------------------------------------

    /** EUR price, months before today → value; log-interpolated per day. */
    private val EUR_ANCHORS = listOf(
        96 to 5_500.0, 90 to 3_200.0, 84 to 9_000.0, 78 to 6_500.0, 72 to 8_000.0, 66 to 10_000.0,
        60 to 30_000.0, 54 to 50_000.0, 51 to 32_000.0, 48 to 55_000.0, 42 to 38_000.0, 36 to 19_000.0,
        30 to 16_000.0, 24 to 25_000.0, 18 to 40_000.0, 12 to 60_000.0, 9 to 58_000.0, 6 to 90_000.0,
        3 to 100_000.0, 0 to 95_000.0,
    )
    private val FX = mapOf("EUR" to 1.0, "USD" to 1.09, "CHF" to 0.95)

    private fun rates(today: LocalDate): Map<String, List<RatePointFfi>> {
        val anchors = EUR_ANCHORS.map { (m, v) -> day(today.minusMonths(m.toLong())) to ln(v) }
        val first = today.minusMonths(EUR_ANCHORS.first().first.toLong())
        val days = ChronoUnit.DAYS.between(first, today)
        val eur = (0..days).map { i ->
            val date = first.plusDays(i)
            val t = day(date)
            val k = anchors.indexOfLast { it.first <= t }.coerceIn(0, anchors.size - 2)
            val (t0, v0) = anchors[k]
            val (t1, v1) = anchors[k + 1]
            val f = if (t1 == t0) 0.0 else (t - t0).toDouble() / (t1 - t0)
            // Day-to-day wiggle, zero on the anchor days' neighbourhood scale.
            val wiggle = 0.025 * sin(i / 6.0) + 0.012 * sin(i / 1.7)
            t to exp(v0 + (v1 - v0) * f + wiggle)
        }
        return FX.mapValues { (_, fx) ->
            eur.map { (t, v) -> RatePointFfi(t, (v * fx).roundToLong().toDouble()) }
        }
    }

    // ------------------------------------------------------------------
    // Wallet transactions and their categories
    // ------------------------------------------------------------------

    private class Demo(val wallet: String, val date: LocalDate, val key: String, val incoming: Boolean, val sat: Long, val category: String?)

    private fun seedWallets(registry: RegistryHandle, today: LocalDate, spending: String, savings: String) {
        val out = mutableListOf<Demo>()
        val start = today.minusMonths(8).withDayOfMonth(1)
        fun add(wallet: String, date: LocalDate, key: String, incoming: Boolean, sat: Long, cat: String?) {
            if (date.isAfter(today) || date.isBefore(start)) return
            out += Demo(wallet, date, key, incoming, sat, cat)
        }
        var month = start
        while (!month.isAfter(today)) {
            val r = Random(month.monthValue * 31 + month.year % 100)
            fun d(dayOfMonth: Int) = month.withDayOfMonth(dayOfMonth.coerceAtMost(month.lengthOfMonth()))
            // Recurring income.
            add(spending, d(27), "salary", true, 3_200_000, "Stipendio")
            add(spending, d(5), "rent-in", true, 600_000, "Affitto ricevuto")
            // Fixed expenses (exact amounts: the recurring detector keys on them).
            add(spending, d(1), "rent", false, 950_000, "Casa")
            add(spending, d(3), "gym", false, 40_000, "Salute")
            add(spending, d(10), "internet", false, 25_000, "Bollette")
            add(spending, d(18), "netflix", false, 13_000, "Abbonamenti")
            add(spending, d(22), "spotify", false, 11_000, "Abbonamenti")
            add(spending, d(15), "power", false, 120_000L + r.nextLong(0, 50_000), "Bollette")
            // Variable expenses.
            for (x in listOf(2, 9, 16, 23, 30)) add(spending, d(x), "food$x", false, 55_000L + r.nextLong(0, 60_000), "Cibo")
            for (x in listOf(6, 14, 25)) add(spending, d(x), "restaurant$x", false, 28_000L + r.nextLong(0, 55_000), "Ristoranti")
            for (x in listOf(4, 8, 12, 19, 24, 28)) add(spending, d(x), "transport$x", false, 6_000L + r.nextLong(0, 30_000), "Trasporti")
            add(spending, d(11), "shopping", false, 60_000L + r.nextLong(0, 140_000), "Shopping")
            if (r.nextBoolean()) add(spending, d(20), "leisure", false, 25_000L + r.nextLong(0, 60_000), "Svago")
            // Monthly saving: out of the spending wallet, into the savings one.
            add(savings, d(28), "dca", true, 900_000L, null)
            month = month.plusMonths(1)
        }
        // One-offs.
        add(spending, today.minusMonths(3).withDayOfMonth(12), "freelance-a", true, 850_000, "Freelance")
        add(spending, today.minusMonths(1).withDayOfMonth(8), "freelance-b", true, 520_000, "Freelance")
        add(spending, today.minusMonths(2).withDayOfMonth(17), "trip", false, 480_000, "Viaggi")
        add(spending, today.minusMonths(4).withDayOfMonth(21), "gift", false, 90_000, "Regali")
        add(savings, today.minusMonths(6).withDayOfMonth(2), "opening", true, 25_000_000, null)
        // Recent and uncategorised: the "to review" lane.
        add(spending, today.minusDays(1), "unknown-a", false, 34_500, null)
        add(spending, today.minusDays(2), "unknown-b", false, 18_000, null)
        add(spending, today.minusDays(4), "unknown-c", true, 75_000, null)

        registry.addDemoEvents(
            out.map { d ->
                DemoEventFfi(
                    walletId = d.wallet,
                    eventId = txid(d.wallet, d.date, d.key),
                    direction = if (d.incoming) "in" else "out",
                    amountSat = d.sat.toULong(),
                    feeSat = if (d.incoming) null else FEE_SAT,
                    occurredUnix = noon(d.date),
                    // Today's transactions are still unconfirmed.
                    blockHeight = if (d.date == today) null else CONFIRMED_HEIGHT,
                )
            },
        )
        out.forEach { d -> d.category?.let { registry.setEventCategory(d.wallet, txid(d.wallet, d.date, d.key), it) } }
    }

    // ------------------------------------------------------------------
    // Budgets, bills, goals, rules
    // ------------------------------------------------------------------

    private fun seedTracking(registry: RegistryHandle, today: LocalDate) {
        listOf(
            "Cibo" to 400_000uL, "Ristoranti" to 200_000uL, "Trasporti" to 120_000uL,
            "Svago" to 100_000uL, "Shopping" to 150_000uL, "Abbonamenti" to 30_000uL, "Bollette" to 180_000uL,
        ).forEach { (cat, sat) -> registry.setBudget(cat, sat, "monthly") }

        registry.setBillCategory(registry.addBill("Affitto", 950_000uL, 1u, null), "Casa")
        val power = registry.addBill("Luce e gas", 140_000uL, 15u, null)
        registry.setBillVariable(power, true)
        registry.setBillCategory(power, "Bollette")
        registry.setBillCategory(registry.addBill("Internet", 25_000uL, 10u, "Fibra"), "Bollette")
        registry.setBillCategory(registry.addBill("Assicurazione auto", 60_000uL, 28u, "Rata mensile"), "Trasporti")
        val gym = registry.addBill("Palestra", 40_000uL, 3u, null)
        registry.setBillCategory(gym, "Salute")
        if (today.dayOfMonth >= 3) registry.markBillPaid(gym, day(today.withDayOfMonth(3)))

        registry.addSavingsGoal("Fondo emergenza", 30_000_000uL, 12_500_000uL, "#3FB950", null, null, null, "manual", null, "savings")
        registry.addSavingsGoal("Vacanza estate", 5_000_000uL, 1_800_000uL, "#58A6FF", day(today.plusMonths(8)), null, null, "manual", null, "sinking")
        registry.addSavingsGoal("Nuovo portatile", 2_500_000uL, 2_100_000uL, "#BC8CFF", day(today.plusMonths(2)), null, null, "manual", null, "sinking")
        registry.addSavingsGoal("Stack BTC 1 BTC", 100_000_000uL, 0uL, "#F7931A", null, null, null, "bitcoin_cash", null, "savings")

        registry.addRule("Netflix → Abbonamenti", "amount_out", "eq", "13000", "category", "Abbonamenti", 1, true)
        registry.addRule("Spotify → Abbonamenti", "amount_out", "eq", "11000", "category", "Abbonamenti", 0, true)
    }

    // ------------------------------------------------------------------
    // Portfolio
    // ------------------------------------------------------------------

    private class Lot(val monthsAgo: Long, val qty: Double, val price: Double, val fees: Double = 0.0)

    private fun seedPortfolio(registry: RegistryHandle, today: LocalDate) {
        fun ago(months: Long) = day(today.minusMonths(months))
        fun details(table: String, id: Long, json: String) = registry.setPortfolioDetails(table, id, json)
        val lotIds = mutableListOf<Long>()
        fun investment(name: String, type: String, ticker: String?, unit: String, price: Double, lots: List<Lot>, json: String? = null) {
            val qty = lots.sumOf { it.qty }
            val cost = lots.sumOf { it.qty * it.price + it.fees }
            val id = registry.addInvestment(name, type, ticker, qty, unit, cost, "EUR", ago(lots.first().monthsAgo), price, null)
            lots.forEach { lotIds += registry.addInvestmentLot(id, it.qty, it.price, it.fees, ago(it.monthsAgo), null) }
            json?.let { details("investment", id, it) }
        }
        investment(
            "VWCE · FTSE All-World", "etf", "VWCE.DE", "shares", 138.4,
            listOf(Lot(31, 50.0, 108.2, 2.0), Lot(23, 40.0, 121.6, 2.0), Lot(16, 30.0, 128.9, 2.0)),
            """{"isin":"IE00BK5BQT80","ter_pct":0.22,"distribution":"acc","broker":"Scalable"}""",
        )
        investment(
            "SXR8 · S&P 500", "etf", "SXR8.DE", "shares", 588.0,
            listOf(Lot(29, 10.0, 482.0, 1.0), Lot(20, 8.0, 561.5, 1.0)),
            """{"isin":"IE00B5BMR087","ter_pct":0.07,"distribution":"acc","broker":"Scalable"}""",
        )
        investment(
            "Apple", "stock", "AAPL", "shares", 205.0,
            listOf(Lot(26, 15.0, 172.0, 4.0), Lot(18, 5.0, 181.0, 4.0)),
            """{"isin":"US0378331005","broker":"Fineco"}""",
        )
        investment("Fondo pensione", "fund", null, "units", 11.4, listOf(Lot(33, 300.0, 10.1), Lot(21, 150.0, 10.7)))
        investment("Oro fisico", "commodity", null, "g", 96.0, listOf(Lot(32, 100.0, 63.5), Lot(18, 50.0, 84.0)))
        investment("Conto in dollari", "currency", null, "USD", 0.88, listOf(Lot(25, 5000.0, 0.91)))

        val house = registry.addManualAsset("Casa", "real_estate", 285_000.0, "EUR", "Torino", null, ago(88), 240_000.0, "EUR")
        details("manual_asset", house, """{"city":"Torino","surface_m2":92,"use":"primary"}""")
        val deposit = registry.addManualAsset("Conto deposito", "cash", 20_000.0, "EUR", "Vincolato 12 mesi", null, ago(8), 20_000.0, "EUR")
        details("manual_asset", deposit, """{"bank":"Banca Test","interest_pct":3.1,"locked":"true"}""")
        registry.addManualAsset("Conto corrente", "cash", 3_200.0, "EUR", "Banca Test", null, ago(40), 3_200.0, "EUR")
        registry.addManualAsset("Auto", "vehicle", 14_500.0, "EUR", "Panda 2021", null, ago(61), 19_000.0, "EUR")

        val mortgage = registry.addLiability("Mutuo casa", 180_000_000uL, "mortgage", "tasso fisso")
        details(
            "liability", mortgage,
            """{"original_amount_sat":"290000000","rate_pct":2.4,"rate_type":"fixed","monthly_payment":690,"linked_asset_id":"$house"}""",
        )
        val card = registry.addLiability("Carta di credito", 1_250_000uL, "credit-card", "Visa")
        details("liability", card, """{"credit_limit_sat":"4000000","statement_day":"15"}""")

        // The BTC rate of each lot's day (vs HODL), from the profile's rates.
        lotIds.forEach { TrackerBtcRate.resolveLot(registry, it) }
    }

    // ------------------------------------------------------------------
    // Off-wallet income and spending (schema v8)
    // ------------------------------------------------------------------

    private fun seedManualTxns(registry: RegistryHandle, today: LocalDate, eurPerBtc: (LocalDate) -> Double) {
        val card = registry.listLiabilities().firstOrNull { it.kind == "credit-card" }?.let { "liability:${it.id}" }
        val bank = registry.listManualAssets().firstOrNull { it.name == "Conto corrente" }?.let { "asset:${it.id}" }
        class Entry(val daysAgo: Long, val dir: String, val eur: Double, val account: String?, val category: String?, val note: String)
        listOf(
            Entry(0, "out", 4.5, null, "Ristoranti", "Caffè e cornetto"),
            Entry(2, "out", 23.0, null, "Cibo", "Mercato"),
            Entry(3, "out", 86.4, card, "Cibo", "Supermercato"),
            Entry(5, "out", 60.0, card, "Trasporti", "Benzina"),
            Entry(8, "out", 39.9, card, "Shopping", "Libri"),
            Entry(9, "out", 12.0, null, null, "Parcheggio"),
            Entry(12, "in", 150.0, bank, "Freelance", "Bonifico cliente"),
            Entry(20, "out", 32.0, bank, "Svago", "Cinema"),
            Entry(35, "out", 91.2, card, "Cibo", "Supermercato"),
            Entry(40, "out", 55.0, card, "Trasporti", "Benzina"),
        ).forEach { e ->
            val date = today.minusDays(e.daysAgo)
            val sat = (e.eur / eurPerBtc(date) * 100_000_000.0).roundToLong().toULong()
            val id = registry.addManualTxn(e.dir, sat, e.eur, "EUR", noon(date), e.account, e.note)
            e.category?.let { registry.setEventCategory(io.bhodl.ffi.manualTxnWalletId(e.account), io.bhodl.ffi.manualTxnEventId(id), it) }
        }
    }
}

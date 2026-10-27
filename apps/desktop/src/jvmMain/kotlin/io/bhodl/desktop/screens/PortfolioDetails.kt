package io.bhodl.desktop.screens

import io.bhodl.desktop.AppClock
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.pfc_cat_brokerage
import io.bhodl.shared.resources.pfc_cat_business
import io.bhodl.shared.resources.pfc_cat_cash
import io.bhodl.shared.resources.pfc_cat_collectible
import io.bhodl.shared.resources.pfc_cat_commodity
import io.bhodl.shared.resources.pfc_cat_credit_card
import io.bhodl.shared.resources.pfc_cat_currency
import io.bhodl.shared.resources.pfc_cat_etf
import io.bhodl.shared.resources.pfc_cat_fund
import io.bhodl.shared.resources.pfc_cat_loan
import io.bhodl.shared.resources.pfc_cat_loan_to_others
import io.bhodl.shared.resources.pfc_cat_mortgage
import io.bhodl.shared.resources.pfc_cat_other_asset
import io.bhodl.shared.resources.pfc_cat_other_debt
import io.bhodl.shared.resources.pfc_cat_other_investment
import io.bhodl.shared.resources.pfc_cat_real_estate
import io.bhodl.shared.resources.pfc_cat_stock
import io.bhodl.shared.resources.pfc_cat_vehicle
import io.bhodl.shared.resources.pfc_cat_wallet
import io.bhodl.shared.resources.pfc_f_account_last4
import io.bhodl.shared.resources.pfc_f_appraisal_date
import io.bhodl.shared.resources.pfc_f_bank
import io.bhodl.shared.resources.pfc_f_borrower
import io.bhodl.shared.resources.pfc_f_broker
import io.bhodl.shared.resources.pfc_f_city
import io.bhodl.shared.resources.pfc_f_collectible_category
import io.bhodl.shared.resources.pfc_f_company
import io.bhodl.shared.resources.pfc_f_credit_limit_sat
import io.bhodl.shared.resources.pfc_f_depreciation_pct
import io.bhodl.shared.resources.pfc_f_distribution
import io.bhodl.shared.resources.pfc_f_dividend_per_unit
import io.bhodl.shared.resources.pfc_f_dividends_12m
import io.bhodl.shared.resources.pfc_f_due_date
import io.bhodl.shared.resources.pfc_f_end_date
import io.bhodl.shared.resources.pfc_f_exchange
import io.bhodl.shared.resources.pfc_f_form
import io.bhodl.shared.resources.pfc_f_index_name
import io.bhodl.shared.resources.pfc_f_institution
import io.bhodl.shared.resources.pfc_f_insured
import io.bhodl.shared.resources.pfc_f_interest_pct
import io.bhodl.shared.resources.pfc_f_isin
import io.bhodl.shared.resources.pfc_f_km
import io.bhodl.shared.resources.pfc_f_linked_asset_id
import io.bhodl.shared.resources.pfc_f_locked
import io.bhodl.shared.resources.pfc_f_manager
import io.bhodl.shared.resources.pfc_f_maturity_date
import io.bhodl.shared.resources.pfc_f_monthly_payment
import io.bhodl.shared.resources.pfc_f_monthly_rent
import io.bhodl.shared.resources.pfc_f_original_amount
import io.bhodl.shared.resources.pfc_f_original_amount_sat
import io.bhodl.shared.resources.pfc_f_platform
import io.bhodl.shared.resources.pfc_f_purity
import io.bhodl.shared.resources.pfc_f_rate_pct
import io.bhodl.shared.resources.pfc_f_rate_type
import io.bhodl.shared.resources.pfc_f_sector
import io.bhodl.shared.resources.pfc_f_stake_pct
import io.bhodl.shared.resources.pfc_f_statement_day
import io.bhodl.shared.resources.pfc_f_storage
import io.bhodl.shared.resources.pfc_f_surface_m2
import io.bhodl.shared.resources.pfc_f_ter_pct
import io.bhodl.shared.resources.pfc_f_use
import io.bhodl.shared.resources.pfc_f_valuation_date
import io.bhodl.shared.resources.pfc_f_year
import io.bhodl.shared.resources.pfc_k_annual_cost
import io.bhodl.shared.resources.pfc_k_annual_interest
import io.bhodl.shared.resources.pfc_k_appraisal_age
import io.bhodl.shared.resources.pfc_k_avg_price
import io.bhodl.shared.resources.pfc_k_balance
import io.bhodl.shared.resources.pfc_k_change
import io.bhodl.shared.resources.pfc_k_company_value
import io.bhodl.shared.resources.pfc_k_depreciation
import io.bhodl.shared.resources.pfc_k_equity
import io.bhodl.shared.resources.pfc_k_estimated_value
import io.bhodl.shared.resources.pfc_k_freshness
import io.bhodl.shared.resources.pfc_k_fx_effect
import io.bhodl.shared.resources.pfc_k_gross_yield
import io.bhodl.shared.resources.pfc_k_linked_debt
import io.bhodl.shared.resources.pfc_k_ltv
import io.bhodl.shared.resources.pfc_k_months_left
import io.bhodl.shared.resources.pfc_k_next_statement
import io.bhodl.shared.resources.pfc_k_price
import io.bhodl.shared.resources.pfc_k_purchase
import io.bhodl.shared.resources.pfc_k_quantity
import io.bhodl.shared.resources.pfc_k_realized
import io.bhodl.shared.resources.pfc_k_remaining
import io.bhodl.shared.resources.pfc_k_repaid
import io.bhodl.shared.resources.pfc_k_revaluation
import io.bhodl.shared.resources.pfc_k_stack_share
import io.bhodl.shared.resources.pfc_k_term_elapsed
import io.bhodl.shared.resources.pfc_k_to_receive
import io.bhodl.shared.resources.pfc_k_unrealized
import io.bhodl.shared.resources.pfc_k_updated
import io.bhodl.shared.resources.pfc_k_utilization
import io.bhodl.shared.resources.pfc_k_value
import io.bhodl.shared.resources.pfc_k_weight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * Portfolio redesign 2026-10 (canvas proposals B + D + E) — the model
 * behind the class cards and the per-item detail cards:
 *
 *  - [PortfolioClass]: the four class cards (Bitcoin, Investments,
 *    Manual assets, Liabilities), one per balance-sheet section;
 *  - [DetailCategory]: what a row IS (an ETF, a deposit account, a
 *    mortgage…), derived from the stored asset type / kind;
 *  - [DetailField]: the kind-specific details a category can carry
 *    (registry v6 `details_json`), all optional;
 *  - [DetailCardModel]: the numbers a detail card shows, computed here
 *    as typed [CardValue]s so the composable only formats them.
 *
 * Everything in this file is pure: no Compose state, no I/O.
 */

/** The four class cards, in balance-sheet order. */
internal enum class PortfolioClass(val section: SheetSection) {
    BITCOIN(SheetSection.WALLETS),
    INVESTMENTS(SheetSection.INVESTMENTS),
    MANUAL_ASSETS(SheetSection.MANUAL_ASSETS),
    LIABILITIES(SheetSection.LIABILITIES),
    ;

    companion object {
        fun of(section: SheetSection): PortfolioClass = entries.first { it.section == section }
    }
}

/** Value types of a [DetailField]. */
internal enum class FieldType { TEXT, NUMBER, PERCENT, MONEY, SATS, DATE, BOOL, CHOICE, DAY_OF_MONTH, LINK_ASSET }

/** One optional, kind-specific detail. [key] is the `details_json` key. */
internal data class DetailField(
    val key: String,
    val label: StringResource,
    val type: FieldType,
    /** Wire values for [FieldType.CHOICE]; labels via [choiceLabelKey]. */
    val options: List<String> = emptyList(),
)

/** String-resource name of a choice option: `pfc_opt_<field>_<option>`. */
internal fun choiceLabelKey(field: String, option: String): String = "pfc_opt_${field}_$option"

private val F_ISIN = DetailField("isin", Res.string.pfc_f_isin, FieldType.TEXT)
private val F_BROKER = DetailField("broker", Res.string.pfc_f_broker, FieldType.TEXT)
private val F_TER = DetailField("ter_pct", Res.string.pfc_f_ter_pct, FieldType.PERCENT)
private val F_DISTRIBUTION = DetailField("distribution", Res.string.pfc_f_distribution, FieldType.CHOICE, listOf("acc", "dist"))
private val F_INTEREST = DetailField("interest_pct", Res.string.pfc_f_interest_pct, FieldType.PERCENT)
private val F_RATE = DetailField("rate_pct", Res.string.pfc_f_rate_pct, FieldType.PERCENT)
private val F_MONTHLY_PAYMENT = DetailField("monthly_payment", Res.string.pfc_f_monthly_payment, FieldType.MONEY)
private val F_END_DATE = DetailField("end_date", Res.string.pfc_f_end_date, FieldType.DATE)
private val F_ORIGINAL_SAT = DetailField("original_amount_sat", Res.string.pfc_f_original_amount_sat, FieldType.SATS)
private val F_LINKED_ASSET = DetailField("linked_asset_id", Res.string.pfc_f_linked_asset_id, FieldType.LINK_ASSET)

/**
 * What a portfolio row is. [cls] is the class card it belongs to,
 * [glyph] the short mark on its icon tile, [fields] its details.
 */
internal enum class DetailCategory(
    val cls: PortfolioClass,
    val glyph: String,
    val label: StringResource,
    val fields: List<DetailField>,
) {
    WALLET(PortfolioClass.BITCOIN, "₿", Res.string.pfc_cat_wallet, emptyList()),
    STOCK(
        PortfolioClass.INVESTMENTS, "▲", Res.string.pfc_cat_stock,
        listOf(
            F_ISIN,
            DetailField("exchange", Res.string.pfc_f_exchange, FieldType.TEXT),
            DetailField("sector", Res.string.pfc_f_sector, FieldType.TEXT),
            F_BROKER,
            DetailField("dividend_per_unit", Res.string.pfc_f_dividend_per_unit, FieldType.MONEY),
        ),
    ),
    ETF(
        PortfolioClass.INVESTMENTS, "◆", Res.string.pfc_cat_etf,
        listOf(F_ISIN, F_TER, F_DISTRIBUTION, DetailField("index_name", Res.string.pfc_f_index_name, FieldType.TEXT), F_BROKER),
    ),
    FUND(
        PortfolioClass.INVESTMENTS, "◇", Res.string.pfc_cat_fund,
        listOf(F_ISIN, F_TER, DetailField("manager", Res.string.pfc_f_manager, FieldType.TEXT), F_DISTRIBUTION),
    ),
    CURRENCY(
        PortfolioClass.INVESTMENTS, "¤", Res.string.pfc_cat_currency,
        listOf(DetailField("institution", Res.string.pfc_f_institution, FieldType.TEXT), F_INTEREST),
    ),
    COMMODITY(
        PortfolioClass.INVESTMENTS, "Au", Res.string.pfc_cat_commodity,
        listOf(
            DetailField("form", Res.string.pfc_f_form, FieldType.CHOICE, listOf("bar", "coin", "etc", "other")),
            DetailField("purity", Res.string.pfc_f_purity, FieldType.TEXT),
            DetailField("storage", Res.string.pfc_f_storage, FieldType.TEXT),
        ),
    ),
    OTHER_INVESTMENT(
        PortfolioClass.INVESTMENTS, "•", Res.string.pfc_cat_other_investment,
        listOf(DetailField("platform", Res.string.pfc_f_platform, FieldType.TEXT)),
    ),
    CASH(
        PortfolioClass.MANUAL_ASSETS, "€", Res.string.pfc_cat_cash,
        listOf(
            DetailField("bank", Res.string.pfc_f_bank, FieldType.TEXT),
            F_INTEREST,
            DetailField("maturity_date", Res.string.pfc_f_maturity_date, FieldType.DATE),
            DetailField("locked", Res.string.pfc_f_locked, FieldType.BOOL),
        ),
    ),
    BROKERAGE(
        PortfolioClass.MANUAL_ASSETS, "▣", Res.string.pfc_cat_brokerage,
        listOf(F_BROKER, DetailField("account_last4", Res.string.pfc_f_account_last4, FieldType.TEXT)),
    ),
    REAL_ESTATE(
        PortfolioClass.MANUAL_ASSETS, "⌂", Res.string.pfc_cat_real_estate,
        listOf(
            DetailField("city", Res.string.pfc_f_city, FieldType.TEXT),
            DetailField("surface_m2", Res.string.pfc_f_surface_m2, FieldType.NUMBER),
            DetailField("use", Res.string.pfc_f_use, FieldType.CHOICE, listOf("primary", "rental", "second", "land")),
            DetailField("monthly_rent", Res.string.pfc_f_monthly_rent, FieldType.MONEY),
        ),
    ),
    VEHICLE(
        PortfolioClass.MANUAL_ASSETS, "◈", Res.string.pfc_cat_vehicle,
        listOf(
            DetailField("year", Res.string.pfc_f_year, FieldType.NUMBER),
            DetailField("km", Res.string.pfc_f_km, FieldType.NUMBER),
            DetailField("depreciation_pct", Res.string.pfc_f_depreciation_pct, FieldType.PERCENT),
        ),
    ),
    COLLECTIBLE(
        PortfolioClass.MANUAL_ASSETS, "✦", Res.string.pfc_cat_collectible,
        listOf(
            DetailField("collectible_category", Res.string.pfc_f_collectible_category, FieldType.TEXT),
            DetailField("appraisal_date", Res.string.pfc_f_appraisal_date, FieldType.DATE),
            DetailField("insured", Res.string.pfc_f_insured, FieldType.BOOL),
        ),
    ),
    BUSINESS(
        PortfolioClass.MANUAL_ASSETS, "◎", Res.string.pfc_cat_business,
        listOf(
            DetailField("company", Res.string.pfc_f_company, FieldType.TEXT),
            DetailField("stake_pct", Res.string.pfc_f_stake_pct, FieldType.PERCENT),
            DetailField("valuation_date", Res.string.pfc_f_valuation_date, FieldType.DATE),
            DetailField("dividends_12m", Res.string.pfc_f_dividends_12m, FieldType.MONEY),
        ),
    ),
    LOAN_TO_OTHERS(
        PortfolioClass.MANUAL_ASSETS, "↗", Res.string.pfc_cat_loan_to_others,
        listOf(
            DetailField("borrower", Res.string.pfc_f_borrower, FieldType.TEXT),
            DetailField("original_amount", Res.string.pfc_f_original_amount, FieldType.MONEY),
            F_INTEREST,
            DetailField("due_date", Res.string.pfc_f_due_date, FieldType.DATE),
        ),
    ),
    OTHER_ASSET(PortfolioClass.MANUAL_ASSETS, "•", Res.string.pfc_cat_other_asset, emptyList()),
    MORTGAGE(
        PortfolioClass.LIABILITIES, "⌂", Res.string.pfc_cat_mortgage,
        listOf(
            F_ORIGINAL_SAT, F_RATE,
            DetailField("rate_type", Res.string.pfc_f_rate_type, FieldType.CHOICE, listOf("fixed", "variable")),
            F_MONTHLY_PAYMENT, F_END_DATE, F_LINKED_ASSET,
        ),
    ),
    LOAN(
        PortfolioClass.LIABILITIES, "%", Res.string.pfc_cat_loan,
        listOf(F_ORIGINAL_SAT, F_RATE, F_MONTHLY_PAYMENT, F_END_DATE, F_LINKED_ASSET),
    ),
    CREDIT_CARD(
        PortfolioClass.LIABILITIES, "▭", Res.string.pfc_cat_credit_card,
        listOf(
            DetailField("credit_limit_sat", Res.string.pfc_f_credit_limit_sat, FieldType.SATS),
            DetailField("statement_day", Res.string.pfc_f_statement_day, FieldType.DAY_OF_MONTH),
        ),
    ),
    OTHER_DEBT(PortfolioClass.LIABILITIES, "−", Res.string.pfc_cat_other_debt, emptyList()),
    ;

    /** `details_json` table of this category (wallets have none). */
    val detailsTable: String?
        get() = when (cls) {
            PortfolioClass.BITCOIN -> null
            PortfolioClass.INVESTMENTS -> "investment"
            PortfolioClass.MANUAL_ASSETS -> "manual_asset"
            PortfolioClass.LIABILITIES -> "liability"
        }

    companion object {
        fun ofInvestment(assetType: String): DetailCategory = when (assetType.trim().lowercase()) {
            "stock" -> STOCK
            "etf" -> ETF
            "fund" -> FUND
            "currency" -> CURRENCY
            "commodity" -> COMMODITY
            else -> OTHER_INVESTMENT
        }

        fun ofManualAsset(kind: String): DetailCategory = when (kind.trim().lowercase()) {
            "cash" -> CASH
            "brokerage" -> BROKERAGE
            "real_estate" -> REAL_ESTATE
            "vehicle" -> VEHICLE
            "collectible" -> COLLECTIBLE
            "business_equity" -> BUSINESS
            "loans_to_others" -> LOAN_TO_OTHERS
            else -> OTHER_ASSET
        }

        fun ofLiability(kind: String): DetailCategory = when (kind.trim().lowercase()) {
            "mortgage" -> MORTGAGE
            "loan" -> LOAN
            "credit-card" -> CREDIT_CARD
            else -> OTHER_DEBT
        }

        fun ofRow(row: SheetRow): DetailCategory = when (row.section) {
            SheetSection.WALLETS -> WALLET
            SheetSection.INVESTMENTS -> ofInvestment(row.kind)
            SheetSection.MANUAL_ASSETS -> ofManualAsset(row.kind)
            SheetSection.LIABILITIES -> ofLiability(row.kind)
        }
    }
}

// ---------------------------------------------------------------------------
// details_json ⇄ map
// ---------------------------------------------------------------------------

/** Details of every portfolio row, keyed by record id, per table. */
internal data class PortfolioDetails(
    val investments: Map<Long, Map<String, String>> = emptyMap(),
    val manualAssets: Map<Long, Map<String, String>> = emptyMap(),
    val liabilities: Map<Long, Map<String, String>> = emptyMap(),
) {
    fun forRow(section: SheetSection, id: Long?): Map<String, String> = when {
        id == null -> emptyMap()
        section == SheetSection.INVESTMENTS -> investments[id].orEmpty()
        section == SheetSection.MANUAL_ASSETS -> manualAssets[id].orEmpty()
        section == SheetSection.LIABILITIES -> liabilities[id].orEmpty()
        else -> emptyMap()
    }
}

private val detailsJson = Json { ignoreUnknownKeys = true }

/** A stored details object as `key → text`; malformed input reads as empty. */
internal fun parseDetailsJson(json: String): Map<String, String> = runCatching {
    (detailsJson.parseToJsonElement(json) as? JsonObject)
        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { p -> k to p.content } }
        ?.toMap()
}.getOrNull().orEmpty()

/**
 * The editor's `key → text` back to a details object. Numeric fields
 * become JSON numbers (decimal comma accepted), booleans JSON booleans;
 * blank and unparsable numeric values are dropped, as are keys that are
 * not fields of [category].
 */
internal fun detailsToJson(category: DetailCategory, values: Map<String, String>): String {
    val out = LinkedHashMap<String, JsonPrimitive>()
    for (f in category.fields) {
        val raw = values[f.key]?.trim().orEmpty()
        if (raw.isEmpty()) continue
        val prim: JsonPrimitive? = when (f.type) {
            FieldType.NUMBER, FieldType.PERCENT, FieldType.MONEY ->
                parseNumber(raw)?.let { JsonPrimitive(it) }
            FieldType.SATS, FieldType.DAY_OF_MONTH ->
                raw.filter { it.isDigit() }.toLongOrNull()?.let { JsonPrimitive(it) }
            FieldType.BOOL -> JsonPrimitive(raw.equals("true", ignoreCase = true))
            FieldType.DATE -> parseIsoDate(raw)?.let { JsonPrimitive(it.toString()) }
            FieldType.CHOICE -> raw.takeIf { it in f.options }?.let { JsonPrimitive(it) }
            FieldType.TEXT, FieldType.LINK_ASSET -> JsonPrimitive(raw)
        }
        if (prim != null) out[f.key] = prim
    }
    return JsonObject(out).toString()
}

/** `1.234,5` / `1,234.5` / `12,5` / `12.5` → Double. */
internal fun parseNumber(raw: String): Double? {
    val t = raw.trim().replace(" ", "").removeSuffix("%")
    if (t.isEmpty()) return null
    val lastComma = t.lastIndexOf(','); val lastDot = t.lastIndexOf('.')
    val normalized = when {
        lastComma > lastDot -> t.replace(".", "").replace(',', '.')
        else -> t.replace(",", "")
    }
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun parseIsoDate(raw: String): LocalDate? = runCatching { LocalDate.parse(raw.trim()) }.getOrNull()

private fun Map<String, String>.num(key: String): Double? = this[key]?.let(::parseNumber)
private fun Map<String, String>.date(key: String): LocalDate? = this[key]?.let(::parseIsoDate)

// ---------------------------------------------------------------------------
// Card model
// ---------------------------------------------------------------------------

/** A typed value on a card; the composable formats it. */
internal sealed interface CardValue {
    data class Sat(val sat: Long, val signed: Boolean = false) : CardValue
    data class Fiat(val amount: Double, val currency: String, val signed: Boolean = false) : CardValue
    data class Pct(val pct: Double, val signed: Boolean = false) : CardValue
    data class Qty(val qty: Double, val unit: String) : CardValue
    data class Date(val date: LocalDate) : CardValue
    data class DaysAgo(val days: Long) : CardValue
    data class Months(val months: Long) : CardValue
    data class InDays(val days: Long) : CardValue
    data class Text(val text: String) : CardValue
    data class Choice(val field: String, val option: String) : CardValue
    data class YesNo(val yes: Boolean) : CardValue
}

/** Tone of a KPI: drives its color. */
internal enum class Tone { NEUTRAL, POSITIVE, NEGATIVE, ACCENT }

internal data class CardKpi(val label: StringResource, val value: CardValue?, val tone: Tone = Tone.NEUTRAL)

internal data class CardMeter(val label: StringResource, val fraction: Double, val value: CardValue)

internal data class CardAttr(val label: StringResource, val value: CardValue)

internal data class DetailCardModel(
    val category: DetailCategory,
    /** Exactly four tiles; a null value renders "—". */
    val kpis: List<CardKpi>,
    val meter: CardMeter?,
    /** Filled details (and derived facts) in field order. */
    val attrs: List<CardAttr>,
)

private fun toneOf(v: Double?): Tone = when {
    v == null || v == 0.0 -> Tone.NEUTRAL
    v > 0.0 -> Tone.POSITIVE
    else -> Tone.NEGATIVE
}

private fun pctChange(now: Double?, then: Double?): Double? =
    if (now == null || then == null || then <= 0.0) null else (now - then) / then * 100.0

private fun epochDay(unix: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(unix, 86_400L))

private fun clamp01(v: Double): Double = v.coerceIn(0.0, 1.0)

/** The filled details of [category] as attribute rows, in field order. */
internal fun detailAttrs(
    category: DetailCategory,
    details: Map<String, String>,
    currency: String,
    linkedAssetName: (String) -> String? = { null },
): List<CardAttr> = category.fields.mapNotNull { f ->
    val raw = details[f.key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val value: CardValue = when (f.type) {
        FieldType.TEXT -> CardValue.Text(raw)
        FieldType.NUMBER -> parseNumber(raw)?.let { CardValue.Qty(it, "") } ?: CardValue.Text(raw)
        FieldType.PERCENT -> parseNumber(raw)?.let { CardValue.Pct(it) } ?: CardValue.Text(raw)
        FieldType.MONEY -> parseNumber(raw)?.let { CardValue.Fiat(it, currency) } ?: CardValue.Text(raw)
        FieldType.SATS -> raw.toLongOrNull()?.let { CardValue.Sat(it) } ?: CardValue.Text(raw)
        FieldType.DATE -> parseIsoDate(raw)?.let { CardValue.Date(it) } ?: CardValue.Text(raw)
        FieldType.BOOL -> CardValue.YesNo(raw.equals("true", ignoreCase = true))
        FieldType.CHOICE -> CardValue.Choice(f.key, raw)
        FieldType.DAY_OF_MONTH -> CardValue.Text(raw)
        FieldType.LINK_ASSET -> CardValue.Text(linkedAssetName(raw) ?: raw)
    }
    CardAttr(f.label, value)
}

/** Bitcoin wallet group: balance, share of the BTC stack, change, weight. */
internal fun walletCardModel(row: SheetRow, stackSat: Long): DetailCardModel {
    val value = row.valueSat ?: 0L
    val share = if (stackSat > 0L) value.toDouble() / stackSat else 0.0
    return DetailCardModel(
        category = DetailCategory.WALLET,
        kpis = listOf(
            CardKpi(Res.string.pfc_k_balance, CardValue.Sat(value), Tone.ACCENT),
            CardKpi(Res.string.pfc_k_stack_share, CardValue.Pct(share * 100.0)),
            CardKpi(Res.string.pfc_k_change, row.changeSat?.let { CardValue.Sat(it, signed = true) }, toneOf(row.changeSat?.toDouble())),
            CardKpi(Res.string.pfc_k_weight, row.pctOfNetWorth?.let { CardValue.Pct(it) }),
        ),
        meter = CardMeter(Res.string.pfc_k_stack_share, clamp01(share), CardValue.Pct(share * 100.0)),
        attrs = emptyList(),
    )
}

/**
 * Investment card. Borsa (stock / ETF / fund) and "other": value,
 * quantity, average price, unrealized P&L; currency: amount, value,
 * rate, FX effect; commodity: quantity, price per unit, value, P&L.
 */
internal fun investmentCardModel(
    inv: TrackingInvestmentFfi,
    lots: List<TrackingInvestmentLotFfi>,
    disposals: List<TrackingLotDisposalFfi>,
    details: Map<String, String>,
    row: SheetRow,
): DetailCardModel {
    val category = DetailCategory.ofInvestment(inv.assetType)
    val ccy = inv.costBasisCurrency.trim().uppercase()
    val qty = effectiveQuantity(inv, lots)
    val cost = effectiveCostBasis(inv, lots)
    val price = inv.fetchedCurrentValue ?: inv.manualCurrentValue
    val avg = if (qty > 0.0 && cost > 0.0) cost / qty else null
    val valueFiat = price?.let { it * qty }
    val unrealized = if (lots.isNotEmpty()) {
        unrealizedPnl(lots, price, disposals)
    } else {
        valueFiat?.let { it - cost }
    }
    val unrealizedPct = if (unrealized != null && cost > 0.0) unrealized / cost * 100.0 else null
    val realized = realizedPnl(disposals)
    val value = row.valueSat?.let { CardValue.Sat(it) } ?: valueFiat?.let { CardValue.Fiat(it, ccy) }
    val unit = inv.unit.ifBlank { "" }
    val kpis = when (category) {
        DetailCategory.CURRENCY -> listOf(
            CardKpi(Res.string.pfc_k_quantity, CardValue.Qty(qty, unit.ifBlank { inv.ticker.orEmpty() })),
            CardKpi(Res.string.pfc_k_value, value),
            CardKpi(Res.string.pfc_k_price, price?.let { CardValue.Fiat(it, ccy) }),
            CardKpi(Res.string.pfc_k_fx_effect, pctChange(price, avg)?.let { CardValue.Pct(it, signed = true) }, toneOf(pctChange(price, avg))),
        )
        DetailCategory.COMMODITY -> listOf(
            CardKpi(Res.string.pfc_k_quantity, CardValue.Qty(qty, unit)),
            CardKpi(Res.string.pfc_k_price, price?.let { CardValue.Fiat(it, ccy) }),
            CardKpi(Res.string.pfc_k_value, value),
            CardKpi(Res.string.pfc_k_unrealized, unrealizedPct?.let { CardValue.Pct(it, signed = true) }, toneOf(unrealized)),
        )
        else -> listOf(
            CardKpi(Res.string.pfc_k_value, value),
            CardKpi(Res.string.pfc_k_quantity, CardValue.Qty(qty, unit)),
            CardKpi(Res.string.pfc_k_avg_price, avg?.let { CardValue.Fiat(it, ccy) }),
            CardKpi(
                Res.string.pfc_k_unrealized,
                unrealized?.let { CardValue.Fiat(it, ccy, signed = true) },
                toneOf(unrealized),
            ),
        )
    }
    val derived = buildList {
        if (category != DetailCategory.CURRENCY && disposals.isNotEmpty()) {
            add(CardAttr(Res.string.pfc_k_realized, CardValue.Fiat(realized, ccy, signed = true)))
        }
        val ter = details.num("ter_pct")
        if (ter != null && valueFiat != null) {
            add(CardAttr(Res.string.pfc_k_annual_cost, CardValue.Fiat(valueFiat * ter / 100.0, ccy)))
        }
        val interest = details.num("interest_pct")
        if (interest != null && category == DetailCategory.CURRENCY) {
            add(CardAttr(Res.string.pfc_k_annual_interest, CardValue.Qty(qty * interest / 100.0, unit)))
        }
    }
    val weight = row.pctOfNetWorth
    return DetailCardModel(
        category = category,
        kpis = kpis,
        meter = weight?.let { CardMeter(Res.string.pfc_k_weight, clamp01(it / 100.0), CardValue.Pct(it)) },
        attrs = detailAttrs(category, details, ccy) + derived,
    )
}

/**
 * Manual asset card. [linkedDebtFiat] is the sum of the liabilities
 * whose `linked_asset_id` points at this asset, in the asset's currency
 * (real estate: equity and LTV).
 */
internal fun manualAssetCardModel(
    a: TrackingManualAssetFfi,
    details: Map<String, String>,
    row: SheetRow,
    nowUnix: Long,
    linkedDebtFiat: Double? = null,
): DetailCardModel {
    val category = DetailCategory.ofManualAsset(a.kind)
    val ccy = a.currency.trim().uppercase()
    val value = a.valueFiat
    val purchase = a.purchaseValueFiat?.takeIf { (a.purchaseCurrency ?: ccy).equals(ccy, ignoreCase = true) }
    val revaluation = pctChange(value, purchase)
    val today = epochDay(nowUnix)
    val updatedDays = ChronoUnit.DAYS.between(epochDay(a.lastUpdatedUnix), today).coerceAtLeast(0L)
    val vFiat = CardValue.Fiat(value, ccy)
    val purchaseKpi = CardKpi(Res.string.pfc_k_purchase, purchase?.let { CardValue.Fiat(it, ccy) })
    val revalKpi = CardKpi(Res.string.pfc_k_revaluation, revaluation?.let { CardValue.Pct(it, signed = true) }, toneOf(revaluation))
    val derived = mutableListOf<CardAttr>()
    var meter: CardMeter? = null
    val kpis: List<CardKpi> = when (category) {
        DetailCategory.CASH -> {
            val rate = details.num("interest_pct")
            val maturity = details.date("maturity_date")
            val start = epochDay(a.purchaseUnix ?: a.createdAtUnix)
            if (maturity != null && maturity.isAfter(start)) {
                val total = ChronoUnit.DAYS.between(start, maturity).toDouble()
                val done = ChronoUnit.DAYS.between(start, today).toDouble()
                meter = CardMeter(Res.string.pfc_k_term_elapsed, clamp01(done / total), CardValue.Pct(clamp01(done / total) * 100.0))
            }
            listOf(
                CardKpi(Res.string.pfc_k_balance, vFiat),
                CardKpi(Res.string.pfc_f_interest_pct, rate?.let { CardValue.Pct(it) }),
                CardKpi(Res.string.pfc_k_annual_interest, rate?.let { CardValue.Fiat(value * it / 100.0, ccy, signed = true) }, toneOf(rate)),
                CardKpi(Res.string.pfc_f_maturity_date, maturity?.let { CardValue.Date(it) }),
            )
        }
        DetailCategory.BROKERAGE -> {
            meter = CardMeter(Res.string.pfc_k_freshness, clamp01(updatedDays / 30.0), CardValue.DaysAgo(updatedDays))
            listOf(
                CardKpi(Res.string.pfc_k_value, vFiat),
                CardKpi(Res.string.pfc_k_updated, CardValue.DaysAgo(updatedDays)),
                CardKpi(Res.string.pfc_k_weight, row.pctOfNetWorth?.let { CardValue.Pct(it) }),
                CardKpi(Res.string.pfc_k_change, row.changeSat?.let { CardValue.Sat(it, signed = true) }, toneOf(row.changeSat?.toDouble())),
            )
        }
        DetailCategory.REAL_ESTATE -> {
            val debt = linkedDebtFiat?.takeIf { it > 0.0 }
            if (debt != null && value > 0.0) {
                meter = CardMeter(Res.string.pfc_k_ltv, clamp01(debt / value), CardValue.Pct(debt / value * 100.0))
                derived += CardAttr(Res.string.pfc_k_linked_debt, CardValue.Fiat(debt, ccy))
            }
            val rent = details.num("monthly_rent")
            if (rent != null && value > 0.0) {
                derived += CardAttr(Res.string.pfc_k_gross_yield, CardValue.Pct(rent * 12.0 / value * 100.0))
            }
            listOf(
                CardKpi(Res.string.pfc_k_value, vFiat),
                purchaseKpi,
                revalKpi,
                CardKpi(Res.string.pfc_k_equity, CardValue.Fiat(value - (debt ?: 0.0), ccy)),
            )
        }
        DetailCategory.VEHICLE -> {
            val dep = details.num("depreciation_pct")
            val bought = a.purchaseUnix?.let(::epochDay)
            if (purchase != null && dep != null && bought != null) {
                val years = ChronoUnit.DAYS.between(bought, today) / 365.25
                val estimate = purchase * Math.pow(1.0 - dep / 100.0, years.coerceAtLeast(0.0))
                derived += CardAttr(Res.string.pfc_k_estimated_value, CardValue.Fiat(estimate, ccy))
            }
            if (purchase != null && purchase > 0.0) {
                meter = CardMeter(Res.string.pfc_k_remaining, clamp01(value / purchase), CardValue.Pct(value / purchase * 100.0))
            }
            listOf(
                CardKpi(Res.string.pfc_k_value, vFiat),
                purchaseKpi,
                CardKpi(Res.string.pfc_k_depreciation, revaluation?.let { CardValue.Pct(it, signed = true) }, toneOf(revaluation)),
                CardKpi(Res.string.pfc_f_km, details.num("km")?.let { CardValue.Qty(it, "km") }),
            )
        }
        DetailCategory.COLLECTIBLE -> {
            val appraisal = details.date("appraisal_date")
            val months = appraisal?.let { ChronoUnit.MONTHS.between(it, today).coerceAtLeast(0L) }
            if (months != null) {
                meter = CardMeter(Res.string.pfc_k_appraisal_age, clamp01(months / 24.0), CardValue.Months(months))
            }
            listOf(
                CardKpi(Res.string.pfc_k_value, vFiat),
                purchaseKpi,
                revalKpi,
                CardKpi(Res.string.pfc_k_appraisal_age, months?.let { CardValue.Months(it) }, if ((months ?: 0L) > 24L) Tone.NEGATIVE else Tone.NEUTRAL),
            )
        }
        DetailCategory.BUSINESS -> {
            val stake = details.num("stake_pct")?.takeIf { it > 0.0 }
            if (stake != null) meter = CardMeter(Res.string.pfc_f_stake_pct, clamp01(stake / 100.0), CardValue.Pct(stake))
            listOf(
                CardKpi(Res.string.pfc_k_value, vFiat),
                CardKpi(Res.string.pfc_f_stake_pct, stake?.let { CardValue.Pct(it) }),
                CardKpi(Res.string.pfc_k_company_value, stake?.let { CardValue.Fiat(value / (it / 100.0), ccy) }),
                CardKpi(Res.string.pfc_f_valuation_date, details.date("valuation_date")?.let { CardValue.Date(it) }),
            )
        }
        DetailCategory.LOAN_TO_OTHERS -> {
            val original = details.num("original_amount")?.takeIf { it > 0.0 }
            if (original != null) {
                val repaid = clamp01(1.0 - value / original)
                meter = CardMeter(Res.string.pfc_k_repaid, repaid, CardValue.Pct(repaid * 100.0))
            }
            listOf(
                CardKpi(Res.string.pfc_k_to_receive, vFiat),
                CardKpi(Res.string.pfc_f_original_amount, original?.let { CardValue.Fiat(it, ccy) }),
                CardKpi(Res.string.pfc_f_interest_pct, details.num("interest_pct")?.let { CardValue.Pct(it) }),
                CardKpi(Res.string.pfc_f_due_date, details.date("due_date")?.let { CardValue.Date(it) }),
            )
        }
        else -> listOf(
            CardKpi(Res.string.pfc_k_value, vFiat),
            purchaseKpi,
            revalKpi,
            CardKpi(Res.string.pfc_k_updated, CardValue.DaysAgo(updatedDays)),
        )
    }
    if (meter == null) {
        meter = row.pctOfNetWorth?.let { CardMeter(Res.string.pfc_k_weight, clamp01(it / 100.0), CardValue.Pct(it)) }
    }
    return DetailCardModel(category, kpis, meter, detailAttrs(category, details, ccy) + derived)
}

/**
 * Liability card. Amounts are in sat, like the liability itself;
 * [linkedAssetName] resolves `linked_asset_id` to the asset's name.
 */
internal fun liabilityCardModel(
    l: TrackingLiabilityFfi,
    details: Map<String, String>,
    nowUnix: Long,
    fiatCode: String,
    linkedAssetName: (String) -> String? = { null },
): DetailCardModel {
    val category = DetailCategory.ofLiability(l.kind)
    val balance = l.amountSat.toLong()
    val today = epochDay(nowUnix)
    var meter: CardMeter? = null
    val kpis: List<CardKpi> = when (category) {
        DetailCategory.MORTGAGE, DetailCategory.LOAN -> {
            val original = details["original_amount_sat"]?.toLongOrNull()?.takeIf { it > 0L }
            if (original != null) {
                val repaid = clamp01(1.0 - balance.toDouble() / original)
                meter = CardMeter(Res.string.pfc_k_repaid, repaid, CardValue.Pct(repaid * 100.0))
            }
            val end = details.date("end_date")
            val monthsLeft = end?.let { ChronoUnit.MONTHS.between(today, it).coerceAtLeast(0L) }
            listOf(
                CardKpi(Res.string.pfc_k_remaining, CardValue.Sat(balance), Tone.NEGATIVE),
                CardKpi(Res.string.pfc_f_monthly_payment, details.num("monthly_payment")?.let { CardValue.Fiat(it, fiatCode) }),
                CardKpi(Res.string.pfc_f_rate_pct, details.num("rate_pct")?.let { CardValue.Pct(it) }),
                CardKpi(Res.string.pfc_k_months_left, monthsLeft?.let { CardValue.Months(it) }),
            )
        }
        DetailCategory.CREDIT_CARD -> {
            val limit = details["credit_limit_sat"]?.toLongOrNull()?.takeIf { it > 0L }
            val use = limit?.let { balance.toDouble() / it }
            if (use != null) meter = CardMeter(Res.string.pfc_k_utilization, clamp01(use), CardValue.Pct(use * 100.0))
            val day = details["statement_day"]?.toIntOrNull()?.takeIf { it in 1..31 }
            val nextStatement = day?.let { daysUntilDayOfMonth(today, it) }
            listOf(
                CardKpi(Res.string.pfc_k_balance, CardValue.Sat(balance), Tone.NEGATIVE),
                CardKpi(Res.string.pfc_f_credit_limit_sat, limit?.let { CardValue.Sat(it) }),
                CardKpi(Res.string.pfc_k_utilization, use?.let { CardValue.Pct(it * 100.0) }, if ((use ?: 0.0) > 0.3) Tone.NEGATIVE else Tone.NEUTRAL),
                CardKpi(Res.string.pfc_k_next_statement, nextStatement?.let { CardValue.InDays(it) }),
            )
        }
        else -> listOf(
            CardKpi(Res.string.pfc_k_balance, CardValue.Sat(balance), Tone.NEGATIVE),
            CardKpi(Res.string.pfc_k_updated, CardValue.DaysAgo(ChronoUnit.DAYS.between(epochDay(l.createdAtUnix), today).coerceAtLeast(0L))),
            CardKpi(Res.string.pfc_f_rate_pct, details.num("rate_pct")?.let { CardValue.Pct(it) }),
            CardKpi(Res.string.pfc_f_end_date, details.date("end_date")?.let { CardValue.Date(it) }),
        )
    }
    return DetailCardModel(category, kpis, meter, detailAttrs(category, details, fiatCode, linkedAssetName))
}

/** Days from [today] to the next [day] of a month (clamped to the month's length). */
internal fun daysUntilDayOfMonth(today: LocalDate, day: Int): Long {
    fun inMonth(d: LocalDate): LocalDate = d.withDayOfMonth(day.coerceAtMost(d.lengthOfMonth()))
    val thisMonth = inMonth(today)
    val next = if (!thisMonth.isBefore(today)) thisMonth else inMonth(today.plusMonths(1).withDayOfMonth(1))
    return ChronoUnit.DAYS.between(today, next)
}

/** Sum, in [currency], of the liabilities linked to manual asset [assetId]. */
internal fun linkedDebtFiat(
    assetId: Long,
    liabilities: List<TrackingLiabilityFfi>,
    details: PortfolioDetails,
    satToFiat: (Long) -> Double?,
): Double? {
    val linked = liabilities.filter { details.liabilities[it.id]?.get("linked_asset_id") == assetId.toString() }
    if (linked.isEmpty()) return null
    return linked.sumOf { satToFiat(it.amountSat.toLong()) ?: return null }
}

/** Today in UTC, as the card builders expect it. */
internal fun nowUnixUtc(): Long = java.time.Instant.ofEpochMilli(AppClock.nowMillis()).atZone(ZoneOffset.UTC).toEpochSecond()

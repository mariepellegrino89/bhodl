package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.TableColumn
import io.bhodl.desktop.ui.TableColumnLayout

/*
 * UX review 2026-10 (item 17) — the columns of every dense table, in
 * display order, with the width each needs, its share of the free width
 * ([TableColumn.weight], the proportions the tables always had) and its
 * hide rank (higher hides first, 0 never hides).
 *
 * Chrome = row padding (2 × 12dp) + panel border (2 × 1dp) + any fixed
 * slot that is a row action rather than data (checkbox, Show, Freeze,
 * move buttons, the lots caret). Those slots never hide.
 */

/** Addresses tab: the "Show" link (64dp) is a fixed action slot. */
internal enum class AddressCol(
    override val weight: Float?,
    override val minWidthDp: Int,
    override val hideRank: Int,
) : TableColumn {
    INDEX(null, 48, 3),
    ADDRESS(3.2f, 150, 0),
    LABEL(2f, 110, 2),
    // Used / unused — the address-reuse signal outlives the label.
    STATUS(0.9f, 72, 1),
}

internal val ADDRESS_TABLE = TableColumnLayout("wallet-addresses", AddressCol.entries, chromeDp = 26 + 64)

/** UTXOs tab: the checkbox (40dp) and Freeze link (96dp) are fixed slots. */
internal enum class UtxoCol(
    override val weight: Float?,
    override val minWidthDp: Int,
    override val hideRank: Int,
) : TableColumn {
    OUTPOINT(1.4f, 110, 2),
    ADDRESS(2.6f, 140, 3),
    TYPE(0.8f, 64, 4),
    // Confirmed / unconfirmed / frozen.
    STATUS(1.1f, 80, 1),
    AMOUNT(1.3f, 110, 0),
}

internal val UTXO_TABLE = TableColumnLayout("wallet-utxos", UtxoCol.entries, chromeDp = 26 + 40 + 96)

/** Home wallet list. */
internal enum class WalletListCol(
    override val weight: Float?,
    override val minWidthDp: Int,
    override val hideRank: Int,
) : TableColumn {
    NAME(2f, 140, 0),
    TYPE(1f, 100, 2),
    NETWORK(1f, 72, 3),
    BALANCE(1.4f, 110, 0),
}

internal val WALLET_LIST_TABLE = TableColumnLayout("wallet-list", WalletListCol.entries, chromeDp = 26)

/** Investments card (Portfolio): the lots caret (~18dp) sits before the name. */
internal enum class InvestmentCol(
    override val weight: Float?,
    override val minWidthDp: Int,
    override val hideRank: Int,
) : TableColumn {
    NAME(2f, 130, 0),
    TYPE(0.7f, 72, 5),
    QTY(0.7f, 72, 4),
    COST(1.2f, 110, 3),
    VALUE(1.2f, 100, 0),
    PNL_FIAT(0.7f, 64, 2),
    // Bitcoin Lens: the BTC P&L is the headline, fiat P&L gives way first.
    PNL_BTC(0.8f, 80, 1),
}

internal val INVESTMENT_TABLE = TableColumnLayout("investments", InvestmentCol.entries, chromeDp = 26 + 18)

/** Manual assets card (Portfolio). */
internal enum class ManualAssetCol(
    override val weight: Float?,
    override val minWidthDp: Int,
    override val hideRank: Int,
) : TableColumn {
    NAME(1.8f, 130, 0),
    TYPE(0.8f, 80, 3),
    VALUE_NOW(1.2f, 100, 0),
    VALUE_AT_BUY(1.2f, 110, 2),
    DELTA_BTC(0.8f, 70, 1),
}

internal val MANUAL_ASSET_TABLE = TableColumnLayout("manual-assets", ManualAssetCol.entries, chromeDp = 26)

/** Rules card: the move up / down buttons (2 × 28dp) are fixed slots. */
internal enum class RuleCol(
    override val weight: Float?,
    override val minWidthDp: Int,
    override val hideRank: Int,
) : TableColumn {
    // Evaluation position; the list order already tells it.
    ORDER(null, 28, 1),
    // Name over condition.
    RULE(2f, 150, 0),
    CATEGORY(1.2f, 90, 0),
    // On / off in words (the dimmed colour alone is not enough).
    ENABLED(0.4f, 36, 0),
}

internal val RULE_TABLE = TableColumnLayout("rules", RuleCol.entries, chromeDp = 26 + 56)

/** Budget breakdown card (Cash flow): 16dp card padding each side, 8dp gaps. */
internal enum class BudgetCol(
    override val weight: Float?,
    override val minWidthDp: Int,
    override val hideRank: Int,
) : TableColumn {
    SOURCE(2f, 100, 0),
    // The budget cell is also the "Set / edit budget" control: never hides.
    BUDGET(1.4f, 90, 0),
    ACTUAL(1.4f, 90, 0),
    PROGRESS(2f, 80, 4),
    STATUS(1.4f, 80, 1),
    VARIANCE(1.2f, 72, 2),
}

internal val BUDGET_TABLE = TableColumnLayout("budget", BudgetCol.entries, chromeDp = 34, gapDp = 8)

/** Net-worth breakdown table. */
internal enum class NetWorthBreakdownCol(
    override val weight: Float?,
    override val minWidthDp: Int,
    override val hideRank: Int,
) : TableColumn {
    CATEGORY(2f, 140, 0),
    VALUE(1f, 120, 0),
    SHARE(1f, 64, 1),
}

internal val NET_WORTH_BREAKDOWN_TABLE =
    TableColumnLayout("net-worth-breakdown", NetWorthBreakdownCol.entries, chromeDp = 26)

/**
 * Every dense table that hides columns through a [TableColumnLayout],
 * by its stable id. The Portfolio sheet and the transactions table keep
 * their own helpers (`visibleSheetColumns`, `visibleTxColumns`).
 */
internal object TableColumnPriorities {
    val ALL: Map<String, TableColumnLayout<out TableColumn>> = listOf(
        ADDRESS_TABLE,
        UTXO_TABLE,
        WALLET_LIST_TABLE,
        INVESTMENT_TABLE,
        MANUAL_ASSET_TABLE,
        RULE_TABLE,
        BUDGET_TABLE,
        NET_WORTH_BREAKDOWN_TABLE,
    ).associateBy { it.id }
}

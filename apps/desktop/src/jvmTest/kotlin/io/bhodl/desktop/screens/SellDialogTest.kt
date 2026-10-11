package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.ffi.LotSelectionFfi
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SellDialog — Specific-ID lot picker vs the plain quantity field.
 */
@OptIn(ExperimentalTestApi::class)
class SellDialogTest {

    private data class Saved(val qty: Double, val selection: List<LotSelectionFfi>?)

    private fun inv(method: String) = TrackingInvestmentFfi(
        id = 1,
        name = "ACME",
        assetType = "stock",
        ticker = "ACME",
        quantity = 0.0,
        unit = "",
        costBasisFiat = 0.0,
        costBasisCurrency = "EUR",
        purchaseUnix = 0,
        manualCurrentValue = null,
        fetchedCurrentValue = null,
        lastPricedAtUnix = null,
        notes = null,
        costBasisMethod = method,
        createdAtUnix = 0,
    )

    private fun lot(id: Long, qty: Double, price: Double) = TrackingInvestmentLotFfi(
        id = id,
        investmentId = 1,
        quantity = qty,
        pricePerUnit = price,
        feesFiat = 0.0,
        purchaseUnix = 1_000L * id,
        notes = null,
        createdAtUnix = 0,
    )

    private val lots = listOf(lot(1, 10.0, 100.0), lot(2, 5.0, 200.0))

    @Test
    fun `specific-ID shows a lot picker and refuses over-picks`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    SellDialog(
                        parentInvestment = inv("specific_id"),
                        parentLots = lots,
                        parentCurrencySymbol = "€",
                        saleBtcRateAt = { _, _ -> null },
                        onDismiss = {},
                        onSave = { _, _, _, _, _, _ -> },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SellDialogTags.lotQty(1)))
            // No free quantity field for Specific-ID.
            waitUntilDoesNotExist(hasTestTag(SellDialogTags.INPUT_QTY))
            onNodeWithTag(SellDialogTags.INPUT_PRICE).performTextInput("250")
            onNodeWithTag(SellDialogTags.SAVE).assertIsNotEnabled()

            // Over-pick on lot 2 → inline error, Save stays disabled.
            onNodeWithTag(SellDialogTags.lotQty(2)).performTextInput("6")
            // The panel merges its descendants: look the status line up unmerged.
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithTag(SellDialogTags.SELECTION_ERROR, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(SellDialogTags.SAVE).assertIsNotEnabled()
        }

    @Test
    fun `specific-ID valid picks enable Save and pass the selection`() =
        runDesktopComposeUiTest {
            var saved: Saved? = null
            setContent {
                BhodlTheme {
                    SellDialog(
                        parentInvestment = inv("specific_id"),
                        parentLots = lots,
                        parentCurrencySymbol = "€",
                        saleBtcRateAt = { _, _ -> null },
                        onDismiss = {},
                        onSave = { _, qty, _, _, _, selection -> saved = Saved(qty, selection) },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SellDialogTags.lotQty(2)))
            onNodeWithTag(SellDialogTags.INPUT_PRICE).performTextInput("250")
            onNodeWithTag(SellDialogTags.lotQty(2)).performTextInput("4")
            onNodeWithTag(SellDialogTags.lotQty(1)).performTextInput("1.5")
            // The panel merges its descendants: look the status line up unmerged.
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithTag(SellDialogTags.SELECTION_TOTAL, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(SellDialogTags.SAVE).assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 2_000) { saved != null }
            val s = saved!!
            assertEquals(5.5, s.qty, 1e-12)
            assertEquals(
                setOf(LotSelectionFfi(lotId = 2, qty = 4.0), LotSelectionFfi(lotId = 1, qty = 1.5)),
                s.selection!!.toSet(),
            )
        }

    @Test
    fun `FIFO keeps the quantity field and passes no selection`() = runDesktopComposeUiTest {
        var saved: Saved? = null
        setContent {
            BhodlTheme {
                SellDialog(
                    parentInvestment = inv("fifo"),
                    parentLots = lots,
                    parentCurrencySymbol = "€",
                    saleBtcRateAt = { _, _ -> null },
                    onDismiss = {},
                    onSave = { _, qty, _, _, _, selection -> saved = Saved(qty, selection) },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SellDialogTags.INPUT_QTY))
        waitUntilDoesNotExist(hasTestTag(SellDialogTags.lotQty(1)))
        onNodeWithTag(SellDialogTags.INPUT_QTY).performTextInput("3")
        onNodeWithTag(SellDialogTags.INPUT_PRICE).performTextInput("250")
        onNodeWithTag(SellDialogTags.SAVE).assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(3.0, saved!!.qty, 1e-12)
        assertNull(saved!!.selection)
    }

    @Test
    fun `comparison shows the three methods and picking HIFO books its lots`() =
        runDesktopComposeUiTest {
            var saved: Saved? = null
            setContent {
                BhodlTheme {
                    SellDialog(
                        parentInvestment = inv("fifo"),
                        parentLots = lots,
                        parentCurrencySymbol = "€",
                        saleBtcRateAt = { _, _ -> 50_000.0 },
                        onDismiss = {},
                        onSave = { _, qty, _, _, _, selection -> saved = Saved(qty, selection) },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SellDialogTags.INPUT_QTY))
            onNodeWithTag(SellDialogTags.INPUT_QTY).performTextInput("3")
            onNodeWithTag(SellDialogTags.INPUT_PRICE).performTextInput("250")
            // FIFO consumes lot 1 (oldest), HIFO lot 2 (highest cost).
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithTag(
                    SellDialogTags.consumed(CostBasisMethod.FIFO, 1), useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodesWithTag(
                        SellDialogTags.consumed(CostBasisMethod.HIFO, 2), useUnmergedTree = true,
                    ).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(SellDialogTags.method(CostBasisMethod.HIFO), useUnmergedTree = true)
                .performClick()
            waitUntil(timeoutMillis = 2_000) {
                onAllNodesWithTag(SellDialogTags.OVERRIDE_NOTE, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(SellDialogTags.SAVE).assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 2_000) { saved != null }
            assertEquals(3.0, saved!!.qty, 1e-12)
            assertEquals(listOf(LotSelectionFfi(lotId = 2, qty = 3.0)), saved!!.selection)
        }
}

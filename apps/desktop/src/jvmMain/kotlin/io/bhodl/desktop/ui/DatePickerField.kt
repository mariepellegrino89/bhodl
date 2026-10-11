package io.bhodl.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_ok
import io.bhodl.shared.resources.wview_select_date
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 9.2 — calendar-backed date field, replacing the free-text
 * `YYYY-MM-DD` inputs (sinking-goal deadline, lot purchase date,
 * sell date). Every finance-tracker peer uses a picker here; the
 * forgiving-parser text field was the most visible affordance gap.
 *
 * Renders as a read-only `OutlinedTextField` (so it sits naturally
 * among the other form fields) with a full-size click target that
 * opens the Material 3 [DatePickerDialog]. Dates are exchanged as
 * epoch SECONDS (the registry convention); the picker works in UTC
 * day granularity, matching the previous parser's behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun DatePickerField(
    label: String,
    epochSeconds: Long?,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    testTagId: String? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    val formatted = epochSeconds?.let {
        DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate(),
        )
    } ?: ""

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = formatted,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(stringResource(Res.string.wview_select_date)) },
            trailingIcon = { Text("📅") },
            modifier = Modifier
                .fillMaxWidth()
                .let { if (testTagId != null) it.testTag(testTagId) else it },
        )
        // Transparent full-size click target — a readOnly text field
        // swallows clicks into its (useless) cursor handling, so the
        // overlay is what actually opens the picker.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { showPicker = true },
        )
    }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = epochSeconds?.times(1000)
                ?: LocalDate.now(ZoneOffset.UTC)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onPick(it / 1000L) }
                        showPicker = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) { Text(stringResource(Res.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(Res.string.common_cancel)) }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

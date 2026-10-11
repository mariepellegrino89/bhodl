package io.bhodl.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.ffi.ContactRecord
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.wsend_picker_empty
import io.bhodl.shared.resources.wsend_picker_no_match
import io.bhodl.shared.resources.wsend_picker_search
import io.bhodl.shared.resources.wsend_picker_title

/**
 * Modal dialog that lets the user pick a contact from the local
 * address book. Surfaces the same `ContactRecord` data the
 * [`io.bhodl.desktop.screens.AddressBookScreen`] does, plus a
 * client-side filter that matches both the contact label and the
 * address itself (substring, case-insensitive).
 *
 * Mounted by callers via:
 * ```
 * if (showPicker) {
 *     ContactPickerDialog(
 *         contacts = contacts,
 *         onSelected = { ... },
 *         onDismiss = { showPicker = false },
 *     )
 * }
 * ```
 *
 * The dialog itself owns no fetching — callers load the contacts
 * (typically from `wallet.listContacts()`) before opening it. That
 * keeps the dialog purely presentational and re-usable for tests
 * without a wallet handle.
 */
@Composable
public fun ContactPickerDialog(
    contacts: List<ContactRecord>,
    onSelected: (ContactRecord) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(contacts, query) {
        if (query.isBlank()) {
            contacts
        } else {
            // Label-only match. We deliberately do NOT match on the
            // address body: bech32m payloads are random-looking
            // alphanumerics, so a substring search there produces
            // spurious matches that confuse the user (typing "abc"
            // matched whatever contact happened to have "abc" in the
            // middle of its address). Users name contacts to find
            // them — that's the affordance we surface.
            val needle = query.trim().lowercase()
            contacts.filter { it.label.lowercase().contains(needle) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(ContactPickerTags.ROOT),
        title = {
            Text(
                text = stringResource(Res.string.wsend_picker_title),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(Res.string.wsend_picker_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ContactPickerTags.SEARCH),
                )

                when {
                    contacts.isEmpty() -> Text(
                        text = stringResource(Res.string.wsend_picker_empty),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ContactPickerTags.EMPTY),
                    )

                    filtered.isEmpty() -> Text(
                        text = stringResource(Res.string.wsend_picker_no_match, query),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ContactPickerTags.NO_MATCH),
                    )

                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            // Cap the dialog height so a long contact
                            // list doesn't push the buttons off-screen
                            // on narrow windows.
                            .heightIn(max = 320.dp)
                            .testTag(ContactPickerTags.LIST),
                    ) {
                        items(items = filtered, key = { it.id }) { contact ->
                            ContactPickerRow(
                                contact = contact,
                                onClick = { onSelected(contact) },
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(ContactPickerTags.CANCEL),
            ) { Text(stringResource(Res.string.common_cancel)) }
        },
        // No "OK" button by design: selection happens by tapping a row,
        // so a primary action would be redundant. AlertDialog requires
        // a `confirmButton` slot to render, so we feed it an empty
        // composable.
        confirmButton = {},
    )
}

@Composable
private fun ContactPickerRow(
    contact: ContactRecord,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(ContactPickerTags.rowFor(contact.id)),
        colors = CardDefaults.cardColors(
            containerColor = BhodlTheme.colors.surface,
            contentColor = BhodlTheme.colors.textPrimary,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = contact.label,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                Text(
                    text = contact.address,
                    style = BhodlTheme.typography.mono,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
        }
    }
}

/** Test tags exposed by [`ContactPickerDialog`]. */
public object ContactPickerTags {
    public const val ROOT: String = "contact-picker.root"
    public const val SEARCH: String = "contact-picker.search"
    public const val LIST: String = "contact-picker.list"
    public const val EMPTY: String = "contact-picker.empty"
    public const val NO_MATCH: String = "contact-picker.no-match"
    public const val CANCEL: String = "contact-picker.cancel"
    public fun rowFor(id: Long): String = "contact-picker.row.$id"
}

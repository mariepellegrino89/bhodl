package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.ContactRecord
import io.bhodl.desktop.ui.userMessage
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.common_delete
import io.bhodl.shared.resources.common_loading
import io.bhodl.shared.resources.dashboard_button_address_book
import io.bhodl.shared.resources.wsend_address
import io.bhodl.shared.resources.wsend_book_add_failed
import io.bhodl.shared.resources.wsend_book_add_title
import io.bhodl.shared.resources.wsend_book_both_required
import io.bhodl.shared.resources.wsend_book_empty
import io.bhodl.shared.resources.wsend_book_label
import io.bhodl.shared.resources.wsend_book_save
import io.bhodl.shared.resources.wsend_load_failed
import io.bhodl.shared.resources.wsend_not_unlocked

/**
 * Local address book.
 *
 * Reads / writes the `bhodl_contacts` table inside the open wallet's
 * SQLCipher database via the FFI. The list is rendered in insertion
 * order; each row offers Delete. The form at the top is a simple
 * `(label, address)` capture; the core validates the address against
 * the wallet's network and stores it in canonical form.
 */
@Composable
public fun AddressBookScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state: ContactsState by remember { mutableStateOf(ContactsState.Loading) }
    var newLabel by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }
    var formError: String? by remember { mutableStateOf(null) }
    // Resolved up front: reload() and the save callback are not composable.
    val notUnlocked = stringResource(Res.string.wsend_not_unlocked)
    val loadFailed = stringResource(Res.string.wsend_load_failed)
    val addFailed = stringResource(Res.string.wsend_book_add_failed)
    val bothRequired = stringResource(Res.string.wsend_book_both_required)

    suspend fun reload() {
        val wallet = WalletStore.current()
        if (wallet == null) {
            state = ContactsState.Failed(notUnlocked)
            return
        }
        state = runCatching {
            withContext(Dispatchers.IO) { wallet.listContacts() }
        }.fold(
            onSuccess = { ContactsState.Ready(it) },
            onFailure = { e ->
                val msg = e.userMessage(loadFailed)
                ContactsState.Failed(msg)
            },
        )
    }

    LaunchedEffect(Unit) { reload() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 720.dp)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BhodlTopBar(title = stringResource(Res.string.dashboard_button_address_book), onBack = onBack)

            // ---- Add form ----------------------------------------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = BhodlTheme.colors.surface,
                    contentColor = BhodlTheme.colors.textPrimary,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.wsend_book_add_title),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text(stringResource(Res.string.wsend_book_label)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AddressBookTags.LABEL),
                    )
                    OutlinedTextField(
                        value = newAddress,
                        onValueChange = { newAddress = it.trim() },
                        label = { Text(stringResource(Res.string.wsend_address)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AddressBookTags.ADDRESS),
                    )
                    if (formError != null) {
                        Text(
                            text = formError!!,
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.textSecondary,
                        )
                    }
                    Button(
                        onClick = {
                            val label = newLabel.trim()
                            val address = newAddress.trim()
                            if (label.isEmpty() || address.isEmpty()) {
                                formError = bothRequired
                                return@Button
                            }
                            formError = null
                            scope.launch {
                                val wallet = WalletStore.current() ?: return@launch
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { wallet.addContact(label, address) }
                                }
                                result.onSuccess {
                                    newLabel = ""
                                    newAddress = ""
                                    reload()
                                }.onFailure { e ->
                                    val msg = e.userMessage(addFailed)
                                    formError = msg
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AddressBookTags.SAVE),
                    ) { Text(stringResource(Res.string.wsend_book_save)) }
                }
            }

            // ---- List --------------------------------------------------
            when (val s = state) {
                ContactsState.Loading -> Text(
                    text = stringResource(Res.string.common_loading),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                is ContactsState.Failed -> Text(
                    text = s.reason,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                is ContactsState.Ready -> if (s.contacts.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.wsend_book_empty),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                } else {
                    s.contacts.forEach { contact ->
                        ContactCard(
                            contact = contact,
                            onDelete = {
                                scope.launch {
                                    val wallet = WalletStore.current() ?: return@launch
                                    runCatching {
                                        withContext(Dispatchers.IO) { wallet.removeContact(contact.id) }
                                    }
                                    reload()
                                }
                            },
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.action_back)) }
        }
    }
}

@Composable
private fun ContactCard(
    contact: ContactRecord,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = BhodlTheme.colors.surface,
            contentColor = BhodlTheme.colors.textPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.label,
                    style = BhodlTheme.typography.titleMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                // Selectable so the user can drag-select + Ctrl+C the
                // address straight out of the list.
                SelectionContainer {
                    Text(
                        text = contact.address,
                        style = BhodlTheme.typography.mono,
                        color = BhodlTheme.colors.textSecondary,
                    )
                }
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag(AddressBookTags.deleteFor(contact.id)),
            ) { Text(stringResource(Res.string.common_delete)) }
        }
    }
}

/** Test tags exposed by [`AddressBookScreen`]. */
public object AddressBookTags {
    public const val LABEL: String = "address-book.label"
    public const val ADDRESS: String = "address-book.address"
    public const val SAVE: String = "address-book.save"
    public fun deleteFor(id: Long): String = "address-book.delete.$id"
}

private sealed class ContactsState {
    data object Loading : ContactsState()
    data class Ready(val contacts: List<ContactRecord>) : ContactsState()
    data class Failed(val reason: String) : ContactsState()
}

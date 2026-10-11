package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.BhodlBackArrow
import io.bhodl.desktop.ui.NetworkSelector
import io.bhodl.desktop.wallet.UniffiWalletFactory
import io.bhodl.desktop.wallet.WalletFactory
import io.bhodl.desktop.wizard.Bip39Suggester
import io.bhodl.desktop.wizard.Bip39Wordlist
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.onb_continue
import io.bhodl.shared.resources.onb_restore_body
import io.bhodl.shared.resources.onb_restore_invalid
import io.bhodl.shared.resources.onb_restore_phrase_label
import io.bhodl.shared.resources.onb_restore_title
import io.bhodl.shared.resources.onb_restore_words_empty
import io.bhodl.shared.resources.onb_restore_words_many
import io.bhodl.shared.resources.onb_restore_words_one
import io.bhodl.shared.resources.onb_restore_wrong_count
import io.bhodl.shared.resources.onb_validating
import org.jetbrains.compose.resources.stringResource

/**
 * Restore an existing wallet from its 12 / 15 / 18 / 21 / 24 BIP39
 * recovery words.
 *
 * Single multi-line text field instead of one input per word: pasting
 * the whole phrase from a paper backup or password manager is the
 * primary flow on desktop, and a single field handles arbitrary
 * whitespace (newlines, tabs, double spaces) without us having to
 * teach the user about visual word boundaries.
 *
 * The screen does NOT touch SQLCipher — it only validates the phrase
 * by trying to build an in-memory `WalletHandle.fromMnemonic`. The
 * persistent wallet is created in the next step (`PinSetup`), which
 * also writes the mnemonic into the encrypted DB so future unlocks
 * can rebuild the secret descriptors without asking again.
 */
@Composable
public fun RestoreMnemonicScreen(
    onValidated: (mnemonic: String, network: Network) -> Unit,
    onBack: () -> Unit,
    factory: WalletFactory = UniffiWalletFactory(),
) {
    val scope = rememberCoroutineScope()

    var field by remember { mutableStateOf(TextFieldValue("")) }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }
    var wordlist by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedNetwork by remember { mutableStateOf(Network.SIGNET) }

    // Load the BIP-39 English wordlist once on first composition. The
    // file lives under shared/composeResources/files/ and is ~13 KB —
    // the read is one-shot and cached inside Bip39Wordlist.
    LaunchedEffect(Unit) {
        wordlist = Bip39Wordlist.load()
    }

    val raw by remember(field) { derivedStateOf { field.text } }
    val suggestions by remember(field, wordlist) {
        derivedStateOf {
            val current = Bip39Suggester.currentWord(field.text)?.word ?: return@derivedStateOf emptyList()
            Bip39Suggester.suggestionsFor(current, wordlist)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        BhodlBackArrow(
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 480.dp)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.onb_restore_title),
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_restore_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            NetworkSelector(
                selected = selectedNetwork,
                onSelected = { selectedNetwork = it },
                enabled = !working,
            )

            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                label = { Text(stringResource(Res.string.onb_restore_phrase_label)) },
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .testTag(RestoreMnemonicTags.PHRASE),
            )

            // Inline BIP-39 autocomplete: while the user is mid-typing
            // a word, surface up to 8 matches as tappable chips. Tap
            // → replace the in-flight word + append a trailing space
            // so the next word's suggestions appear right away.
            // Empty list (blank prefix or word complete after a space)
            // hides the row entirely.
            if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag(RestoreMnemonicTags.SUGGESTIONS),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestions.forEach { suggestion ->
                        SuggestionChip(
                            label = suggestion,
                            onClick = {
                                val replaced = Bip39Suggester.replaceCurrentWord(field.text, suggestion)
                                field = TextFieldValue(
                                    text = replaced,
                                    selection = TextRange(replaced.length),
                                )
                            },
                        )
                    }
                }
            }

            val wordCount = remember(raw) {
                raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            }
            val countText = if (raw.isBlank()) {
                stringResource(Res.string.onb_restore_words_empty)
            } else if (wordCount == 1) {
                stringResource(Res.string.onb_restore_words_one)
            } else {
                stringResource(Res.string.onb_restore_words_many, wordCount)
            }
            val invalidPhrase = stringResource(Res.string.onb_restore_invalid)
            Text(
                text = countText,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )

            if (lastError != null) {
                Text(
                    text = lastError!!,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = onClick@{
                    if (working) return@onClick
                    val normalized = normalizePhrase(raw)
                    val count = normalized.split(' ').size
                    if (count !in BIP39_VALID_COUNTS) {
                        lastError = localizedText(Res.string.onb_restore_wrong_count, count)
                        return@onClick
                    }
                    working = true
                    lastError = null
                    scope.launch {
                        // Round-trip a throwaway in-memory wallet to validate
                        // the BIP39 checksum before sending the user into the
                        // PIN-setup flow. Cheaper than letting the
                        // SQLCipher-backed createOrLoadPersistent fail later
                        // with a less actionable error.
                        val outcome = runCatching {
                            withContext(Dispatchers.IO) {
                                factory.validateMnemonic(
                                    network = selectedNetwork,
                                    mnemonic = normalized,
                                    passphrase = "",
                                )
                            }
                        }
                        working = false
                        outcome.fold(
                            onSuccess = { onValidated(normalized, selectedNetwork) },
                            onFailure = { e ->
                                val msg = e.userMessage(invalidPhrase)
                                lastError = msg
                            },
                        )
                    }
                },
                enabled = !working && raw.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RestoreMnemonicTags.CONTINUE),
            ) {
                Text(
                    if (working) stringResource(Res.string.onb_validating)
                    else stringResource(Res.string.onb_continue),
                )
            }

            OutlinedButton(
                onClick = onBack,
                enabled = !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RestoreMnemonicTags.BACK),
            ) { Text(stringResource(Res.string.action_back)) }
        }
    }
}

/**
 * Normalise the user input the way `bip39::Mnemonic::parse_normalized`
 * expects it: strip leading/trailing whitespace, lowercase ASCII, and
 * collapse arbitrary internal whitespace into single spaces.
 */
private fun normalizePhrase(raw: String): String {
    return raw.trim()
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

private val BIP39_VALID_COUNTS = setOf(12, 15, 18, 21, 24)

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f))
            .border(
                width = 1.dp,
                color = BhodlTheme.colors.brandPrimary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(RestoreMnemonicTags.suggestionChip(label)),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

/** Test tags exposed by [`RestoreMnemonicScreen`]. */
public object RestoreMnemonicTags {
    public const val PHRASE: String = "restore.phrase"
    public const val CONTINUE: String = "restore.continue"
    public const val BACK: String = "restore.back"
    public const val SUGGESTIONS: String = "restore.suggestions"
    public fun suggestionChip(word: String): String = "restore.suggestion.$word"
}

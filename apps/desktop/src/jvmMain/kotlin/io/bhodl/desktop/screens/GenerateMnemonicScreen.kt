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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.BhodlBackArrow
import io.bhodl.desktop.ui.NetworkSelector
import io.bhodl.desktop.wallet.UniffiWalletFactory
import io.bhodl.desktop.wallet.WalletFactory
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.onb_gen_body
import io.bhodl.shared.resources.onb_gen_failed
import io.bhodl.shared.resources.onb_gen_title
import io.bhodl.shared.resources.onb_gen_written
import io.bhodl.shared.resources.onb_generating
import io.bhodl.shared.resources.onb_unknown_error
import org.jetbrains.compose.resources.stringResource

/**
 * Generated 12-word mnemonic phrase, post-derivation.
 *
 * The screen calls [`WalletHandle.generateRandomMnemonic`] once on first
 * composition (via [`LaunchedEffect`] so the call is bound to the
 * coroutine scope of the composition) and renders the resulting words
 * in a grid. The user is expected to write them down on paper before
 * tapping the primary CTA.
 *
 * Mnemonic generation is delegated to [`WalletFactory.generateMnemonic`]
 * (R7): the uniffi ABI workaround that used to be inlined here now lives
 * in `UniffiWalletFactory`, so this screen no longer carries FFI-ABI
 * knowledge. Tests inject a `FakeWalletFactory`.
 *
 * State is hoisted as a sealed class so the compiler enforces handling
 * every loading / failed / ready case.
 */
@Composable
public fun GenerateMnemonicScreen(
    onConfirmed: (mnemonic: String, network: Network) -> Unit,
    onBack: () -> Unit,
    factory: WalletFactory = UniffiWalletFactory(),
) {
    var state: MnemonicLoadState by remember { mutableStateOf(MnemonicLoadState.Loading) }
    var selectedNetwork by remember { mutableStateOf(Network.SIGNET) }

    LaunchedEffect(Unit) {
        state = runCatching {
            withContext(Dispatchers.IO) {
                factory.generateMnemonic(WORD_COUNT)
            }
        }.fold(
            onSuccess = { MnemonicLoadState.Ready(it) },
            onFailure = { e ->
                val msg = e.userMessage(localizedText(Res.string.onb_unknown_error))
                MnemonicLoadState.Failed(msg)
            },
        )
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "BHODL",
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_gen_title),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_gen_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            NetworkSelector(
                selected = selectedNetwork,
                onSelected = { selectedNetwork = it },
            )

            when (val s = state) {
                is MnemonicLoadState.Loading -> Text(
                    text = stringResource(Res.string.onb_generating),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                is MnemonicLoadState.Failed -> Text(
                    text = stringResource(Res.string.onb_gen_failed, s.reason),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                is MnemonicLoadState.Ready -> MnemonicGrid(words = s.mnemonic.split(" "))
            }

            Button(
                onClick = {
                    val ready = state as? MnemonicLoadState.Ready ?: return@Button
                    onConfirmed(ready.mnemonic, selectedNetwork)
                },
                enabled = state is MnemonicLoadState.Ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.onb_gen_written))
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.action_back))
            }
        }
    }
}

@Composable
private fun MnemonicGrid(words: List<String>) {
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
            // Render the words as rows of `COLUMNS_PER_ROW` columns each.
            words.chunked(COLUMNS_PER_ROW).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    row.forEachIndexed { colIndex, word ->
                        val index = rowIndex * COLUMNS_PER_ROW + colIndex + 1
                        Text(
                            text = "$index. $word",
                            style = BhodlTheme.typography.mono,
                            color = BhodlTheme.colors.textPrimary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed class MnemonicLoadState {
    data object Loading : MnemonicLoadState()
    data class Ready(val mnemonic: String) : MnemonicLoadState() {
        /** Audit 2026-10 — never print the seed (logs, crash dumps). */
        override fun toString(): String = "Ready(mnemonic=<redacted>)"
    }
    data class Failed(val reason: String) : MnemonicLoadState()
}

private const val WORD_COUNT: UInt = 12u
private const val COLUMNS_PER_ROW: Int = 3

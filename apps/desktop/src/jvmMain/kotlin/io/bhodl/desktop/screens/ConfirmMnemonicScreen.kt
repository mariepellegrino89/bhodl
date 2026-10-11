package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.BhodlBackArrow
import io.bhodl.desktop.wizard.MnemonicChallenge
import io.bhodl.desktop.wizard.VerificationResult
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.common_confirm
import io.bhodl.shared.resources.onb_confirm_body
import io.bhodl.shared.resources.onb_confirm_missing
import io.bhodl.shared.resources.onb_confirm_title
import io.bhodl.shared.resources.onb_confirm_word
import io.bhodl.shared.resources.onb_confirm_wrong
import org.jetbrains.compose.resources.stringResource

/**
 * Wizard step 3: ask the user to retype a few words from the mnemonic
 * shown in the previous screen. Backed by [`MnemonicChallenge`] so the
 * verification logic is decoupled from the UI and unit-tested.
 */
@Composable
public fun ConfirmMnemonicScreen(
    mnemonic: String,
    onConfirmed: (mnemonic: String) -> Unit,
    onBack: () -> Unit,
    // Test seam: production callers pass the default `null` and let the
    // screen build its own challenge; tests inject a deterministic one
    // to assert against known positions.
    challengeOverride: MnemonicChallenge? = null,
) {
    // Build the challenge once, the first time this screen composes.
    // Subsequent recompositions reuse the same selection so the user
    // does not see the asked positions shuffle while typing.
    val challenge = remember(mnemonic, challengeOverride) {
        challengeOverride ?: MnemonicChallenge.create(mnemonic)
    }
    val answers = remember { mutableStateMapOf<Int, String>() }
    var lastResult: VerificationResult? by remember { mutableStateOf(null) }

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
                text = "BHODL",
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_confirm_title),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_confirm_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            challenge.positions.forEach { pos ->
                OutlinedTextField(
                    value = answers[pos].orEmpty(),
                    onValueChange = { answers[pos] = it },
                    label = { Text(stringResource(Res.string.onb_confirm_word, pos)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ConfirmMnemonicTags.wordField(pos)),
                )
            }

            val errorText = when (val r = lastResult) {
                null, VerificationResult.AllCorrect -> null
                is VerificationResult.MissingAnswers -> stringResource(Res.string.onb_confirm_missing)
                is VerificationResult.Wrong -> {
                    val list = r.incorrectPositions.joinToString(", ") { "#$it" }
                    stringResource(Res.string.onb_confirm_wrong, list)
                }
            }
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = {
                    val result = challenge.verify(answers.toMap())
                    lastResult = result
                    if (result is VerificationResult.AllCorrect) {
                        onConfirmed(mnemonic)
                    }
                },
                enabled = answers.size == challenge.positions.size &&
                    answers.values.all { it.isNotBlank() },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConfirmMnemonicTags.CONFIRM),
            ) {
                Text(stringResource(Res.string.common_confirm))
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConfirmMnemonicTags.BACK),
            ) {
                Text(stringResource(Res.string.action_back))
            }
        }
    }
}

/** Test tags exposed by [`ConfirmMnemonicScreen`]. */
public object ConfirmMnemonicTags {
    public fun wordField(position: Int): String = "confirm-mnemonic.word.$position"
    public const val CONFIRM: String = "confirm-mnemonic.confirm"
    public const val BACK: String = "confirm-mnemonic.back"
}

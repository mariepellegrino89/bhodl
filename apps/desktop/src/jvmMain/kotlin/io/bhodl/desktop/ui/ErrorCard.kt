package io.bhodl.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_retry
import io.bhodl.shared.resources.wview_err_bc_conflict
import io.bhodl.shared.resources.wview_err_bc_in_chain
import io.bhodl.shared.resources.wview_err_bc_rejected
import io.bhodl.shared.resources.wview_err_bc_spent_cpfp
import io.bhodl.shared.resources.wview_err_bc_spent_rbf
import io.bhodl.shared.resources.wview_err_bc_spent_send
import io.bhodl.shared.resources.wview_err_net_refused
import io.bhodl.shared.resources.wview_err_net_timeout
import io.bhodl.shared.resources.wview_err_net_tls
import io.bhodl.shared.resources.wview_err_net_unreachable
import io.bhodl.shared.resources.wview_err_payjoin_rejected
import io.bhodl.shared.resources.wview_err_payjoin_transport
import io.bhodl.shared.resources.wview_err_unknown
import io.bhodl.shared.resources.wview_hide_details
import io.bhodl.shared.resources.wview_show_details
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Reusable error block for the desktop UI.
 *
 * Replaces the previous "single tomato-red Text under the Sync
 * button" pattern with a proper card that:
 * - Shows a short, user-readable [`title`] (e.g. "Sync failed").
 * - Shows a helpful [`message`] (e.g. the prettified network error).
 * - Optionally exposes an actionable [`onRetry`] hook with a button
 *   labelled by [`retryLabel`] (default "Retry").
 * - Optionally hides the raw exception detail behind a "Show details"
 *   toggle, so power users can dig in without polluting the default
 *   surface for everyone else.
 *
 * Visually a card with a 1dp brand-accent border so it stands out
 * from the surrounding screen without screaming "destructive".
 */
@Composable
public fun ErrorCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    details: String? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = stringResource(Res.string.common_retry),
) {
    var showDetails by remember(details) { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ErrorCardTags.ROOT),
        colors = CardDefaults.cardColors(
            containerColor = BhodlTheme.colors.surfaceVariant,
            contentColor = BhodlTheme.colors.textPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = BhodlTheme.colors.brandPrimary.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
                modifier = Modifier.testTag(ErrorCardTags.TITLE),
            )
            Text(
                text = message,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.testTag(ErrorCardTags.MESSAGE),
            )
            if (details != null) {
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.testTag(ErrorCardTags.DETAILS_TOGGLE),
                ) {
                    Text(stringResource(if (showDetails) Res.string.wview_hide_details else Res.string.wview_show_details))
                }
                if (showDetails) {
                    Text(
                        text = details,
                        style = BhodlTheme.typography.mono,
                        color = BhodlTheme.colors.textTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ErrorCardTags.DETAILS_TEXT),
                    )
                }
            }
            if (onRetry != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag(ErrorCardTags.RETRY),
                    ) { Text(retryLabel) }
                }
            }
        }
    }
}

/** Test tags exposed by [`ErrorCard`]. */
public object ErrorCardTags {
    public const val ROOT: String = "error-card.root"
    public const val TITLE: String = "error-card.title"
    public const val MESSAGE: String = "error-card.message"
    public const val DETAILS_TOGGLE: String = "error-card.details-toggle"
    public const val DETAILS_TEXT: String = "error-card.details-text"
    public const val RETRY: String = "error-card.retry"
}

/**
 * Map a raw exception message into a user-readable hint.
 *
 * The set of patterns is small on purpose — we only catch the cases
 * we have actually observed in the field. Anything we do not
 * recognise falls through to the raw text so the user is never
 * lied to. The raw text always lives in the optional details slot
 * of [`ErrorCard`] so even matched cases stay debuggable.
 */
public fun prettifyNetworkError(raw: String?): String {
    if (raw.isNullOrBlank()) return uiText(Res.string.wview_err_unknown)
    val lowered = raw.lowercase()
    val hint = when {
        "unknownhost" in lowered || "no address associated" in lowered ->
            Res.string.wview_err_net_unreachable
        "connection refused" in lowered || "connectexception" in lowered ->
            Res.string.wview_err_net_refused
        "timed out" in lowered || "sockettimeoutexception" in lowered ->
            Res.string.wview_err_net_timeout
        "ssl" in lowered || "certificate" in lowered ->
            Res.string.wview_err_net_tls
        else -> null
    }
    return hint?.let { uiText(it) } ?: raw
}

/**
 * UX review 2026-10 (rec 3) — resolve a hint in the app language
 * outside composition (the prettifiers run in click handlers and
 * coroutine callbacks). Compose resources cache the parsed files, so
 * after the first call this is a map lookup.
 */
private fun uiText(resource: StringResource): String = runBlocking { getString(resource) }

/**
 * Caller context passed into [`prettifyBroadcastError`] so the -25
 * (`bad-txns-inputs-missingorspent`) message can read naturally for
 * the specific flow the user is in. The same RPC error means
 * different things depending on intent:
 *
 * - [`Rbf`] — "the parent confirmed, fee bump pointless" (user
 *   wanted to *replace* a pending tx).
 * - [`Cpfp`] — "the parent confirmed, speed-up no longer needed"
 *   (user wanted to *push along* a pending tx by spending its
 *   outputs).
 * - [`Send`] — "your selected coins were spent elsewhere; try
 *   re-syncing" (user wanted to spend a UTXO that was consumed
 *   between Build and Broadcast).
 */
public enum class BroadcastContext {
    Rbf,
    Cpfp,
    Send,
}

/**
 * Map a raw `sendrawtransaction` RPC error (or any other broadcast
 * failure surfaced as [`io.bhodl.ffi.WalletException.Broadcast`])
 * into a user-readable hint.
 *
 * Catches the cases the wallet has actually observed in the field
 * (Bitcoin Core error codes -25 and -26 are by far the most common
 * after a state race against a mid-flow confirmation). Anything we
 * do not recognise returns `null` so the caller can fall back to the
 * raw text — the raw text always belongs in the [`ErrorCard.details`]
 * slot regardless of whether we prettify the message.
 *
 * `context` switches the -25 copy between RBF / CPFP / Send (see
 * [`BroadcastContext`]). Defaults to [`BroadcastContext.Rbf`] for
 * source compatibility with the original single-arg call sites.
 */
public fun prettifyBroadcastError(
    raw: String?,
    context: BroadcastContext = BroadcastContext.Rbf,
): String? {
    if (raw.isNullOrBlank()) return null
    val lowered = raw.lowercase()
    val hint = when {
        // Bitcoin Core -25: the input the wallet is trying to spend
        // is no longer in the UTXO set. The follow-up depends on
        // what the user was trying to do — the same RPC error reads
        // very differently for an RBF, CPFP, or Send race.
        "code\\\":-25" in lowered ||
            "code:-25" in lowered ||
            "bad-txns-inputs-missingorspent" in lowered ->
            when (context) {
                BroadcastContext.Rbf -> Res.string.wview_err_bc_spent_rbf
                BroadcastContext.Cpfp -> Res.string.wview_err_bc_spent_cpfp
                BroadcastContext.Send -> Res.string.wview_err_bc_spent_send
            }

        // Bitcoin Core -26: a mempool tx already spends the same UTXO
        // at >= the same feerate, so the relay refuses to swap it for
        // our new one (BIP-125 rule 4). Either the user is sending
        // again from the same UTXO without a higher fee, or a "ghost"
        // tx the wallet did not surface is occupying the slot.
        "code\\\":-26" in lowered ||
            "code:-26" in lowered ||
            "insufficient fee, rejecting replacement" in lowered ->
            Res.string.wview_err_bc_conflict

        // Bitcoin Core -27: tx already in chain. Friendlier than the
        // raw "transaction already in block chain" string.
        "code\\\":-27" in lowered ||
            "code:-27" in lowered ||
            "transaction already in block chain" in lowered ->
            Res.string.wview_err_bc_in_chain

        // Generic 4xx from the Esplora endpoint — wallet built and
        // signed locally, the network just refused. Could be many
        // reasons (low fee, non-standard, dust, …); surface a
        // friendlier fallback that points the user at next steps.
        "httpresponse { status: 4" in lowered ||
            "sendrawtransaction rpc error" in lowered ->
            Res.string.wview_err_bc_rejected

        // PayJoin transport failures: ureq did not even get a response
        // (DNS lookup failed, TCP refused, TLS handshake aborted, …).
        // The wallet never signed or broadcast anything; the user is
        // safe to retry once the network situation clears.
        "payjoin transport" in lowered ->
            Res.string.wview_err_payjoin_transport

        // PayJoin receiver returned a non-2xx HTTP response. The body
        // (often BIP-78 JSON `{"errorCode", "message"}`) is included
        // in the raw text but a friendlier summary covers the common
        // case where the user pasted a stale `pj=` URL.
        "payjoin endpoint returned http" in lowered ->
            Res.string.wview_err_payjoin_rejected

        else -> null
    }
    return hint?.let { uiText(it) }
}

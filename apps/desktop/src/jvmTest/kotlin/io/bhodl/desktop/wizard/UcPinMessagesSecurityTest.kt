package io.bhodl.desktop.wizard

import io.bhodl.desktop.ui.userMessage
import io.bhodl.ffi.WalletException
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_registry_open_failed
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Out-of-composition PIN messages: lockout text, one message per
 * failure kind, generic fallback, and the current-language lookup
 * (docs/use-cases.md SEC-030, SEC-038, SEC-100, SEC-142).
 */
class UcPinMessagesSecurityTest {

    @BeforeTest
    fun setUp() = PinAttemptTracker.reset()

    @AfterTest
    fun tearDown() = PinAttemptTracker.reset()

    @Test
    fun `SEC-030 lockoutMessage names the seconds to wait while a scope is locked out`() {
        assertNull(PinAttemptTracker.lockoutMessage(PinScopes.DEVICE))
        repeat(3) { PinAttemptTracker.recordFailure(PinScopes.DEVICE) }
        val message = assertNotNull(PinAttemptTracker.lockoutMessage(PinScopes.DEVICE))
        assertTrue(
            Regex("""Too many wrong PINs\. Wait [1-5]s before retrying\.""").matches(message),
            message,
        )
        assertNull(PinAttemptTracker.lockoutMessage(PinScopes.MIGRATION_LEGACY), "other scopes stay open")
    }

    @Test
    fun `SEC-142 each failure kind has its own message`() {
        val messages = PinFailureKind.entries.associateWith { PinErrors.messageFor(it) }
        assertEquals("Wrong PIN.", messages[PinFailureKind.WrongPin])
        assertTrue(messages.getValue(PinFailureKind.NetworkMismatch).startsWith("This wallet was created on a different network"))
        assertTrue(messages.getValue(PinFailureKind.Storage).startsWith("The wallet files could not be read"))
        assertTrue(messages.getValue(PinFailureKind.NewerVersion).startsWith("This data was written by a newer version"))
        assertEquals("Unlock failed.", messages[PinFailureKind.Other])
        assertEquals(PinFailureKind.entries.size, messages.values.toSet().size, "no two kinds share a message")
        listOf(PinFailureKind.NetworkMismatch, PinFailureKind.Storage, PinFailureKind.NewerVersion).forEach {
            assertTrue("not counted" in messages.getValue(it), "$it must say the attempt was not counted")
        }
    }

    @Test
    fun `SEC-038 an unknown error is Other and shows a sanitized generic message`() {
        val e = IllegalStateException("boom at C:\\Users\\alice\\.bhodl\\registry.sqlite")
        assertEquals(PinFailureKind.Other, PinErrors.classify(e))
        val shown = e.userMessage(localizedText(Res.string.onb_registry_open_failed))
        assertFalse("alice" in shown, shown)
        assertTrue(shown.startsWith("boom at"), shown)
        assertEquals(
            "Failed to open registry.",
            IllegalStateException("").userMessage(localizedText(Res.string.onb_registry_open_failed)),
        )
        assertEquals(PinFailureKind.Other, PinErrors.classify(WalletException.EsploraSync("x")))
    }

    @Test
    fun `SEC-100 messages outside composition follow the current language`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALIAN)
            assertTrue(
                PinErrors.messageFor(PinFailureKind.Storage).startsWith("Impossibile leggere i file del wallet"),
                PinErrors.messageFor(PinFailureKind.Storage),
            )
            assertEquals(
                "Eliminati 2 elementi.",
                LocalWipe.describe(
                    WalletPathsView.ResetResult(listOf(java.io.File("a"), java.io.File("b")), emptyList(), false),
                ),
            )
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("Wiped 1 items.", LocalWipe.describe(WalletPathsView.ResetResult(listOf(java.io.File("a")), emptyList(), false)))
        } finally {
            Locale.setDefault(previous)
        }
    }
}

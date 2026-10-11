package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compose UI tests for [`WelcomeScreen`].
 *
 * The screen is intentionally state-light: it owns no FFI dependency,
 * so we can mount it directly in `runDesktopComposeUiTest` without
 * faking `WalletStore` / `WalletHandle`. The reset path mutates real
 * filesystem state through `WalletPathsView.reset()`; that branch is
 * covered by `WalletPathsTest` rather than here.
 */
@OptIn(ExperimentalTestApi::class)
class WelcomeScreenTest {

    @Test
    fun `tap create wallet invokes the create callback`() = runDesktopComposeUiTest {
        var createCalled = 0
        var unlockCalled = 0
        var restoreCalled = 0
        setContent {
            BhodlTheme {
                WelcomeScreen(
                    onCreateWallet = { createCalled++ },
                    onUnlock = { unlockCalled++ },
                    onRestore = { restoreCalled++ },
                    onResetComplete = {},
                )
            }
        }

        onNodeWithTag(WelcomeTags.CREATE_WALLET).assertIsDisplayed().performClick()

        assertEquals(1, createCalled, "Create new wallet should fire exactly once")
        assertEquals(0, unlockCalled, "Unlock callback must not be triggered")
        assertEquals(0, restoreCalled, "Restore callback must not be triggered")
    }

    @Test
    fun `tap I already have a wallet invokes the unlock callback`() = runDesktopComposeUiTest {
        var unlockCalled = 0
        setContent {
            BhodlTheme {
                WelcomeScreen(
                    onCreateWallet = {},
                    onUnlock = { unlockCalled++ },
                    onRestore = {},
                    onResetComplete = {},
                )
            }
        }
        onNodeWithTag(WelcomeTags.UNLOCK_EXISTING).performClick()
        assertEquals(1, unlockCalled)
    }

    @Test
    fun `tap restore invokes the restore callback`() = runDesktopComposeUiTest {
        var restoreCalled = 0
        setContent {
            BhodlTheme {
                WelcomeScreen(
                    onCreateWallet = {},
                    onUnlock = {},
                    onRestore = { restoreCalled++ },
                    onResetComplete = {},
                )
            }
        }
        onNodeWithTag(WelcomeTags.RESTORE).performClick()
        assertEquals(1, restoreCalled)
    }

    @Test
    fun `tap restore from encrypted backup invokes its callback`() = runDesktopComposeUiTest {
        var backupCalled = 0
        setContent {
            BhodlTheme {
                WelcomeScreen(
                    onCreateWallet = {},
                    onUnlock = {},
                    onRestore = {},
                    onRestoreBackup = { backupCalled++ },
                    onResetComplete = {},
                )
            }
        }
        onNodeWithTag(WelcomeTags.RESTORE_BACKUP).performClick()
        assertEquals(1, backupCalled)
    }
}

package io.bhodl.desktop.screens

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import io.bhodl.desktop.AppClock
import io.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Screen renders compared with committed reference PNGs (Roborazzi).
 *
 * Every call writes the render to `build/screenshots/<name>.png`
 * (`-Dbhodl.screenshots=<dir>` to move it) for a look by eye. What
 * happens next depends on the Gradle task that runs the tests:
 *
 *  - `jvmTest` — nothing else: a local run never fails on pixels.
 *  - `verifyRoborazziJvm` (CI) — compares with
 *    `src/jvmTest/screenshots/<name>.png` and fails beyond [CHANGE_THRESHOLD],
 *    writing `<name>_compare.png` / `<name>_actual.png` to
 *    build/outputs/roborazzi. A missing reference is reported
 *    ("no reference, recorded") instead of failing, so a new render can
 *    land before its reference is recorded.
 *  - `recordRoborazziJvm` — (re)writes the reference.
 *
 * Fonts rasterise differently on Windows / macOS / Linux, so references
 * are Linux renders only: off Linux a record is skipped and a verify is
 * downgraded to a compare (diff images, no failure).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
internal object ScreenshotCheck {

    /** Per-pixel colour distance treated as equal (antialiasing noise). */
    const val MAX_PIXEL_DISTANCE: Float = 0.007f

    /** Share of pixels allowed to differ beyond [MAX_PIXEL_DISTANCE] (0.01 %). */
    const val CHANGE_THRESHOLD: Float = 0.0001f

    /** "Now" for every render: 2026-10-25 12:00 UTC (fixture day [PortfolioFixtures.TODAY], at noon). */
    val NOW_UNIX: Long = PortfolioFixtures.NOW + 12L * 3_600L

    /**
     * Pins [AppClock] to [NOW_UNIX] (and the shared Budget / Transactions
     * month with it): dates and month names stay put whatever the day the
     * tests run. Fixtures must then be relative to [NOW_UNIX], not to the
     * real clock.
     */
    fun pinClock() {
        AppClock.fixAt(NOW_UNIX * 1000L)
        TrackerUiState.month.value = AppClock.currentMonth()
    }

    fun releaseClock() {
        AppClock.fixAt(null)
        TrackerUiState.month.value = AppClock.currentMonth()
    }

    private val isLinux: Boolean = System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)

    fun check(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage()
        val viewDir = File(System.getProperty("bhodl.screenshots", "build/screenshots")).apply { mkdirs() }
        val viewFile = File(viewDir, "$name.png")
        ImageIO.write(image.toAwtImage(), "png", viewFile)

        // The Gradle plugin passes its outputDir (= REFERENCE_DIR, see build.gradle.kts).
        val reference = File(System.getProperty("roborazzi.output.dir") ?: REFERENCE_DIR, "$name.png")
        val requested = roborazziSystemPropertyTaskType()
        val taskType = when {
            !requested.isEnabled() -> return
            requested.isRecording() && !isLinux -> {
                println("Screenshot $name: not recorded, references are Linux renders (render in ${viewFile.path})")
                return
            }
            requested.isRecording() -> RoborazziTaskType.Record
            !reference.exists() -> {
                println("Screenshot $name: no reference, recorded ${viewFile.path}")
                return
            }
            !isLinux -> requested.convertVerifyingToComparing()
            else -> requested
        }
        image.captureRoboImage(
            file = reference,
            roborazziOptions = RoborazziOptions(
                taskType = taskType,
                compareOptions = RoborazziOptions.CompareOptions(
                    changeThreshold = CHANGE_THRESHOLD,
                    imageComparator = SimpleImageComparator(maxDistance = MAX_PIXEL_DISTANCE),
                ),
            ),
        )
    }

    /** Committed references, relative to the module (the test working directory). */
    private const val REFERENCE_DIR = "src/jvmTest/screenshots"
}

/** Captures the window root as screenshot [name] (see [ScreenshotCheck]). */
@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteractionsProvider.screenshot(name: String) = ScreenshotCheck.check(name, onRoot())

/** Captures the topmost root (an open dialog / popup) as screenshot [name]. */
@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteractionsProvider.screenshotTopmost(name: String) {
    val roots = onAllNodes(isRoot())
    ScreenshotCheck.check(name, roots[roots.fetchSemanticsNodes().size - 1])
}

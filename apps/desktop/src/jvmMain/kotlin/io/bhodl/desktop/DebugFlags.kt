package io.bhodl.desktop

/**
 * Security audit 2026-10 — gate for debug-only surfaces.
 *
 * `BHODL_DEBUG=1` alone used to enable the debug watch screen in every
 * build, including shipped installers. It now additionally requires the
 * `bhodl.devBuild=true` JVM system property, which is set only by the
 * Gradle dev `run` task (see `apps/desktop/build.gradle.kts`) and never
 * by packaged distributions.
 */
public object DebugFlags {
    /** System property set by the Gradle `run` task only. */
    public const val DEV_BUILD_PROPERTY: String = "bhodl.devBuild"

    /** `true` only in a dev `run` with `BHODL_DEBUG=1`. */
    public val debugToolsEnabled: Boolean
        get() = System.getProperty(DEV_BUILD_PROPERTY) == "true" &&
            System.getenv("BHODL_DEBUG") == "1"
}

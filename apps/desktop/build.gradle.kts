import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // Required by Navigation Compose KMP for type-safe routes via
    // @Serializable data classes / objects.
    alias(libs.plugins.kotlin.serialization)
    // JVM coverage. Aggregated at the root via `dependencies { kover(project(...)) }`.
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(21)

    jvm {
        // Compose for Desktop is a JVM target. We bind the application
        // entry point on the `main` compilation.
        compilations.named("main") {
            // Spike: leave defaults; the compose.desktop block below handles
            // mainClass + run task wiring.
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            // material3 has its own version stream, decoupled from CMP. Using
            // the `compose.material3` accessor lets the JetBrains Compose
            // plugin pick the version matched to compose-multiplatform 1.10.x.
            implementation(compose.material3)
            // Phase 7.0 — full Material Symbols icon pack. Replaces the
            // Unicode glyph placeholders (₿ ◫ ◷ etc.) with proper icon
            // composables. Bundled with CMP, no extra repository setup.
            implementation(compose.materialIconsExtended)
            // `stringResource` + `Res.string.*` accessor. `:shared` declares
            // this as `implementation` (non-transitive), so consumers must
            // re-declare it.
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.swing)
            // qrose: pure-Kotlin Compose Multiplatform QR-code renderer. MIT.
            // No native deps; works on JVM desktop, Android, iOS, JS, Wasm.
            implementation(libs.qrose)
            // Navigation Compose Multiplatform + serialization runtime for
            // @Serializable route objects. JetBrains KMP port of
            // androidx.navigation, Apache-2.0.
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.serialization.core)
            // Phase 5.0 — JSON parsing for the fiat-price HTTP responses
            // (CoinGecko / mempool.space / Yahoo / etc). Already pinned
            // by the existing :shared compilation via the serialization
            // plugin alias, but apps/desktop needs an explicit `json`
            // dep to use the JSON-specific decoders.
            implementation(libs.kotlinx.serialization.json)
            // Koalaplot — Compose Multiplatform charting library used by the
            // Tracking dashboard (Phase 3.5): line chart for balance over
            // time, donut for category breakdown, bar for inflow/outflow.
            implementation(libs.koalaplot.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Compose UI Test (semantic-tree based). The DSL ships under
            // `compose.desktop.uiTestJUnit4`; we use `runDesktopComposeUiTest`
            // (still `@ExperimentalTestApi` in 1.10) to drive the NavHost
            // without standing up a real window.
            implementation(compose.desktop.uiTestJUnit4)
            // Testcontainers + JSON parsing for the L3 integration tests
            // (regtest bitcoind + electrs). Filed under jvmTest because we
            // re-use the existing classpath/cdylib wiring instead of
            // splitting into a separate compilation; the `integrationTest`
            // task below filters by class-name suffix so the day-to-day
            // `jvmTest` run stays fast and offline.
            implementation(libs.testcontainers)
            implementation(libs.testcontainers.junit)
            // Docker 29.x dropped HTTP API v1.43 — the version that
            // docker-java 3.4.x (bundled with Testcontainers 1.21.3)
            // hard-pins. Force the 3.5.x line which negotiates v1.46
            // and respects DOCKER_API_VERSION as an override.
            implementation("com.github.docker-java:docker-java-core:3.7.1")
            implementation("com.github.docker-java:docker-java-transport-zerodep:3.7.1")
            implementation("com.github.docker-java:docker-java-transport-httpclient5:3.7.1")
            implementation(libs.kotlinx.serialization.json)
            // JUnit 5 platform engine — Testcontainers @Testcontainers
            // extension is JUnit5-native, and kotlin("test") adapts kotlin
            // assertions to whichever engine is on the classpath.
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            runtimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
            // Testcontainers + docker-java emit all diagnostics through
            // SLF4J. Without a binding the bootstrap-time
            // "Could not find a valid Docker environment" message hides
            // the actual transport / DNS failure. slf4j-simple keeps it
            // simple — one line per log event, straight to stderr.
            runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
        }
    }
}

// L3 integration tests: classes whose simple name ends with
// `IntegrationTest` are excluded from the regular `jvmTest` run and
// executed only by the explicit `integrationTest` task. They need
// Docker (for bitcoind + electrs containers) and take seconds per
// case, so we never want them to slow down the inner loop.
val integrationTestPattern: String = "**/*IntegrationTest.class"

// Live Tor tests: classes whose simple name ends with `LiveTorTest`
// talk to the public internet through a REAL Tor daemon
// (BHODL_TOR_SOCKS, default socks5h://127.0.0.1:9050). Excluded from
// `jvmTest`; run by `torTest` (nightly CI + scripts/tor-tests.sh).
val liveTorTestPattern: String = "**/*LiveTorTest.class"

tasks.named<Test>("jvmTest") {
    exclude(integrationTestPattern)
    exclude(liveTorTestPattern)
    // UX review 2026-10 — amounts are formatted with the locale's
    // separators ("3,141,592 sat" in en, "3.141.592 sat" in it). Pin the
    // test JVM to en-US so assertions do not depend on the developer's
    // OS language; tests that need another locale pass it explicitly.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
}

// Dev-only: on the `run` task (and the KMP `jvmRun` alias) the BTC
// average-cost view also counts signet / testnet / regtest wallets, at
// the real BTC price of each day, so the card can be tried with test
// funds. Packaged distributions never set the property. Opt out with
// `-Pbhodl.costBasisTestNetworks=false`.
tasks.withType<JavaExec>().matching { it.name == "run" || it.name == "jvmRun" }.configureEach {
    val enabled = providers.gradleProperty("bhodl.costBasisTestNetworks").orElse("true")
    systemProperty("bhodl.costBasis.includeTestNetworks", enabled.get())
}

tasks.register<Test>("torTest") {
    description = "Runs the live Tor tests (needs a running Tor SOCKS proxy, see BHODL_TOR_SOCKS)."
    group = "verification"
    val jvmTestTask = tasks.named<Test>("jvmTest").get()
    classpath = jvmTestTask.classpath
    testClassesDirs = jvmTestTask.testClassesDirs
    include(liveTorTestPattern)
    useJUnitPlatform()
    failFast = false
    // External network: never consider a previous result up to date.
    outputs.upToDateWhen { false }
    // BHODL_TOR_SOCKS / BHODL_TOR_REQUIRED reach the test JVM through
    // the inherited environment.
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs the L3 integration tests (Testcontainers: bitcoind regtest + electrs)."
    group = "verification"
    val jvmTestTask = tasks.named<Test>("jvmTest").get()
    classpath = jvmTestTask.classpath
    testClassesDirs = jvmTestTask.testClassesDirs
    include(integrationTestPattern)
    useJUnitPlatform()
    // Run every test even if an earlier one fails — each L3 case
    // owns its own bitcoind+electrs pair so they never share state,
    // and seeing all the failures in one cycle beats re-running the
    // ~20s setup for each one in turn.
    failFast = false
    systemProperty("org.testcontainers.checks.disable", "true")
    // Docker Desktop 29.x on Windows ships only CLI-proxy named pipes;
    // every direct API client (docker-java, testcontainers, BuildKit
    // standalone) gets a synthetic HTTP 400 with a redirect-hint body
    // because the real engine lives inside the WSL2 distro and is not
    // exposed over npipe to outside processes.
    //
    // The supported workaround is the TCP endpoint, which the user
    // must enable once in Docker Desktop:
    //   Settings → General → "Expose daemon on tcp://localhost:2375
    //   without TLS" → Apply.
    //
    // Once that toggle is on we point Testcontainers at it. On Linux
    // / macOS the default unix socket already works so no override
    // is needed there.
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        environment("DOCKER_HOST", "tcp://localhost:2375")
    }
    // Pin the API version that Docker 29.x agreed to serve. Without
    // this, docker-java may negotiate a too-old version and the
    // daemon replies 400 to every call. Set via every name docker-java
    // / Testcontainers might consult — env var, system prop, AND
    // the DOCKER_HOST counterparts.
    environment("DOCKER_API_VERSION", "1.45")
    systemProperty("api.version", "1.45")
    systemProperty("docker.api.version", "1.45")
    systemProperty("DOCKER_API_VERSION", "1.45")
    systemProperty("docker.host", "tcp://localhost:2375")
    // Show every test result inline so the slow flow is easy to track.
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
}

compose.desktop {
    application {
        mainClass = "io.bhodl.desktop.MainKt"
        nativeDistributions {
            // Phase-0 spike: we don't ship installers yet, but declaring the
            // formats now makes `./gradlew :apps:desktop:packageDistributionForCurrentOS`
            // work out of the box for ad-hoc binaries during development.
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "BHODL"
            packageVersion = "0.1.0"
        }
    }
}

# Scelta della Navigation Library — BHODL KMP

> Ricerca condotta: maggio 2026  
> Stack di riferimento: Compose Multiplatform 1.10.3 · Kotlin 2.2.21 · JVM Desktop priority · Android Phase 2 · iOS Phase 2-3

---

## 1. TL;DR — Raccomandazione

**Scegliere `org.jetbrains.androidx.navigation:navigation-compose` (versione KMP, stabile 2.9.x).**

Le tre ragioni chiave:

1. **Ufficiale e a lungo termine garantita.** JetBrains ha portato Navigation Compose a KMP come libreria di riferimento dell'intero ecosistema Compose Multiplatform. Non è un progetto comunitario: è mantenuto dallo stesso team che mantiene CMP. Il rischio di abbandono è praticamente zero.
2. **API identica ad Android, zero re-learning.** Chiunque conosca `NavHost` su Android è già produttivo. Il pattern `@Serializable data class/object` per le rotte introduce type-safety compile-time senza boilerplate aggiuntivo. Questo è particolarmente rilevante quando arriveranno i contributor Android-side in Phase 2.
3. **JVM Desktop è un target di prima classe nel sample ufficiale `nav_cupcake`.** Il sample JetBrains usa esattamente il nostro setup (jvm("desktop") + Android + iOS + WasmJS) e ci mostra il pattern funzionante end-to-end. L'alternativa Voyager ha 186 issue aperte e l'ultimo commit significativo risale a febbraio 2026 con pochissima attività.

---

## 2. Analisi per opzione

### 2.1 Opzione A — Voyager (`cafe.adriel.voyager:voyager-navigator`)

#### Versione attuale
- Ultima stable: **1.1.0-beta02** (circa fine 2024)
- Ultima release in assoluto: **2.0.0-alpha01** (17 agosto 2024, pre-release, targeting Kotlin 2.2.0 + CMP 1.8)
- Non esiste ancora una 2.0.0 stable.

In `gradle/libs.versions.toml`:
```toml
[versions]
voyager = "1.1.0-beta02"  # oppure "2.0.0-alpha01" per chi vuole il bleeding edge

[libraries]
voyager-navigator        = { module = "cafe.adriel.voyager:voyager-navigator",        version.ref = "voyager" }
voyager-screenmodel      = { module = "cafe.adriel.voyager:voyager-screenmodel",       version.ref = "voyager" }
voyager-transitions      = { module = "cafe.adriel.voyager:voyager-transitions",       version.ref = "voyager" }
voyager-tab-navigator    = { module = "cafe.adriel.voyager:voyager-tab-navigator",     version.ref = "voyager" }
voyager-koin             = { module = "cafe.adriel.voyager:voyager-koin",              version.ref = "voyager" }
```

#### Status del progetto
**Preoccupante.** Il repository ha 3.100 star e 186 issue aperte. A settembre 2024 un utente ha aperto l'issue #493 "Still maintained?" senza ricevere risposta dal maintainer. I commit tra giugno 2024 e febbraio 2026 si contano sulle dita di una mano. La 2.0.0-alpha01 è l'unica release recente e rimane alpha da otto mesi.

#### KMP coverage reale (2026)
| Target       | Funziona? | Note                                                      |
|--------------|-----------|-----------------------------------------------------------|
| Android      | Sì        | Target principale, più testato                            |
| JVM Desktop  | Parziale  | Issue #476 (IllegalStateException su window close), #447 (cannot pop root), #448 (density bug), #391 (recomposition con nested navigators) |
| iOS          | Parziale  | Issue #552 (IrLinkageError), #545 (crash dopo upgrade a Kotlin 2), #522 #520 #515 (build failure con Kotlin 2.1.0) |
| Wasm/JS      | Alpha     | Supporto aggiunto in 1.1.0-alpha03, poco testato in produzione |

Il pattern di breakage su Kotlin 2.x è sistematico e non risolto. Per un progetto che usa Kotlin 2.2.21, Voyager nella sua versione stabile è fragile.

#### Esempi di adozione di riferimento
Non esiste un wallet bitcoin self-custodial di rilievo con codebase pubblica che usi Voyager in produzione nel 2026. La maggior parte dei progetti KMP open source che erano su Voyager stanno migrando verso Navigation Compose.

#### Curva di apprendimento e documentazione
Documentazione esiste su `voyager.adriel.cafe` ma è parziale sulle questioni Desktop/iOS. La community di supporto (GitHub issues) risponde lentamente.

#### Ergonomia API — esempio wizard step 1 → step 2

```kotlin
// Screen definitions
class WelcomeScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        WelcomeContent(
            onCreateWallet = { navigator.push(GenerateMnemonicScreen()) },
            onRestore      = { navigator.push(RestoreScreen()) }
        )
    }
}

class GenerateMnemonicScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MnemonicScreenModel() }
        GenerateMnemonicContent(
            state  = screenModel.state.collectAsState().value,
            onSaved = { navigator.push(ConfirmMnemonicScreen(screenModel.words)) }
        )
    }
}

// Root
@Composable
fun App() {
    Navigator(WelcomeScreen()) { navigator ->
        SlideTransition(navigator)
    }
}
```

Ergonomia semplice e compatta, ma i parametri tra schermi vanno serializzati manualmente nel costruttore della Screen (niente type-safety compile-time nativa).

#### State management integrato
`ScreenModel` è il sostituto di ViewModel. Ha lifecycle automatico legato allo stack di navigazione (dispose al pop). Supporta Koin via `voyager-koin`. Non ha StateKeeper (no serializzazione automatica dello stato su process death).

#### Trade-off specifici per BHODL
- PRO: API semplice, poco boilerplate, ScreenModel comodo per fasi early
- CONTRO: Kotlin 2.2 compatibility non garantita (molte regression aperte), progetto potenzialmente in stato di semi-abbandono, issue Desktop sistemiche, nessun precedente di riferimento su wallet bitcoin KMP

---

### 2.2 Opzione B — Decompose (`com.arkivanov.decompose:decompose`)

#### Versione attuale
- Ultima stable: **3.5.0** (15 marzo 2025)
- Kotlin target nella 3.5.0: **2.1.0**
- Compose Multiplatform target: **1.9.3**
- Non esiste ancora una release esplicita per Kotlin 2.2 / CMP 1.10.x (nessuna issue aperta, compatibilità da verificare con test locali)

In `gradle/libs.versions.toml`:
```toml
[versions]
decompose      = "3.5.0"
essenty        = "2.5.0"  # dipendenza transitiva obbligatoria

[libraries]
decompose              = { module = "com.arkivanov.decompose:decompose",                    version.ref = "decompose" }
decompose-compose      = { module = "com.arkivanov.decompose:extensions-compose",           version.ref = "decompose" }
decompose-compose-exp  = { module = "com.arkivanov.decompose:extensions-compose-experimental", version.ref = "decompose" }
```

#### Status del progetto
**Stabile e attivamente mantenuto.** Arkadii Ivanov è un maintainer molto reattivo, sviluppatore JetBrains. Il progetto ha una cadenza di release regolare, documentazione completa, e supporto su Kotlin Slack (`#decompose`). Non ha il problema di abbandono che affligge Voyager.

#### KMP coverage reale (2026)
| Target       | Funziona? | Note                                                              |
|--------------|-----------|-------------------------------------------------------------------|
| Android      | Sì        | Prima classe, state preservation cross-process-death nativa       |
| JVM Desktop  | Sì        | Sample ufficiale include target desktop con LifecycleController   |
| iOS          | Sì        | Sample include iOS, LifecycleRegistry manuale ma funzionante      |
| watchOS/tvOS | Sì        | Supportato dichiaratamente                                        |
| Wasm/JS      | Sì        | Supportato, web history integration disponibile                   |

Il sample desktop ufficiale mostra state persistence su file con dialog "vuoi salvare?" — funzionalità molto utile per un wallet desktop.

#### Esempi di adozione di riferimento
Decompose è usato in diverse app KMP production. Il progetto **KotlinConf App** (JetBrains, open source) usava Decompose prima di migrare a Navigation Compose con la versione 2024. **Bitkey** (Square) usa internamente Workflow (un framework simile per FSM), non Decompose né Voyager. Non esiste un wallet bitcoin auto-custodial publico con Decompose che possa servire da riferimento diretto.

#### Curva di apprendimento e documentazione
**Alta.** Decompose richiede di comprendere: ComponentContext, LifecycleRegistry, StateKeeperDispatcher, InstanceKeeper, Child Stack, Config sealed class serializzabili. Per uno sviluppatore che proviene da Compose puro, la curva è di 1-2 settimane prima di sentirsi a proprio agio. La documentazione è completa ma verbosa.

#### Ergonomia API — esempio wizard step 1 → step 2

```kotlin
// Configurazioni (devono essere @Serializable per state preservation)
@Serializable
sealed interface SetupConfig {
    @Serializable data object Welcome          : SetupConfig
    @Serializable data object GenerateMnemonic : SetupConfig
    @Serializable data class  ConfirmMnemonic(val words: List<String>) : SetupConfig
    @Serializable data object PinSetup         : SetupConfig
}

// Root component (condiviso tra piattaforme)
class DefaultRootComponent(
    componentContext: ComponentContext,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<SetupConfig>()

    val stack: Value<ChildStack<*, SetupChild>> = childStack(
        source              = navigation,
        serializer          = SetupConfig.serializer(),
        initialConfiguration = SetupConfig.Welcome,
        handleBackButton    = true,
        childFactory        = ::createChild,
    )

    private fun createChild(config: SetupConfig, ctx: ComponentContext): SetupChild =
        when (config) {
            SetupConfig.Welcome          -> SetupChild.Welcome(
                DefaultWelcomeComponent(ctx, onCreateWallet = { navigation.push(SetupConfig.GenerateMnemonic) })
            )
            SetupConfig.GenerateMnemonic -> SetupChild.GenerateMnemonic(
                DefaultGenerateMnemonicComponent(ctx, onSaved = { words ->
                    navigation.push(SetupConfig.ConfirmMnemonic(words))
                })
            )
            is SetupConfig.ConfirmMnemonic -> SetupChild.ConfirmMnemonic(
                DefaultConfirmMnemonicComponent(ctx, words = config.words,
                    onConfirmed = { navigation.push(SetupConfig.PinSetup) })
            )
            SetupConfig.PinSetup         -> SetupChild.PinSetup(
                DefaultPinSetupComponent(ctx, onComplete = { navigation.replaceAll(DashboardConfig) })
            )
        }

    sealed class SetupChild {
        class Welcome(val component: WelcomeComponent)                   : SetupChild()
        class GenerateMnemonic(val component: GenerateMnemonicComponent) : SetupChild()
        class ConfirmMnemonic(val component: ConfirmMnemonicComponent)   : SetupChild()
        class PinSetup(val component: PinSetupComponent)                 : SetupChild()
    }
}

// UI Compose (commonMain)
@Composable
fun RootContent(component: DefaultRootComponent) {
    val stack by component.stack.subscribeAsState()
    Children(stack = stack, animation = stackAnimation(slide())) {
        when (val child = it.instance) {
            is SetupChild.Welcome          -> WelcomeContent(child.component)
            is SetupChild.GenerateMnemonic -> GenerateMnemonicContent(child.component)
            is SetupChild.ConfirmMnemonic  -> ConfirmMnemonicContent(child.component)
            is SetupChild.PinSetup         -> PinSetupContent(child.component)
        }
    }
}

// Desktop entry point (jvmMain)
fun main() {
    val lifecycle  = LifecycleRegistry()
    val stateKeeper = StateKeeperDispatcher(tryRestoreStateFromFile())
    val root = DefaultRootComponent(
        DefaultComponentContext(lifecycle = lifecycle, stateKeeper = stateKeeper)
    )
    application {
        val windowState = rememberWindowState()
        LifecycleController(lifecycle, windowState)
        Window(onCloseRequest = ::exitApplication, title = "BHODL") {
            RootContent(root)
        }
    }
}
```

Il codice è più verboso ma completamente type-safe. I parametri tra schermi passano attraverso la `Config` serializzabile — niente stringe magiche, compile-time safety garantita.

#### State management integrato
`InstanceKeeper` = ViewModel-equivalent con lifecycle. `StateKeeper` = serializzazione stato per process death (Android) e state persistence su file (Desktop, come mostrato nel sample). I due meccanismi sono ortogonali e componibili.

#### Trade-off specifici per BHODL
- PRO: architettura solida e testabile, state preservation su Desktop e Android, type-safe routing, maintainer affidabile, piena compatibilità con Kotlin 2.x
- CONTRO: boilerplate alto, curva di apprendimento significativa, non compatibile esplicitamente con CMP 1.10.x (solo 1.9.3 certificata), rischio di migration cost se JetBrains consolida Navigation Compose come standard

---

### 2.3 Opzione C — Navigation Compose Multiplatform (`org.jetbrains.androidx.navigation:navigation-compose`)

#### Versione attuale
- Versione KMP **stabile: 2.9.2** (circa marzo 2026, 2 mesi fa dalla data di questa ricerca)
- Versione usata nel sample `nav_cupcake` di JetBrains: **2.9.0-rc01** (con Kotlin 2.2.10 + CMP 1.8.2)
- Versione Android standalone più recente: **2.9.8** (22 aprile 2026)
- Alpha corrente Android: **2.10.0-alpha04** (6 maggio 2026)

In `gradle/libs.versions.toml`:
```toml
[versions]
navigation-compose-kmp = "2.9.2"

[libraries]
navigation-compose = { module = "org.jetbrains.androidx.navigation:navigation-compose", version.ref = "navigation-compose-kmp" }
```

**Nota**: il group ID è `org.jetbrains.androidx.navigation`, non `androidx.navigation`. Il modulo KMP è separato.

#### Status del progetto
**Stabile, a lungo termine garantito.** JetBrains + Google co-mantengono questo porting. Il sample ufficiale `nav_cupcake` nel repository di Compose Multiplatform è il progetto di riferimento e viene aggiornato ad ogni release CMP. Non c'è rischio di abbandono.

#### KMP coverage reale (2026)
| Target       | Funziona? | Note                                                              |
|--------------|-----------|-------------------------------------------------------------------|
| Android      | Sì        | Identico all'originale androidx.navigation:navigation-compose     |
| JVM Desktop  | Sì        | Supportato come target di prima classe nel sample nav_cupcake     |
| iOS          | Sì        | iosArm64 + iosSimulatorArm64 nel sample nav_cupcake              |
| Wasm         | Sì        | wasmJs con `bindToBrowserNavigation()` per URL-bar integration    |
| JS           | Sì        | Supportato con binding browser navigation                         |

Il sample `nav_cupcake` dimostra tutti e sei i target (Android, Desktop JVM, iOS Arm64, iOS Simulator, Wasm, JS) con la stessa codebase commonMain.

#### Esempi di adozione di riferimento
Il repository JetBrains compose-multiplatform include `nav_cupcake` come riferimento ufficiale. La KotlinConf App (open source, JetBrains) usa Navigation Compose KMP dal 2025. L'ecosistema Android è immenso: chiunque abbia esperienza con navigation-compose Android è immediatamente produttivo.

Non esiste ancora un wallet bitcoin self-custodial open-source prominente che usi esplicitamente Navigation Compose KMP nel 2026, ma Mutiny Wallet (Flutter) e Zeus (React Native) non sono confrontabili. Il progetto `bdk-sample` di Bitcoin Dev Kit usa BDK-Kotlin senza navigazione KMP strutturata.

#### Curva di apprendimento e documentazione
**Bassa** per chiunque abbia esperienza Android. La documentazione su `kotlinlang.org/docs/multiplatform/compose-navigation-routing.html` è aggiornata. API identica ad Android riduce il costo di onboarding futuro.

#### Ergonomia API — esempio wizard step 1 → step 2

```kotlin
// Route definitions (commonMain) — type-safe con @Serializable
@Serializable data object WelcomeRoute
@Serializable data object GenerateMnemonicRoute
@Serializable data class  ConfirmMnemonicRoute(val words: List<String>)
@Serializable data object PinSetupRoute
@Serializable data object DashboardRoute

// Navigazione (commonMain)
@Composable
fun BhodlApp() {
    val navController = rememberNavController()

    NavHost(
        navController  = navController,
        startDestination = WelcomeRoute,
        enterTransition  = { slideInHorizontally(initialOffsetX = { it }) },
        exitTransition   = { slideOutHorizontally(targetOffsetX = { -it }) },
        popEnterTransition  = { slideInHorizontally(initialOffsetX = { -it }) },
        popExitTransition   = { slideOutHorizontally(targetOffsetX = { it }) },
    ) {
        composable<WelcomeRoute> {
            WelcomeScreen(
                onCreateWallet = { navController.navigate(GenerateMnemonicRoute) },
                onRestore      = { navController.navigate(RestoreRoute) }
            )
        }

        composable<GenerateMnemonicRoute> {
            val viewModel = viewModel { MnemonicViewModel() }
            GenerateMnemonicScreen(
                state  = viewModel.state.collectAsStateWithLifecycle().value,
                onSaved = { words ->
                    navController.navigate(ConfirmMnemonicRoute(words))
                }
            )
        }

        composable<ConfirmMnemonicRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ConfirmMnemonicRoute>()
            ConfirmMnemonicScreen(
                words       = route.words,
                onConfirmed = { navController.navigate(PinSetupRoute) }
            )
        }

        composable<PinSetupRoute> {
            PinSetupScreen(
                onComplete = {
                    navController.navigate(DashboardRoute) {
                        popUpTo(WelcomeRoute) { inclusive = true }
                    }
                }
            )
        }

        composable<DashboardRoute> { DashboardScreen() }
    }
}

// Entrypoint Desktop (jvmMain)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "BHODL") {
        BhodlApp()
    }
}

// Entrypoint Android (androidMain)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BhodlApp() }
    }
}
```

Il codice è diretto, leggibile, e familiar per chiunque abbia fatto Android. I parametri (`ConfirmMnemonicRoute(words)`) sono type-safe via serializzazione Kotlin. La navigazione back stack si gestisce con `popUpTo`.

#### State management integrato
Supporta `viewModel { }` KMP-style (via `lifecycle-viewmodel-compose`) per Desktop e Android. Non ha un ViewModel-equivalent nativo per iOS nella versione KMP corrente — su iOS è necessario usare `remember { }` o uno state container custom. Questo è il principale gap rispetto a Decompose.

Tuttavia, per BHODL che usa già `remember { mutableStateOf() }` raw, questo non è un cambiamento regressivo. In Phase 2 con Koin, si può iniettare il ViewModel tramite `koinViewModel()` (supportato da `io.insert-koin:koin-compose`).

#### Trade-off specifici per BHODL
- PRO: ufficiale JetBrains, API familiare, type-safe, tutti i target funzionanti incluso JVM Desktop, compatibile con Kotlin 2.2 + CMP 1.10.x, bassa curva di apprendimento, codebase pronta per Android Phase 2 senza modifiche alla struttura di navigazione
- CONTRO: ViewModel KMP non ancora al 100% su iOS (gap chiuso parzialmente da lifecycle-viewmodel 2.9.x ma richiede attenzione), deep linking complesso richiede setup aggiuntivo su Desktop

---

### 2.4 Opzione D — Raw Compose State

#### Descrizione
Gestione della navigazione via `sealed class Route` + `var currentScreen by remember { mutableStateOf<Route>(Route.Welcome) }` + `AnimatedContent`.

```kotlin
sealed class Route {
    object Welcome          : Route()
    object GenerateMnemonic : Route()
    data class ConfirmMnemonic(val words: List<String>) : Route()
    object PinSetup         : Route()
    object Dashboard        : Route()
}

@Composable
fun BhodlApp() {
    var route by remember { mutableStateOf<Route>(Route.Welcome) }
    var backStack by remember { mutableStateOf(listOf<Route>()) }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
        }
    ) { currentRoute ->
        when (currentRoute) {
            is Route.Welcome -> WelcomeScreen(
                onCreateWallet = {
                    backStack = backStack + route
                    route = Route.GenerateMnemonic
                }
            )
            is Route.GenerateMnemonic -> GenerateMnemonicScreen(
                onSaved = { words ->
                    backStack = backStack + route
                    route = Route.ConfirmMnemonic(words)
                }
            )
            is Route.ConfirmMnemonic -> ConfirmMnemonicScreen(
                words = currentRoute.words,
                onConfirmed = {
                    backStack = backStack + route
                    route = Route.PinSetup
                }
            )
            is Route.PinSetup -> PinSetupScreen(
                onComplete = {
                    backStack = listOf()
                    route = Route.Dashboard
                }
            )
            is Route.Dashboard -> DashboardScreen()
        }
    }
}
```

#### Status
Nessuna dipendenza esterna — è codice puro Compose.

#### KMP coverage
Funziona ovunque funzioni Compose Multiplatform. Nessuna limitazione di piattaforma.

#### Pro
- Zero dipendenze aggiuntive
- Massimo controllo
- Funziona oggi, con qualsiasi versione di Kotlin e CMP
- Perfetto per un MVP a 4-5 schermi

#### Contro
- Back stack manuale: devi implementare tu la logica di pop, back gesture handling per Desktop/iOS, e la gestione dei parametri tra schermi
- Non scala oltre ~8-10 schermi senza diventare un `when` mostruoso difficile da leggere
- Nessun supporto nativo a deep linking, nested navigation, bottom tabs
- State preservation su process death: da implementare da zero
- Non prepara la codebase per i pattern standard — quando arriverà un secondo contributor, non troverà nulla di familiare
- Le schermate Phase 2-3 (SendWizard, nested Settings, bottom tabs) renderanno questa scelta dolorosa da mantenere

#### Trade-off specifici per BHODL
Appropriata per un prototipo o per i primissimi 2-3 schermi. Il progetto BHODL ha già nella roadmap 15+ schermate con nesting (Settings nested, SendWizard, tab). L'opzione D diventa tech debt certo entro Phase 2.

---

## 3. Tabella riassuntiva matrice

| Feature                             | Voyager 2.0.0-α01 | Decompose 3.5.0 | Nav Compose KMP 2.9.2 | Raw State      |
|-------------------------------------|:-----------------:|:---------------:|:---------------------:|:--------------:|
| **Status progetto**                 | Semi-abbandono    | Stabile         | Stabile/Ufficiale     | N/A            |
| **Compatibilità Kotlin 2.2**        | Fragile (α)       | 2.1.0 certa, 2.2 da testare | Sì (sample usa 2.2.10) | Sì |
| **Compatibilità CMP 1.10.x**        | Alpha (1.8 target)| 1.9.3 certa, 1.10 da testare | Sì (2.9.2 target 1.10) | Sì |
| **JVM Desktop**                     | Parziale (bug)    | Sì              | Sì                    | Sì             |
| **Android**                         | Sì                | Sì              | Sì                    | Sì             |
| **iOS**                             | Parziale (crash)  | Sì              | Sì                    | Sì             |
| **Wasm/JS**                         | Alpha             | Sì              | Sì                    | Sì             |
| **Type-safe routing**               | No (cast)         | Sì (@Serializable Config) | Sì (@Serializable Route) | No |
| **Back stack reasoning**            | Automatico        | Automatico      | Automatico            | Manuale        |
| **Nested navigation**               | Sì (LocalNavigator) | Sì (Child Stack) | Sì (nested NavHost) | Manuale        |
| **Deep linking**                    | Limitato          | Sì (handleDeepLink) | Sì (URL binding) | No           |
| **Parameter passing**               | Costruttore Screen| Config serializz.| Route serializz.     | Ad-hoc         |
| **Animazioni dichiarative**         | SlideTransition   | stackAnimation(slide()) | enterTransition/exitTransition | AnimatedContent |
| **State management integrato**      | ScreenModel       | InstanceKeeper + StateKeeper | ViewModel KMP (parziale iOS) | remember{}    |
| **State preservation process death**| No               | Sì (Android + Desktop) | Parziale (Android) | No            |
| **DI Koin integration**             | voyager-koin      | Manuale/standard | koinViewModel()      | Standard       |
| **iOS back gesture (swipe-back)**   | Parziale          | Sì              | Sì                    | No             |
| **Bottom tabs**                     | TabNavigator      | Child Pages     | Composable destinaz.  | Manuale        |
| **Bundle size impact**              | ~200KB            | ~300KB          | ~250KB                | 0              |
| **Curva di apprendimento**          | Bassa             | Alta            | Bassa/Media           | Minima         |
| **Documentazione qualità**          | Media             | Buona           | Ottima                | N/A            |
| **Maintainer garantito LT**         | No                | Sì              | Sì (JetBrains)        | N/A            |

---

## 4. Use case specifici BHODL

### 4.1 Setup Wizard (4 schermi: Welcome → GenerateMnemonic → ConfirmMnemonic → PinSetup)

**Nav Compose KMP:**
```kotlin
// Una sola volta in App.kt, tutto il wizard è nel NavHost principale
NavHost(navController, startDestination = WelcomeRoute) {
    composable<WelcomeRoute>          { WelcomeScreen(onCreateWallet = { navController.navigate(GenerateMnemonicRoute) }) }
    composable<GenerateMnemonicRoute> { GenerateMnemonicScreen(onSaved = { words -> navController.navigate(ConfirmMnemonicRoute(words)) }) }
    composable<ConfirmMnemonicRoute>  { ConfirmMnemonicScreen(words = it.toRoute<ConfirmMnemonicRoute>().words, onConfirmed = { navController.navigate(PinSetupRoute) }) }
    composable<PinSetupRoute>         { PinSetupScreen(onComplete = { navController.navigate(DashboardRoute) { popUpTo(WelcomeRoute) { inclusive = true } } }) }
}
```
Insidia: `popUpTo(WelcomeRoute) { inclusive = true }` è obbligatorio al termine del wizard per svuotare il back stack; dimenticarlo significa che l'utente può tornare al wizard con il back button anche dopo il setup.

**Decompose:**
```kotlin
// Nel DefaultRootComponent: StackNavigation<SetupConfig> + childStack
// Il childFactory crea i componenti con le dipendenze iniettate
// Vantaggioso perché la logica di navigazione è separata dall'UI e testabile in unit test puri
navigation.push(SetupConfig.GenerateMnemonic)
// Per il pop-to-root dopo PinSetup:
navigation.replaceAll(AppConfig.Dashboard)
```
Insidia: ogni schermo richiede una propria interfaccia Component, una DefaultXxxComponent, e un XxxContent composable separato. Per 4 schermi wizard è ~12 file. Overkill per un wizard semplice, ma la struttura paga quando arrivano i test unitari.

**Voyager:**
```kotlin
navigator.push(GenerateMnemonicScreen())
// PassaGgio parametri: costruttore della Screen class
class ConfirmMnemonicScreen(private val words: List<String>) : Screen { ... }
```
Insidia: la list `words` passata al costruttore non è serializzata di default. Se Compose ricrea la Screen (ad es. su configuration change su Android), i dati si perdono a meno di implementare `rememberSaveable` manuale.

**Raw:**
```kotlin
var route by remember { mutableStateOf<SetupRoute>(SetupRoute.Welcome) }
// Niente pop-to-root built-in: devi azzerare il back stack manualmente
backStack = emptyList(); route = AppRoute.Dashboard
```
Insidia: il back button fisico su Android non funziona automaticamente — devi agganciare `BackHandler`.

---

### 4.2 UnlockScreen (boot guard: se DB esiste → mostra UnlockScreen altrimenti → Welcome)

**Nav Compose KMP:**
```kotlin
// Logica nel ViewModel o nel LaunchedEffect prima del NavHost
val startDestination: Route by viewModel.startRoute.collectAsState()
// NavHost usa startDestination dinamico
NavHost(navController, startDestination = startDestination) { ... }
```
Attenzione: `startDestination` non può essere cambiato a runtime dopo la composizione iniziale del NavHost. La soluzione standard è mostrare uno splash screen mentre si determina la route iniziale, poi costruire il NavHost con la destinazione corretta.

**Decompose:**
Il componente root decide nell'initialConfiguration sulla base dello stato del DB:
```kotlin
initialConfiguration = if (dbExists()) AppConfig.Unlock else AppConfig.Welcome
```
Questo è più pulito: la logica vive nel layer business, non nella UI.

---

### 4.3 Send Wizard (3-4 step: DestinationInput → AmountFees → Review → Broadcast)

**Nav Compose KMP:**
```kotlin
@Serializable data class SendDestinationRoute(val prefillAddress: String? = null)
@Serializable data class SendAmountRoute(val address: String, val maxSpendable: Long)
@Serializable data class SendReviewRoute(val address: String, val amountSats: Long, val feeSats: Long)
@Serializable data object SendBroadcastRoute

navigation(route = SendGraph) {
    composable<SendDestinationRoute> { ... }
    composable<SendAmountRoute>      { ... }
    composable<SendReviewRoute>      { ... }
    composable<SendBroadcastRoute>   { ... }
}
```
Il nested graph `navigation(route = SendGraph)` mantiene il send wizard isolato nel back stack — il back button all'inizio del wizard esce dall'intero flow senza passare per gli step intermedi.

**Decompose:**
Il SendWizard diventerebbe un proprio `ChildStack` annidato dentro il RootComponent, con un suo `WizardComponent` che gestisce i 4 step. Separazione pulita, testabile, ma ~20 file aggiuntivi.

---

### 4.4 Dashboard con bottom tabs probabili (Balance, Activity, Settings)

**Nav Compose KMP:**
```kotlin
// Bottom navigation standard con NavHost
val navController = rememberNavController()
val currentBackStack by navController.currentBackStackEntryAsState()

Scaffold(
    bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = currentRoute == BalanceRoute, onClick = { navController.navigate(BalanceRoute) { launchSingleTop = true } }, ...)
            NavigationBarItem(selected = currentRoute == ActivityRoute, ...)
            NavigationBarItem(selected = currentRoute == SettingsRoute, ...)
        }
    }
) {
    NavHost(navController, startDestination = BalanceRoute) {
        composable<BalanceRoute>   { DashboardScreen() }
        composable<ActivityRoute>  { ActivityScreen() }
        composable<SettingsRoute>  { SettingsScreen() }
    }
}
```
`launchSingleTop = true` previene la ricreazione della tab se già selezionata. Per preservare lo stato di ogni tab, usare `saveState = true` + `restoreState = true`.

**Decompose:**
`ChildPages` o `ChildSlot` per i tab. Ogni tab è un componente indipendente con lifecycle gestito. Più verboso ma con lifecycle completamente separato tra tab.

**Voyager:**
`TabNavigator` built-in con API semplice, ma le issue di stato e disposal non risolte su Desktop rendono questa scelta rischiosa per BHODL.

---

### 4.5 Settings nested (Network → Network Detail, Privacy → Privacy Detail, About)

**Nav Compose KMP:**
```kotlin
navigation<SettingsGraph>(startDestination = SettingsRootRoute) {
    composable<SettingsRootRoute>   { SettingsRootScreen(onNetworkClick = { navController.navigate(NetworkRoute) }) }
    composable<NetworkRoute>        { NetworkSettingsScreen() }
    composable<PrivacyRoute>        { PrivacySettingsScreen() }
    composable<AboutRoute>          { AboutScreen() }
}
```
Il nested graph `navigation<SettingsGraph>` fa sì che il back button esca dall'intera sezione Settings tornando alla tab precedente del Dashboard.

---

## 5. Raccomandazione finale

### Scelta: `org.jetbrains.androidx.navigation:navigation-compose` versione **2.9.2**

**Motivazione in tre punti:**

1. **Rischio di maintenance quasi zero.** JetBrains mantiene questa libreria come parte dell'infrastruttura di Compose Multiplatform. Ogni release CMP porta con sé un aggiornamento del sample `nav_cupcake` che valida tutti i target. Voyager ha mostrato segnali preoccupanti di semi-abbandono (186 issue aperte, nessuna risposta alla domanda "is it maintained?", ultima release significativa agosto 2024). Decompose è solido ma non ancora certificato per CMP 1.10.x / Kotlin 2.2.

2. **Zero costo di re-learning per la fase Android.** Quando BHODL arriverà in Phase 2 con target Android, la struttura di navigazione in `commonMain` non dovrà cambiare. L'entry point Android (`MainActivity`) si aggiunge in 5 righe. Non è lo stesso con Decompose (che aggiunge `DefaultComponentContext` e `LifecycleController` per Android) né con Voyager (dove i crash su Android Kotlin 2.x richiedono workaround).

3. **Il sample ufficiale usa esattamente il nostro setup.** `nav_cupcake` (CMP 1.8.2, Kotlin 2.2.10, targeting Desktop + Android + iOS + Wasm) è la dimostrazione live che l'intera pipeline funziona. Non è teoria: è codice compilabile e testabile oggi.

### Versioni esatte da pinnare in `gradle/libs.versions.toml`

```toml
[versions]
navigation-compose-kmp = "2.9.2"

# Dipendenze correlate consigliate
lifecycle-viewmodel-compose = "2.9.0"  # per viewModel() in commonMain
koin-compose                = "4.0.0"  # per koinViewModel() quando arriva Koin in Phase 2

[libraries]
navigation-compose = { module = "org.jetbrains.androidx.navigation:navigation-compose", version.ref = "navigation-compose-kmp" }
lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle-viewmodel-compose" }
```

Aggiungere in `commonMain.dependencies`:
```kotlin
implementation(libs.navigation.compose)
implementation(libs.lifecycle.viewmodel.compose)
```

### Cosa fare se in 6 mesi sembra essere stata la scelta sbagliata

Il migration cost da Navigation Compose KMP verso Decompose è stimato **basso-medio**: le rotte `@Serializable` diventano `@Serializable Config`, il `NavHost` diventa `childStack + Children`, e le callback di navigazione si spostano dal layer Compose al layer Component. Con ~10-15 schermate il costo è 2-3 giorni di lavoro per uno sviluppatore senior. Il contrario (da Decompose a Nav Compose) ha costo simile.

La cosa da NON fare è restare su Raw State oltre Phase 1 alpha: quel refactor diventa esponenzialmente più costoso con ogni schermata aggiunta.

### Scenario in cui questa raccomandazione non vale

**Se BHODL aggiunge target Web (Wasm/JS) in Phase 7 come URL-routable SPA**, Navigation Compose KMP è ancora valida (`bindToBrowserNavigation()`). La raccomandazione invece andrebbe rivalutata se:

- Si decide di portare la logica di navigazione in una libreria multiplatform condivisa con un'app web React (in quel caso, Decompose con un UI layer separato sarebbe più pulito architetturalmente).
- Si decide di adottare un'architettura strict MVVM con test unit puri dei navigation flow senza Compose — Decompose ha un vantaggio netto lì perché il Component è testabile senza emulatore.

---

## 6. Out of scope

Questa decisione riguarda esclusivamente **come ci si sposta tra schermate** e come si gestisce il back stack. Non riguarda, e non deve influenzare:

- **Animation system**: le transizioni `enterTransition`/`exitTransition` di Nav Compose sono implementate sopra `AnimatedContent` standard. Il sistema di animazione di BHODL (es. `BhodlTheme` custom, eventuali Lottie o animazioni brand) è completamente indipendente.
- **Theming**: `BhodlTheme` in `shared/nonNativeMain` rimane invariato. La navigation library non tocca il MaterialTheme.
- **Dependency Injection**: Koin si integra con Navigation Compose tramite `koinViewModel()` quando viene introdotto in Phase 2. La scelta della navigation library non preclude né favorisce nessuna strategia DI.
- **State management di business logic**: `ViewModel` / `ScreenModel` / `StateFlow` sono ortogonali alla navigation library. In Phase 1 alpha, `remember { mutableStateOf() }` continua a funzionare dentro i composable destinazione di `NavHost`.
- **Secure storage / PIN / biometrics**: tutta la logica crittografica (Keystore Android, SQLCipher, biometrics) vive nel layer `shared/commonMain` e nei layer nativi — non è influenzata dalla navigation library.
- **i18n strings**: `compose-multiplatform-resources` e `StringResource` rimangono invariati. Le stringhe vengono chiamate dalle composable destination esattamente come oggi.

---

*Fine ricerca — navigation-choice.md — maggio 2026*

# Contributing to BHODL

Thanks for considering a contribution. BHODL is a self-custodial Bitcoin wallet, so code
in this repo will one day handle other people's money. It is alpha and runs on test
networks only, which makes this a good time to shape it.

## Quick links

- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)
- [Developer handbook](docs/handbook.md): architecture, build, security model
- [Design tasks looking for help](docs/design-help-wanted.md)
- [Plan and roadmap](PLAN.md) (Italian) and [tech stack](TECH_STACK.md)
- [Manual test book](docs/test-book.md)

## Ways to contribute

- **Design and UX**: the area that needs help most. See [Design contributions](#design-contributions).
- Report a bug or propose a feature with the issue templates.
- Fix a tracked issue or implement a documented roadmap item.
- Review open pull requests, test on signet with the [test book](docs/test-book.md).
- Improve docs, error messages and translations (several planning documents are in
  Italian and would benefit from English versions).

For a security issue, do **not** open a public issue. Follow [SECURITY.md](SECURITY.md).

## Design contributions

You do not need to build the project to help with design.

1. **Pick a task.** [`docs/design-help-wanted.md`](docs/design-help-wanted.md) lists scoped
   design tasks with context and the deliverable we are looking for. Issues labelled
   [`design`](https://github.com/mariepellegrino89/bhodl/labels/design) are the same tasks
   on GitHub. Comment on the issue before starting so two people do not work on the same
   thing.
2. **Learn the direction.** BHODL is dense and data-heavy (Sparrow / Bloomberg / Kubera
   style), bitcoin is the unit of account and fiat only appears on operational surfaces.
   Read [`docs/ux-review-2026-10.md`](docs/ux-review-2026-10.md) (Italian) for the review
   and the decisions already taken, and [`design/`](design/) for tokens, the theme and the
   contrast report. The [Bitcoin Design Guide](https://bitcoin.design/guide/) is our
   shared vocabulary.
3. **Work in the tool you like.** Figma or Penpot are both fine. Share a public view link
   (or exported PNG / PDF frames) in the issue. Sketches, annotated screenshots and written
   critiques are just as welcome as polished mockups.
4. **Use the screenshots.** Current screens are in [`docs/screenshots/`](docs/screenshots/).
   If you need a screen that is not there, ask in the issue and a maintainer will render
   it with demo data.
5. **Feedback without a task?** Open a
   [design feedback issue](https://github.com/mariepellegrino89/bhodl/issues/new?template=design_feedback.yml)
   about any screen.

A maintainer turns accepted designs into code, or pairs with you if you want to write the
Compose code yourself. Design work is credited in the pull request that implements it.

## Repository structure

```
crates/
  bhodl-core/            Amount, Network, Argon2id KDF
  bhodl-wallet-onchain/  BDK wallet engine, SQLCipher persistence, registry, PayJoin, Tor
  bhodl-uniffi/          FFI surface exposed to Kotlin
shared/                  Kotlin Multiplatform module: theme, generated UniFFI bindings
apps/
  desktop/               Compose Desktop app (wallet + tracker); all features live here
  android/               Android host app: packages the Rust core, placeholder UI
  ios/                   iOS scaffold, not built yet
design/                  Design tokens, theme reference, contrast report
docs/                    Handbook, test book, reviews, research notes
scripts/                 Helper scripts
.github/                 Issue / PR templates, CI workflows
```

## Development setup

The [handbook](docs/handbook.md#5-build-run-test) has the full guide. Short form:

### Required toolchain

- Rust 1.88 stable, edition 2024 (pinned via `rust-toolchain.toml`).
- JDK 21 (Temurin).
- Gradle via the wrapper (`./gradlew`); Kotlin and Compose Multiplatform come from the
  Gradle plugins.
- Docker, only for the integration tests (bitcoind regtest + electrs).
- Android SDK (platform 35, NDK r28) only if you build the Android target, on Linux,
  macOS or WSL2.

### Windows specific

SQLCipher compiles OpenSSL from source through `openssl-src`, which needs Strawberry Perl
(the Perl bundled with Git Bash lacks `Locale::Maketext::Simple`):

```
winget install StrawberryPerl.StrawberryPerl
export PATH="/c/Strawberry/perl/bin:/c/Strawberry/c/bin:$PATH"
cargo build
```

If the OpenSSL build fails with path-length errors (deep checkout, git worktree), use a
short target directory: `export CARGO_TARGET_DIR=/c/t`. The first build takes around 10
minutes; later builds reuse the cache.

### Common commands

```
cargo test --workspace
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo deny check

./gradlew :apps:desktop:jvmTest
./gradlew :apps:desktop:run
```

Dependency bumps: Gradle verifies artifact checksums, so after changing a version run
`./gradlew --write-verification-metadata sha256 :shared:jvmTest :apps:desktop:jvmTest` and commit
the updated `gradle/verification-metadata.xml`. The `OSV scan` job then checks it for known
vulnerabilities; see handbook section 5.3 for how to handle a finding. Docker images used by the
integration tests are pinned by digest and bumped deliberately (handbook section 5.3).

## Pull request workflow

1. Fork the repo and branch from `main` (`type/short-description`, e.g. `fix/fee-rounding`).
2. Keep changes small and focused: one logical change per pull request.
3. Add tests for new behaviour. Minimum bar: a regression test that fails before the
   change and passes after. Bugs in amounts or balances always get a test.
4. Run the checks above locally. GitHub Actions may be paused, so local runs are what
   reviewers rely on; say in the PR what you ran.
5. Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/),
   lowercase and imperative: `feat(scope): ...`, `fix(scope): ...`, `docs: ...`,
   `chore: ...`, `refactor: ...`, `test: ...`, `ci: ...`, `perf: ...`.
6. Do not bump version numbers; that happens in dedicated release commits.
7. Open the pull request against `main` and fill in the template. UI changes need
   before / after screenshots in light and dark.

## Sign-off

By contributing, you certify that you have the right to submit the work under the
project's licence (`MIT OR Apache-2.0`), following the
[Developer Certificate of Origin](https://developercertificate.org). Please sign your
commits with `git commit -s`.

## Code style

### Rust

- Edition 2024. Public API has rustdoc.
- `unsafe` is forbidden at the workspace level; the FFI shim crate is the only documented
  exception.
- Errors are typed with `thiserror`; new failure modes go in the per-crate error enum.
- No `unwrap` / `expect` outside tests unless a comment explains why it cannot fail.
- `clippy::pedantic` is on; the exceptions in `Cargo.toml` are the only accepted ones.

### Kotlin

- Public API in `commonMain` is documented with KDoc.
- State hoisting follows the Compose guidelines: stateful screens, stateless leaves.
- Every user-visible string goes through the string resources in both English and
  Italian; a test fails on hard-coded UI strings.
- Amounts go through the shared formatter, never built by hand (`"$x sat"`).

### General

- Lines wrap around 100 columns. LF line endings (`.gitattributes` enforces it).
- `kebab-case` for Markdown and Gradle files, `snake_case` for Rust, `PascalCase` for
  Kotlin types.

## Tests

- Network-dependent Rust tests are `#[ignore]` or behind a feature (`tor-tests`) and never
  run on pull requests.
- Compose UI tests must never touch the real `~/.bhodl`: inject a temp directory (see
  handbook §7).

## Reviewing pull requests

Reviewers focus on correctness of key, signing and amount paths; test coverage of new
behaviour; licence compatibility of new dependencies; and consistency with the design
direction for UI changes. Approval from a maintainer is required to merge.

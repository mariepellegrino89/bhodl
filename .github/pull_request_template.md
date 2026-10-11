## What and why

<!-- What does this change, and which issue does it close? (Closes #123) -->

## How it was tested

<!-- Commands you ran and manual checks (signet / regtest). GitHub Actions may be paused,
so list what you ran locally. -->

- [ ] `cargo fmt --all --check` and `cargo clippy --workspace --all-targets -- -D warnings`
- [ ] `cargo test --workspace`
- [ ] `./gradlew :apps:desktop:jvmTest`
- [ ] Manual check on signet / regtest (describe below)

## UI changes

<!-- Before / after screenshots in light and dark, with demo data only. Delete if not UI. -->

## Checklist

- [ ] Conventional Commit messages, lowercase imperative (`fix(send): round fee up`)
- [ ] Tests cover the new behaviour (amount and balance bugs always get one)
- [ ] User-visible strings are in both English and Italian resources
- [ ] No secrets, personal paths, real addresses or real amounts in code, tests or screenshots
- [ ] Docs / README updated if behaviour or setup changed
- [ ] No version bump

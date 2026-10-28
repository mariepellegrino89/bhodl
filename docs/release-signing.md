# Releases and code signing

Windows releases are built by [`release.yml`](../.github/workflows/release.yml) and
signed for free by [SignPath Foundation](https://signpath.org). The public policy is in
[`CODE_SIGNING_POLICY.md`](../CODE_SIGNING_POLICY.md); this page is the maintainer's
checklist.

## Cutting a release

1. Prepare a release commit (on a branch, merged through a pull request). It sets
   `packageVersion` in `apps/desktop/build.gradle.kts` and updates the changelog. This is
   the only commit that changes the version.
2. Tag `main` with `v<packageVersion>` and push the tag:
   ```sh
   git tag -s v0.1.0 -m "BHODL 0.1.0"   # or -a without a signing key
   git push origin v0.1.0
   ```
3. `release.yml` builds the MSI on `windows-latest` and, if SignPath is configured,
   submits it. Approve the signing request on SignPath within an hour.
4. The workflow creates a **draft** release with the MSI and `SHA256SUMS.txt`. Check
   the notes and publish it.

Run the workflow by hand (Actions → Release → Run workflow) for a dry run: it builds the
installer as an artifact and publishes nothing.

To build the same installer locally:

```sh
./gradlew :apps:desktop:packageMsi
# → apps/desktop/build/compose/binaries/main/msi/BHODL-<version>.msi
```

The installer shows `apps/desktop/packaging/installer-notice.txt` (license and privacy
summary) before installing. Keep it in line with [`PRIVACY.md`](../PRIVACY.md).

## SignPath Foundation: one-time setup

Conditions: [signpath.org/terms](https://signpath.org/terms).

1. **Apply** at [signpath.org/apply](https://signpath.org/apply). The project must
   already have a published release, so the first release (0.1.0) goes out unsigned.
   Link these from the application:
   - the repository;
   - the release page;
   - [`CODE_SIGNING_POLICY.md`](../CODE_SIGNING_POLICY.md);
   - [`PRIVACY.md`](../PRIVACY.md).
2. **Use MFA** on GitHub and on SignPath for every team member.
3. **Configure the project in SignPath** once accepted:
   - Project slug `bhodl`.
   - Connect a **trusted build system** for GitHub.com and link this repository.
   - Signing policy slug `release-signing`, with manual approval and the maintainers as
     approvers.
   - Artifact configuration slug `msi`, with the content below.
4. **Add to GitHub repo settings** → Secrets and variables → Actions:

   | Kind | Name | Value |
   | --- | --- | --- |
   | Secret | `SIGNPATH_API_TOKEN` | API token of a SignPath CI user that may submit to the policy |
   | Variable | `SIGNPATH_ORGANIZATION_ID` | SignPath organization ID |

   Variables `SIGNPATH_PROJECT_SLUG`, `SIGNPATH_POLICY_SLUG` and
   `SIGNPATH_ARTIFACT_CONFIGURATION_SLUG` override the default slugs above. Once
   `SIGNPATH_ORGANIZATION_ID` is set, every tag release is signed.

### Artifact configuration

The GitHub artifact is a zip holding the MSI. Signing covers:

- the MSI itself;
- the app launcher `BHODL.exe` inside it;
- `bhodl_uniffi.dll` (the Rust core) inside the shared jar.

The Java runtime in the MSI is upstream code and is left as its vendor shipped it.

```xml
<?xml version="1.0" encoding="utf-8"?>
<artifact-configuration xmlns="http://signpath.io/artifact-configuration/v1">
  <zip-file>
    <msi-file path="BHODL-*.msi" product-name="BHODL">
      <pe-file path="BHODL.exe" product-name="BHODL">
        <authenticode-sign/>
      </pe-file>
      <zip-file path="app/shared-jvm-*.jar">
        <pe-file path="win32-x86-64/bhodl_uniffi.dll">
          <authenticode-sign/>
        </pe-file>
      </zip-file>
      <authenticode-sign/>
    </msi-file>
  </zip-file>
</artifact-configuration>
```

Check the paths against a real MSI before the first signed release. SignPath's editor
validates the configuration against an uploaded sample, and the unsigned 0.1.0 MSI works
as that sample. The SignPath Foundation terms ask for enforced file metadata (product
name, same product version in every build), so add the restrictions the editor offers
for `BHODL.exe` and the DLL.

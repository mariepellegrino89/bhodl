# Code signing policy

Free code signing provided by [SignPath.io](https://signpath.io), certificate by
[SignPath Foundation](https://signpath.org).

## What is signed

Only the Windows installer (`BHODL-<version>.msi`) published on
[GitHub Releases](https://github.com/mariepellegrino89/bhodl/releases), and the BHODL
binaries inside it (the app launcher and `bhodl_uniffi.dll`, the Rust core). Third-party
files in the installer, such as the Java runtime, are not signed under this certificate.

## How a release is signed

Every signed file is built from this repository's source on GitHub Actions
([`release.yml`](.github/workflows/release.yml)), from a version tag on `main`. A
release goes through these steps:

1. The workflow builds the installer and submits it to SignPath.
2. A project approver checks the build and approves the signing request.
3. SignPath signs the files, and the workflow publishes them to the release.

Nothing built on a developer's machine is ever signed.

`main` accepts changes only through pull requests that pass CI.

## Team roles

| Role | Members |
| --- | --- |
| Committers and reviewers | [Owner of the repository](https://github.com/mariepellegrino89) |
| Approvers | [Owner of the repository](https://github.com/mariepellegrino89) |

Changes from anyone outside this list arrive as pull requests and are reviewed by a
committer before merging. Every team member uses multi-factor authentication on GitHub
and SignPath.

## Privacy

This program sends no information to other networked systems unless the user asks for it
or keeps a default it can turn off. The [privacy policy](PRIVACY.md) lists each server it
contacts and its policy, and the installer shows it before installing.

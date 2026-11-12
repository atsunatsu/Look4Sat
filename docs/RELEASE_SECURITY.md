# Release security configuration

This repository's release workflow keeps the APK signing key out of the Gradle build process. The APK is compiled without signing secrets, then a separate step signs it with `apksigner` and verifies the pinned certificate SHA-256 before any artifact can be published.

## One-time GitHub settings

These settings cannot be safely expressed by a repository file alone; configure them in GitHub before running the hardened workflow.

### 1. Protect the signing credentials

1. Open **Settings → Environments** and create an environment named `release`.
2. Add at least one trusted maintainer as a **Required reviewer**.
3. Leave **Prevent self-review** disabled if the same maintainer who starts a release must be able to approve it. If a second maintainer is available, prefer requiring that second person.
4. Under **Environment variables**, add `RELEASE_GUARD_ENABLED=true`. The workflow fails closed if this variable is absent.
5. Add these four secrets to the `release` environment:
   - `KEY_STORE`
   - `KEY_STORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
6. Confirm a protected test deployment can access the environment secrets, then delete the repository-level copies of those four secrets. Do not leave duplicate repository secrets: an unprotected job could otherwise read them.

Every signed workflow mode (`artifact`, `test`, and `release`) uses this environment and therefore waits for approval. The build and unit tests still run automatically after approval; no manual APK signing is required.

### 2. Protect `main`

Under **Settings → Rules → Rulesets** (or **Settings → Branches**), protect `main` with:

- Require a pull request before merging.
- Require the `USB CAT regression / android-usb-cat` status check to pass.
- Block force pushes and branch deletion.
- Do not require a commit signature unless the repository's commit-signing process is configured first.

For a single-maintainer repository, requiring a PR with zero mandatory human approvals is still useful: it prevents direct pushes while allowing the maintainer to merge their own reviewed changes.

### 3. Enable GitHub security alerts

Under **Settings → Code security and analysis**, enable Secret Scanning and Push Protection where available, plus Dependabot alerts. Review alerts before merging dependency updates.

## What the workflow now enforces

- Workflow-level token permission is read-only; only the guarded job that can sign and publish releases receives `contents: write`.
- Release runs are serialized to prevent concurrent publishing or tag replacement.
- Official releases can only be published from `main`.
- Gradle runs without the signing keystore or passwords.
- A separate step creates the temporary keystore under the runner temp directory, signs the APK, and deletes temporary key material on exit.
- The existing release certificate SHA-256 check remains mandatory.
- Official Release assets include a SHA-256 checksum and a build metadata file containing the source commit and Actions run URL.
- The release workflow uses no third-party Marketplace Actions; the artifact uploader is a repository-local action.

The checksum and metadata make a release easier to audit, but they are not a cryptographic SLSA/GitHub artifact attestation. Do not describe them as one.

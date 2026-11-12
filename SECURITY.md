# Security Policy

## Supported versions

Security fixes are provided for the latest published Look4Sat release and the current `main` branch.

## Reporting a vulnerability

Please do not publish credentials, private keys, personal data, or an exploitable vulnerability in a public issue. Use GitHub's private vulnerability reporting for this repository when available:

https://github.com/atsunatsu/Look4Sat/security/advisories/new

If private reporting is unavailable, contact the maintainer through the GitHub profile and request a private channel.

## Release security

The release workflow is protected by the GitHub Environment named `release`. Its signing credentials must be stored as environment secrets, not repository secrets. See [docs/RELEASE_SECURITY.md](docs/RELEASE_SECURITY.md) for the one-time repository configuration.

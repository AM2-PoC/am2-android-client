# Changelog

Notable user-facing changes are recorded here. This project follows [Semantic Versioning](https://semver.org/).

## Unreleased

### Fixed

- The update screen follows the current session identity instead of retaining a disconnected snapshot.
- Foreground-service startup failures are reported instead of being silently discarded.
- The map once again shows a unit's speaking state.

## 1.3.0 - 2026-09-17

### Fixed

- Stationary units refresh their location timestamp so the map does not mark them stale.
- The production update manifest is generated from the exact signed APK it describes.

## 1.2.0 - 2026-09-01

### Added

- Durable revocable device sessions and Android/API metadata for compatibility evidence.

### Fixed

- Logout returns to sign-in, stops the PTT service, and clears reconnect notifications.
- Update checks explain refusals and retry interrupted downloads.

# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project uses [unified versioning](README.md#versioning): every module is
released at the same version number.

## [Unreleased]

## [0.2.0] - 2026-09-05

### Fixed

- **router:** bidi vector-of-pairs route tables now compile correctly — 0.1.0 silently
  corrupted them (entries dropped, component fns leaked into the table). Malformed tables
  now throw at `register-routes` time with the offending form in the message.
- **form:** `get-form-display-error` returns the error value (0.1.0 returned a reaction the
  caller had to deref — the one bound getter that behaved differently).
- **form:** an `on-submit` that throws, or a wrong submission result, no longer leaves the
  form stuck with `:is-submitting` true — both become a failed submission.
- **form:** the `create-form` error path referenced `cljs.pprint` without requiring it.
- **http-api:** path parameters are percent-encoded, and a param name that is a prefix of
  another (`:id` / `:idx`) can no longer corrupt the substitution.
- **http-api:** a failed `execute` no longer wipes the endpoint's last good data — the
  reaction / re-frame slot keeps `:data`, gains `:error`, and flips `:success?`.

### Changed

- **form:** `register-field` no longer defaults `:placeholder` to `"Enter"` — no
  placeholder unless you pass one.
- **form:** `:initial-values` accepts anything derefable (plain atoms included, not just
  reagent types); submission results may be any core.async read port, not only a channel.
- **http-api:** the extra SSE event names delivered to `:on-message` are configurable via
  `:events` in `subscribe` opts (default `["changed"]`, the previous hardcoded name).
- Registries (`forms`, `api-specs`, route tables) survive hot reload (`defonce`).

### Added

- **router:** `bangmod.router.table` — the pure route-compilation half, unit-tested,
  including the shapes 0.1.0 miscompiled.
- **http-api:** `:with-credentials` endpoint option documented; `defapi` rejects the
  reserved endpoint name `:_options`; `get-data-reaction` throws on an undeclared endpoint
  instead of returning nil.
- `:lib-check` shadow-cljs build compiling every public namespace in CI — the `:test` build
  alone never compiles the reagent-dependent namespaces.

## [0.1.0] - 2026-09-03

### Added

- Initial extraction of `reagent-form`, `reagent-http-api` and `reagent-router`
  into a three-module repo.

[Unreleased]: https://github.com/bangmodcloud/reagent-toolkit/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/bangmodcloud/reagent-toolkit/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/bangmodcloud/reagent-toolkit/releases/tag/v0.1.0

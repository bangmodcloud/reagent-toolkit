# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project uses [unified versioning](README.md#versioning): every module is
released at the same version number.

## [Unreleased]

## [0.4.0] - 2026-09-16

### Added

- **form:** `bangmod.form.core/create-form-submission` — a macro that builds the
  `:on-submit` handler: `(create-form-submission form [form values dispatch] body...)`. The
  body reports the outcome by calling `dispatch` exactly once with `nil` (success), a string
  (the form error) or a promise of either. Anything else — another value, a second call,
  never dispatching, a rejected promise, a throwing body — marks the form failed with the
  error's message and throws. This is now the documented way to submit; `api/handle-submit`
  and the `create-*-submission-result` helpers remain as the lower-level path.
- **form:** `api/start-submission` — the validate-and-mark-submitting gate both submit paths
  share; returns the values map, or `nil` when it refused.
- **http-api:** `set-auth-token-injector!` — `(fn [request token] -> request)` deciding where
  the provider's token goes on a request. The default, `bangmod.http-api.auth/bearer-injector`,
  is the previous behaviour (`Authorization: Bearer`, explicit header wins); `nil` restores it.

### Changed

- **form:** docs and the Claude skill now present `bangmod.form.api` (form as first
  argument) as the one way to call a form.

### Removed

- **form:** `bangmod.form.core/make-api`. It was a map of `bangmod.form.api` functions with
  the form pre-bound; call those directly — `(api/register-field form :email {...})` — and
  build the submit handler with `create-form-submission`.

### Fixed

- **form:** `bangmod.form.core/get-form` now exists — the docs already referred to it, but
  it was only reachable as `bangmod.form.form/get-form`.

## [0.3.0] - 2026-09-15

### Changed

- **http-api:** `execute` now fires the request and returns the endpoint's reaction
  (`raw-execute` + `get-data-reaction`), so a component can `(let [x (execute :api :ep)] ...)`
  and deref `x` in its render fn. The previous channel-returning `execute` is renamed
  `raw-execute` — replace `(a/<! (execute ...))` with `(a/<! (raw-execute ...))`.
- **http-api:** `execute` on an `:sse` endpoint opens the stream (as `subscribe`) and returns
  the subscription handle; that handle now derefs to the stream's latest state, whose latest
  frame also lands under `:data` (alongside `:last-message`), so `(:data @x)` reads the same
  for a request and a stream. `unsubscribe!` is a no-op on anything that isn't a
  subscription, so a `with-let` `finally` can close whatever `execute` returned.

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

[Unreleased]: https://github.com/bangmodcloud/reagent-toolkit/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/bangmodcloud/reagent-toolkit/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/bangmodcloud/reagent-toolkit/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/bangmodcloud/reagent-toolkit/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/bangmodcloud/reagent-toolkit/releases/tag/v0.1.0

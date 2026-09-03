# reagent-toolkit

Three small, independent ClojureScript libraries for [Reagent](https://reagent-project.github.io/)
/ [re-frame](https://day8.github.io/re-frame/) front-ends. They live in one repo and
release together, but ship as separate artifacts so you only pull in what you use.

| Artifact                             | Namespaces             | What it does                                                 | Depends on                                   |
| ------------------------------------ | ---------------------- | ------------------------------------------------------------ | -------------------------------------------- |
| `io.github.bangmod/reagent-form`     | `bangmod.form.*`       | Form state, validation, field arrays and field groups         | reagent, core.async                          |
| `io.github.bangmod/reagent-http-api` | `bangmod.http-api.*`   | Declarative HTTP + SSE API client, with re-frame integration  | reagent, re-frame, cljs-ajax, core.async     |
| `io.github.bangmod/reagent-router`   | `bangmod.router.*`     | bidi + pushy routing for single-page apps                     | reagent, re-frame, bidi, pushy, cemerick/url |

The three modules do not depend on each other — take `reagent-router` without dragging in
form handling or an HTTP stack.

## Installation

### From Clojars

```clojure
;; deps.edn
{:deps {io.github.bangmod/reagent-form     {:mvn/version "0.1.0"}
        io.github.bangmod/reagent-http-api {:mvn/version "0.1.0"}
        io.github.bangmod/reagent-router   {:mvn/version "0.1.0"}}}
```

### As a git dependency

`:deps/root` points tools.deps at a subdirectory. Note the **explicit `:git/url`**: without
it, tools.deps would infer `github.com/bangmod/reagent-router` from the library name, which
is not where this code lives — and the explicit url is also what lets two modules from the
same repo coexist in one dependency map.

```clojure
{:deps {io.github.bangmod/reagent-router
        {:git/url   "https://github.com/bangmodcloud/reagent-toolkit.git"
         :git/tag   "v0.1.0"
         :git/sha   "<sha>"          ; first 7 chars of the tagged commit
         :deps/root "modules/reagent-router"}}}
```

## Repo layout

```
reagent-toolkit/
├── deps.edn                        # root: modules wired via :local/root, plus dev/test/build aliases
├── build.clj                       # tools.build — jar / install / deploy, one module or all
├── shadow-cljs.edn                 # :node-test build
├── modules/
│   ├── reagent-form/
│   │   ├── deps.edn                # this module's own dependencies
│   │   └── src/bangmod/form/
│   ├── reagent-http-api/
│   │   ├── deps.edn
│   │   └── src/bangmod/http_api/
│   └── reagent-router/
│       ├── deps.edn
│       └── src/bangmod/router/
└── test/                           # tests for all modules (root classpath sees all three)
```

The directory name under `modules/` is the artifact name; the namespaces inside stay
`bangmod.form` / `bangmod.http-api` / `bangmod.router`.

Each `modules/*/deps.edn` declares only that module's dependencies — that file *is* the
published artifact's dependency list, so keeping it tight is what keeps consumers' builds
lean. The root `deps.edn` wires all three in with `:local/root`, so a REPL or test run
sees your edits with no install step in between.

## Development

```bash
# REPL with all three modules + ClojureScript on the classpath
clj -M:dev

# run the tests
clj -M:test -m shadow.cljs.devtools.cli compile test && node target/node-tests.js
```

The test build is `:node-test`, so it can only load namespaces that are free of browser
globals — `cljs-ajax` touches `js/XMLHttpRequest` and `pushy` touches `js/window` at load
time. That is why the decision logic of `reagent-http-api` lives in `retry.cljc` and
`sse.cljc`, apart from the transport in `internal.cljs`: the policy stays unit-testable.
Code that genuinely needs a DOM wants a `:browser-test` build instead.

## Versioning

All three modules share one version number. Releasing `v0.2.0` publishes `reagent-form`,
`reagent-http-api` and `reagent-router` as `0.2.0` together, even if only one of them
changed. There is no compatibility matrix to keep in your head — same number, works together.

## Building and releasing

```bash
clj -T:build clean
clj -T:build jar-all                                 # target/*.jar
clj -T:build install-all                             # into ~/.m2, for testing against a real app
clj -T:build jar :module '"reagent-router"'          # just one module

clj -T:build deploy-all :version '"0.1.0"'           # to Clojars
```

`deploy` reads `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` (a
[deploy token](https://github.com/clojars/clojars-web/wiki/Deploy-Tokens), not your
password) from the environment. The version defaults to `0.1.0-SNAPSHOT`, is overridden by
the `RELEASE_VERSION` env var, and an explicit `:version` argument wins over both.

To cut a release: bump the changelog, tag `vX.Y.Z`, and push the tag — the release
workflow builds and deploys all three modules.

> **Before the first deploy:** an `io.github.*` group is verified on Clojars by proving
> ownership of the GitHub account of the same name — so `io.github.bangmod` needs the
> `bangmod` account, even though this repo lives under `bangmodcloud`. If only
> `bangmodcloud` is available, change `group` in `build.clj` to `io.github.bangmodcloud`.
> See [Verified group names](https://github.com/clojars/clojars-web/wiki/Verified-Group-Names).

## License

MIT © 2026 bangmod. See [LICENSE](LICENSE).

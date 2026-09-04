# reagent-toolkit

Three small, independent ClojureScript libraries for [Reagent](https://reagent-project.github.io/)
/ [re-frame](https://day8.github.io/re-frame/) front-ends, extracted from a production
monorepo where they lived as `bangmod.router`, `bangmod.form` and `bangmod.http-api`. They
release together from one repo but ship as separate artifacts, so you only pull in what you
use — take `reagent-router` without dragging in a form library or an HTTP client.

| Artifact                                  | Namespace            | What it does                                                 | Docs                                 |
| ------------------------------------------ | -------------------- | ------------------------------------------------------------ | ------------------------------------- |
| `io.github.bangmodcloud/reagent-form`     | `bangmod.form.*`     | Form state, validation, field arrays and field groups        | [docs/form.md](docs/form.md)         |
| `io.github.bangmodcloud/reagent-http-api` | `bangmod.http-api.*` | Declarative HTTP + SSE client, GET and live-stream on one URI | [docs/http-api.md](docs/http-api.md) |
| `io.github.bangmodcloud/reagent-router`   | `bangmod.router.*`   | bidi + pushy routing, each feature registers its own routes   | [docs/router.md](docs/router.md)     |

Each doc covers its module on its own: a quick start, the full API, a real-world example, and
the gotchas worth knowing before you rely on it.

## 30-second tour

```clojure
;; form: register-field returns ready-to-spread input props
(let [{:keys [register-field handle-submit get-field-display-error]}
      (form/make-api (form/create-form :login))]
  [:form {:on-submit (handle-submit on-submit-fn)}
   [:input (register-field :email {:type "email" :validators [v/required]})]
   (when-let [err (get-field-display-error :email)] [:span.error err])])

;; http-api: a GET and a live SSE subscription can share one URI
(defapi :account {:base-url "https://api.example.com"}
  {:get     {:method :get :uri "/api/account/me" :response-format :json}
   :changes {:method :sse :uri "/api/account/me"}})
(http-api/execute :account :get)
(http-api/subscribe :account :changes {:on-message #(rf/dispatch [:account/update %])})

;; router: each feature registers its own routes; one panel renders whichever matched
(router/register-routes ["" {"/dashboard" [:dashboard dashboard-view]}])
[router-views/matched-route-panel]
```

## Installation

### From Clojars

```clojure
{:deps {io.github.bangmodcloud/reagent-form     {:mvn/version "0.2.0"}
        io.github.bangmodcloud/reagent-http-api {:mvn/version "0.2.0"}
        io.github.bangmodcloud/reagent-router   {:mvn/version "0.2.0"}}}
```

### As a git dependency

`:deps/root` points tools.deps at a subdirectory. The explicit `:git/url` matters: without
it, tools.deps would infer `github.com/bangmodcloud/reagent-router` from the library name,
which isn't where this code lives, and it's also what lets two modules from the same repo
coexist in one dependency map.

```clojure
{:deps {io.github.bangmodcloud/reagent-router
        {:git/url   "https://github.com/bangmodcloud/reagent-toolkit.git"
         :git/tag   "v0.2.0"
         :git/sha   "<sha>"
         :deps/root "modules/reagent-router"}}}
```

## Design

- **Unified versioning** — all three modules release at the same version number, so there's
  no compatibility matrix to remember.
- **No cross-dependencies** — the modules don't depend on each other.
- **Each `modules/*/deps.edn` is the published dependency list** for that artifact — kept
  tight on purpose, since it's exactly what a consumer inherits.

## Repo layout

```
reagent-toolkit/
├── deps.edn              # root: modules wired via :local/root, plus dev/test/build aliases
├── build.clj              # tools.build — jar / install / deploy, one module or all
├── shadow-cljs.edn        # :node-test build
├── docs/                  # per-module docs
│   ├── form.md
│   ├── http-api.md
│   └── router.md
├── modules/
│   ├── reagent-form/{deps.edn, src/bangmod/form/}
│   ├── reagent-http-api/{deps.edn, src/bangmod/http_api/}
│   └── reagent-router/{deps.edn, src/bangmod/router/}
└── test/                  # tests for all modules (root classpath sees all three)
```

## Development

```bash
clj -M:dev    # REPL with all three modules + ClojureScript on the classpath

clj -M:test -m shadow.cljs.devtools.cli compile test && node target/node-tests.js

clj -M:test -m shadow.cljs.devtools.cli compile lib-check   # compile every public namespace
```

The test build is `:node-test`, so it only loads namespaces free of browser globals —
`cljs-ajax` and `pushy` touch `js/XMLHttpRequest`/`js/window` at load time, which is why
`reagent-http-api`'s decision logic lives in `retry.cljc`/`sse.cljc`, apart from the
transport in `internal.cljs`.

## Building and releasing

```bash
clj -T:build clean
clj -T:build jar-all                                 # target/*.jar
clj -T:build install-all                             # into ~/.m2
clj -T:build jar :module '"reagent-router"'          # just one module
clj -T:build deploy-all :version '"0.2.0"'           # to Clojars
```

`deploy` reads `CLOJARS_USERNAME`/`CLOJARS_PASSWORD` (a
[deploy token](https://github.com/clojars/clojars-web/wiki/Deploy-Tokens)) from the
environment. To cut a release: bump the changelog, tag `vX.Y.Z`, push the tag — the release
workflow builds and deploys all three modules.

> The `io.github.bangmodcloud` group must be [verified on
> Clojars](https://github.com/clojars/clojars-web/wiki/Verified-Group-Names) (proving
> ownership of the `bangmodcloud` GitHub org) before the first deploy will be accepted.

## License

MIT © 2026 bangmodcloud. See [LICENSE](LICENSE).

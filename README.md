# reagent-toolkit

[![CI](https://github.com/bangmodcloud/reagent-toolkit/actions/workflows/ci.yml/badge.svg)](https://github.com/bangmodcloud/reagent-toolkit/actions)
[![Clojars Project](https://img.shields.io/clojars/v/io.github.bangmodcloud/reagent-form.svg?color=blue)](https://clojars.org/io.github.bangmodcloud/reagent-form)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![ClojureScript](https://img.shields.io/badge/ClojureScript-1.11+-purple.svg)](https://clojurescript.org)

Three lightweight, modular ClojureScript libraries for [Reagent](https://reagent-project.github.io/) and [re-frame](https://day8.github.io/re-frame/) applications. 

Extracted directly from production monorepos, these libraries solve the three most common front-end concerns without forcing you into an all-in-one framework:

* 📝 **`reagent-form`** — Form state, automatic input prop spreading, validation, and field arrays.
* 🌐 **`reagent-http-api`** — Declarative REST & SSE client where a `GET` query and a live Server-Sent Events stream can share the exact same URI.
* 🧭 **`reagent-router`** — Feature-first routing (bidi + pushy) where each feature module registers its own routes independently.

> **Zero Bloat & Zero Cross-Dependencies:** Take `reagent-router` without pulling in an HTTP client or form handler. Each artifact is completely standalone.

---

## The Modules at a Glance

| Artifact | Namespace | Solves | Deep Dive |
| :--- | :--- | :--- | :--- |
| **`io.github.bangmodcloud/reagent-form`** | `bangmod.form.*` | Eliminates form state boilerplate. Produces ready-to-spread props for `[:input ...]` and handles sync/async validation. | [📖 Form Docs](docs/form.md) |
| **`io.github.bangmodcloud/reagent-http-api`** | `bangmod.http-api.*` | Declarative endpoints with automatic Bearer token injection, single-flight token refresh, and dual GET/SSE streaming on one URI. | [📖 HTTP/SSE Docs](docs/http-api.md) |
| **`io.github.bangmodcloud/reagent-router`** | `bangmod.router.*` | Decentralized routing. Feature modules own their routes. Reactive URL params and built-in route health checking (`registration-report`). | [📖 Router Docs](docs/router.md) |

---

## 30-Second Tour

### 1. Forms without event-wiring boilerplate
Spread `register-field` directly onto inputs. Validation and touched state are handled automatically:

```clojure
(let [login-form (form/create-form :login)
      {:keys [register-field handle-submit get-field-display-error]} (form/make-api login-form)]
  [:form {:on-submit (handle-submit on-submit-fn)}
   [:input (register-field :email {:type "email" :validators [v/required]})]
   (when-let [err (get-field-display-error :email)]
     [:span.error err])
   [:button {:type "submit"} "Log in"]])
```

### 2. HTTP & Real-time SSE on the same URI
Fetch an initial snapshot with `execute`, then seamlessly stream live updates with `subscribe` using the exact same backend endpoint:

```clojure
(defapi :account {:base-url "https://api.example.com"}
  {:get     {:method :get :uri "/api/account/me" :response-format :json}
   :changes {:method :sse :uri "/api/account/me"}})

;; 1. One-off request
(http-api/execute :account :get)

;; 2. Live stream (auto-reconnects, auto-injected auth token)
(http-api/subscribe :account :changes
  {:on-message (fn [data] (rf/dispatch [:account/update data]))})
```

### 3. Decentralized, Feature-First Routing
Features declare their own routes; the router aggregates them and provides a reactive panel to render the active page:

```clojure
;; In your feature module:
(router/register-routes ["" {"/dashboard" [:dashboard dashboard-view]}])

;; In your root view:
[:div.app-shell
 [router-views/matched-route-panel]]
```

---

## Installation

### From Clojars (`deps.edn`)

Choose only the modules your app needs:

```clojure
{:deps {io.github.bangmodcloud/reagent-form     {:mvn/version "0.1.0"}
        io.github.bangmodcloud/reagent-http-api {:mvn/version "0.1.0"}
        io.github.bangmodcloud/reagent-router   {:mvn/version "0.1.0"}}}
```

### As a Git Dependency

If you want to track `main` or pin to a specific git commit, use an explicit `:git/url` and `:deps/root`:

```clojure
{:deps {io.github.bangmodcloud/reagent-router
        {:git/url   "https://github.com/bangmodcloud/reagent-toolkit.git"
         :git/tag   "v0.1.0"
         :git/sha   "..." 
         :deps/root "modules/reagent-router"}}}
```

---

## Design Principles

1. **Unified Versioning:** All modules share the exact same version number (e.g. `v0.1.0`). There is no compatibility matrix to remember—if they have the same version, they work together.
2. **Decoupled by Default:** Modules do not depend on each other. If you only want a router, you never pull in `cljs-ajax` or form code.
3. **Production Hardened:** Designed around real frontend challenges—silent error suppression while submitting, single-flight token renewal on 401s, and compile-time route audits to avoid 404 white-screens.

---

## Repository Layout

```
reagent-toolkit/
├── deps.edn                        # Root workspace wiring modules via :local/root
├── build.clj                       # tools.build — jar, install, deploy
├── shadow-cljs.edn                 # Test build runner (:node-test)
├── docs/                           # In-depth per-module guides
│   ├── form.md                     # Form validation, API reference, field arrays
│   ├── http-api.md                 # REST, SSE streaming, token refresh
│   └── router.md                   # Routing, bidi syntax, route auditing
├── modules/
│   ├── reagent-form/               # Form state & validation
│   ├── reagent-http-api/           # REST + SSE HTTP client
│   └── reagent-router/             # Pushy + Bidi SPA router
└── test/                           # Test suite across all modules
```

---

## Development

```bash
# Start a REPL with all modules on the classpath
clj -M:dev

# Run automated tests via shadow-cljs and Node.js
clj -M:test -m shadow.cljs.devtools.cli compile test && node target/node-tests.js
```

---

## Building and Releasing

Build artifacts locally:

```bash
# Clean previous builds
clj -T:build clean

# Build jars for all modules into target/
clj -T:build jar-all

# Install into local ~/.m2 (great for testing with your actual app)
clj -T:build install-all

# Deploy all modules to Clojars
clj -T:build deploy-all :version '"0.1.0"'
```

Deployment credentials are read from `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` (Deploy Token).

---

## License

Distributed under the MIT License. See [LICENSE](LICENSE) for details.

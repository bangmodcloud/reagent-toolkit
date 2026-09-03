# reagent-router

[![Clojars Project](https://img.shields.io/clojars/v/io.github.bangmodcloud/reagent-router.svg?color=blue)](https://clojars.org/io.github.bangmodcloud/reagent-router)

A modular, feature-first routing library for [Reagent](https://reagent-project.github.io/) and [re-frame](https://day8.github.io/re-frame/) powered by [bidi](https://github.com/juxt/bidi) and [pushy](https://github.com/kibu-oss/pushy).

---

## Why reagent-router?

Most SPA routers force you to maintain a single, monolithic routing file where every route and page component across the entire app must be imported. In large codebases, this creates tight coupling and merge conflicts.

`reagent-router` takes a **feature-first** approach:

* 🧩 **Decentralized Routes:** Every feature module defines and registers its own route table independently.
* 🖼️ **Zero-Boilerplate Rendering:** Just place `[router-views/matched-route-panel]` in your layout. No manual `case` or `condp` switching required.
* ⚡ **Reactive Parameters:** Read current path parameters and query strings directly through Reagent reactions (`atom-params`, `atom-query-params`).
* 🩺 **Built-in Route Auditing (`registration-report`):** Catch blank-screen bugs before your users do. Detects orphan routes, dead components, and duplicates automatically.

---

## Installation

Add to your `deps.edn`:

```clojure
io.github.bangmodcloud/reagent-router {:mvn/version "0.1.0"}
```

---

## Quick Start

### 1. Define Features & Views

```clojure
;; feature/home/view.cljs
(ns myapp.feature.home.view)

(defn home-page []
  [:div.page
   [:h1 "Welcome Home"]])

;; feature/home/routes.cljs
(ns myapp.feature.home.routes
  (:require [myapp.feature.home.view :as view]))

(def routes
  ["" {"/" [:home view/home-page]}])
```

### 2. Define a 404 Fallback View

```clojure
;; feature/not_found/view.cljs
(ns myapp.feature.not-found.view)

(defn not-found-page []
  [:div.page
   [:h1 "404 - Page Not Found"]])
```

### 3. Mount the Router in Your App Root

```clojure
;; core.cljs
(ns myapp.core
  (:require [reagent.dom :as rdom]
            [bangmod.router.core :as router]
            [bangmod.router.views :as router-views]
            [myapp.feature.home.routes :as home-routes]
            [myapp.feature.not-found.view :as not-found]))

(defn app-shell []
  [:div.app-container
   [:nav
    [:a {:href (router/url-for :home)} "Home"]]
   [:main
    ;; Automatically renders the component bound to the current route:
    [router-views/matched-route-panel]]])

(defn init []
  ;; 1. Register routes from any number of features (in any order)
  (router/register-routes home-routes/routes)

  ;; 2. Start the router with a fallback component
  (router/start! {:default-component not-found/not-found-page})

  ;; 3. Mount Reagent root
  (rdom/render [app-shell] (.getElementById js/document "app")))
```

---

## Multi-Module Architecture: Scaling to 15+ Features

In production SPAs (such as cloud management consoles, ERPs, or dashboards), having 15–20+ distinct features (`account`, `voucher`, `hosting`, `network`, `billing`, etc.) is standard.

### The Problem with Centralized Routing
With conventional routers, all routes are dumped into a single `routes.cljs` file:
* 💥 **Git Merge Conflicts:** Five developers working on different features simultaneously modify the same central routing file.
* 🍝 **Spaghetti Imports:** The root router imports hundreds of view namespaces from every corner of the codebase.
* ❌ **Fragile Encapsulation:** Extracting, renaming, or deleting a feature requires surgical edits across central routing and view files.

### The `reagent-router` Feature-First Solution
Each feature folder is fully self-contained. It owns its views, events, and route table:

```
src/myapp/feature/
├── account/
│   ├── routes.cljs   # ["/account" {"/admin" ... "/customer" ...}]
│   ├── view/
│   └── core.cljs     # (defn init [] (router/register-routes routes/account-routes))
├── voucher/
│   ├── routes.cljs   # ["/voucher" {"" ... "/new" ...}]
│   ├── view/
│   └── core.cljs     # (defn init [] (router/register-routes routes/voucher-routes))
├── hosting/
│   ├── routes.cljs   # ["/hosting" {"/servers" ... "/packages" ...}]
│   └── core.cljs
└── billing/
    ├── routes.cljs   # ["/billing" ...]
    └── core.cljs
```

#### 1. Inside a Feature: Define its own routes
```clojure
;; myapp/feature/voucher/routes.cljs
(ns myapp.feature.voucher.routes
  (:require [myapp.feature.voucher.view.listing :as listing-page]
            [myapp.feature.voucher.view.new :as new-voucher-page]
            [myapp.feature.voucher.view.detail :as detail-page]))

(def voucher-routes
  ["/voucher" {""        [:voucher-listing listing-page/component]
               "/new"    [:new-voucher new-voucher-page/component]
               ["/" :id] [:voucher-detail detail-page/component]}])
```

#### 2. Inside the Feature Entrypoint: Register its routes
```clojure
;; myapp/feature/voucher/core.cljs
(ns myapp.feature.voucher.core
  (:require [bangmod.router.core :as router]
            [myapp.feature.voucher.routes :as routes]))

(defn init []
  (router/register-routes routes/voucher-routes))
```

#### 3. In the App Core: Wire features together cleanly
```clojure
;; myapp/core.cljs
(ns myapp.core
  (:require [bangmod.router.core :as router]
            [bangmod.router.views :as router-views]
            [myapp.feature.account.core :as account-feature]
            [myapp.feature.voucher.core :as voucher-feature]
            [myapp.feature.hosting.core :as hosting-feature]
            [myapp.feature.billing.core :as billing-feature]))

(defn init []
  ;; ===== Initialize all features independently
  (account-feature/init)
  (voucher-feature/init)
  (hosting-feature/init)
  (billing-feature/init)

  ;; ===== Start router once (aggregates all registered feature routes)
  (router/start! {:default-component not-found-page}))

(defn root-layout []
  [:div.app-shell
   [sidebar-navigation]
   [:main.content-area
    ;; One line renders the active component of whichever feature matched:
    [router-views/matched-route-panel]]])
```

### Why this is a game-changer:
1. **Zero Merge Conflicts:** Team A working on `voucher` and Team B working on `hosting` never touch the same files.
2. **Conditional / Feature Toggling:** Need to disable a feature for certain user roles or tenants? Simply don't call `(feature/init)` at boot.
3. **Effortless Code Removal:** To delete a feature, delete its folder and remove one `init` call in `myapp.core`. No dangling route references left behind.

---

## Route Syntax

`reagent-router` uses standard [bidi](https://github.com/juxt/bidi) data structures, with one key enhancement: the leaf node is a vector of `[handler-keyword component]` instead of a bare keyword.

```clojure
(def routes
  ["" [;; Static route
       ["/" [:home home-view]]
       ;; Path parameter route (:id is extracted into atom-params)
       [["/projects/" :id] [:project-detail project-view]]
       ;; Nested route prefix
       ["/settings"
        {"/profile" [:settings-profile profile-view]
         "/billing" [:settings-billing billing-view]}]]])
```

---

## Navigation & URL Generation

### Programmatic Navigation (`navigate!`)
Push a new URL to HTML5 history and trigger view re-rendering:

```clojure
;; Navigate by route keyword
(router/navigate! :home)

;; Or by literal path
(router/navigate! "/settings/profile")
```

### URL Generation (`url-for`)
Generates safe, reverse-routed URL strings:

```clojure
;; Path parameter substitution:
(router/url-for :project-detail :id 42)
;; => "/projects/42"

;; Adding query parameters:
(router/url-for :project-detail :id 42 {:query {:tab "logs" :sort "asc"}})
;; => "/projects/42?tab=logs&sort=asc"
```

---

## Reactive Route State

Access route information reactively inside any Reagent component without passing props down:

```clojure
(ns myapp.feature.project.view
  (:require [bangmod.router.core :as router]))

(defn project-view []
  (let [{:keys [id]} @router/atom-params
        {:keys [tab]} @router/atom-query-params
        current-route @router/atom-matched-route]
    [:div
     [:h2 "Project ID: " id]
     [:p "Active Tab: " (or tab "overview")]
     [:small "Matched Handler: " (str current-route)]]))
```

* `router/atom-matched-route` — Keyword of currently active route (or `:default`).
* `router/atom-params` — Map of path parameters (e.g. `{:id "42"}`).
* `router/atom-query-params` — Map of query parameters parsed from the URL.

---

## Killer Feature: Route Health Check (`registration-report`)

In single-page apps, typos between route keys and components can lead to silent dead ends. `registration-report` audits your entire compiled routing table:

```clojure
(router/registration-report)
```

**Output:**
```clojure
{:routed               [:home :project-detail :settings-profile]
 :registered           [:home :project-detail :settings-profile :old-dashboard]
 :duplicates           []
 :orphan-routes        []
 :orphan-registrations [:old-dashboard]}
```

| Field | Meaning | Impact |
| :--- | :--- | :--- |
| `:routed` | All route handlers recognized in route tables. | — |
| `:registered` | All handlers that have a mounted component. | — |
| `:duplicates` | Handlers declared more than once. | Second registration silently shadows the first. |
| `:orphan-routes` | Routes in the table that have **no component**. | **User sees a blank/fallback page!** |
| `:orphan-registrations` | Components registered for routes that don't exist. | Dead code. |

> [!TIP]
> **Dev Sanity Check:** Wire `(router/registration-report)` into a development assertion or test step after all features initialize to guarantee that no orphan routes exist in your app.

---

## API Reference

### Core Routing (`bangmod.router.core`)

| Function / Var | Signature | Description |
| :--- | :--- | :--- |
| `start!` | `[{:keys [default-component]}]` | Initializes HTML5 history listener and registers 404 fallback. Call once at boot. |
| `register-routes` | `[routes]` | Merges a bidi route table into the application route hierarchy. |
| `url-for` | `[handler & args]` | Generates path strings with path param substitution and optional `{:query {...}}`. |
| `navigate!` | `[handler-or-url]` | Pushes new browser history state and renders corresponding component. |
| `registration-report`| `[]` | Diagnostic report identifying duplicate, orphan, or missing routes. |
| `atom-matched-route` | Reagent reaction | Reactive keyword of active route (defaults to `:default`). |
| `atom-params` | Reagent reaction | Reactive map of matched path params. |
| `atom-query-params` | Reagent reaction | Reactive map of current query string params. |

### Views (`bangmod.router.views`)

| Component | Description |
| :--- | :--- |
| `[matched-route-panel]` | Reagent component that dynamically mounts whichever view component corresponds to the active route. |

---

## Gotchas & Pro-Tips

> [!CAUTION]
> **Never call `navigate!` inside a render function**
> Triggering `navigate!` during the render pass will alter the routing state while React is still rendering, which can unmount the component mid-render. Always call `navigate!` inside lifecycle callbacks (`component-did-mount`, `component-did-update`) or event handlers (clicks, button presses).

> [!IMPORTANT]
> **Order of Initialization**
> All features should call `(router/register-routes ...)` **before** calling `(router/start! ...)`. Any routes registered after `start!` will still match on subsequent URL changes, but will not be reflected on initial page load.

> [!NOTE]
> **Duplicate Route Handling in Dev vs Prod**
> If two components are accidentally registered under the exact same route keyword:
> - In development (`goog.DEBUG = true`): Throws an explicit exception immediately with the offending keyword.
> - In production: Logs a warning to the console and renders the latest registered component, avoiding white-screen crashes for end users.

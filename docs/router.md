# reagent-router

`io.github.bangmodcloud/reagent-router` — namespace `bangmod.router.*`

A thin wrapper around [bidi](https://github.com/juxt/bidi) (route matching) and
[pushy](https://github.com/kibu-oss/pushy) (HTML5 history) for Reagent/re-frame SPAs. Each
feature registers its own route table independently — no central routes file every feature
has to touch — and one component renders whichever route is currently active.

## Install

See the [root README](../README.md#installation) for `deps.edn` / git-dependency snippets.

## Quick start

```clojure
(ns myapp.feature.home.view)

(defn home-page []
  [:div "Home"])
```

```clojure
(ns myapp.feature.not-found.view)

(defn not-found-panel []
  [:div "No component found for this route."])
```

```clojure
(ns myapp.feature.home.routes
  (:require [myapp.feature.home.view :as view]))

(def routes
  ["" {"/" [:home view/home-page]}])
```

```clojure
(ns myapp.core
  (:require [reagent.dom :as rdom]
            [bangmod.router.core :as router]
            [bangmod.router.views :as router-views]
            [myapp.feature.home.routes :as home-routes]
            [myapp.feature.not-found.view :as not-found]))

(defn root []
  [:div [router-views/matched-route-panel]])

(defn init []
  ;; Each feature registers its own table; call this once per feature, in any order.
  (router/register-routes home-routes/routes)
  ;; Call once, after every feature has registered. Unmatched URLs render :default-component.
  (router/start! {:default-component not-found/not-found-panel})
  (rdom/render [root] (.getElementById js/document "app")))
```

## Route syntax

[bidi](https://github.com/juxt/bidi) route tables, with one change: each leaf is
`[handler-keyword component]` instead of bidi's bare handler keyword. Map tables and
vector-of-pairs tables both work; path-parameter patterns go in the pattern position
(a map key, or the first element of a pair):

```clojure
["" {"/" [:home home-view]
     ["/projects/" :id] [:project-detail project-view]   ; :id lands in atom-params
     "/settings" {"/profile" [:settings-profile profile-view]
                  "/billing" [:settings-billing billing-view]}}]
```

A malformed table — a leaf without a component, a route that isn't a `[pattern matched]`
pair — throws at `register-routes` time with the offending form in the message, instead of
compiling into a table that silently matches nothing.

`:home` / `:project-detail` / ... is the route's identity everywhere else in this API
(`url-for`, `navigate!`, `atom-matched-route`); the paired component is what
`matched-route-panel` renders when that route is current.

## Registering routes per feature

Each feature owns its route table and registers it during its own init:

```clojure
(ns myapp.feature.account.routes
  (:require [myapp.feature.account.view :as view]))

(def routes
  ["" {"/account" [:account view/page]}])
```

```clojure
(ns myapp.feature.account.core
  (:require [bangmod.router.core :as router]
            [myapp.feature.account.routes :as routes]))

(defn init []
  (router/register-routes routes/routes))
```

App boot wires every feature together and starts the router last, once every feature has
registered:

```clojure
(ns myapp.core
  (:require [bangmod.router.core :as router]
            [bangmod.http-api.core :as http-api]
            [myapp.feature.authentication.core :as authentication-feature]
            [myapp.feature.account.core :as account-feature]
            [myapp.feature.admin.core :as admin-feature]
            [myapp.feature.projects.core :as projects-feature]
            [myapp.feature.docs.core :as docs-feature]
            [myapp.feature.not-found.view :as not-found]
            [myapp.auth :as auth]))

(defn init []
  (http-api/set-auth-token-provider! (fn [] @auth/access-token))
  (authentication-feature/init)
  (account-feature/init)
  (admin-feature/init)
  (projects-feature/init)
  (docs-feature/init)
  (router/start! {:default-component not-found/not-found-panel}))
```

No feature's `init` needs to know about any other's routes — adding, removing, or renaming a
feature is a local change plus one `init` call in `myapp.core`.

## Navigation and URL generation

```clojure
;; navigate! — push a URL and re-match, as if the user followed a link
(router/navigate! :home)
(router/navigate! "/settings/profile")           ; a literal path works too

;; url-for — reverse-route a handler into a path string
(router/url-for :project-detail :id 42)
;; => "/projects/42"

;; a trailing map with a :query key adds a query string instead of a path param
(router/url-for :project-detail :id 42 {:query {:tab "logs" :sort "asc"}})
;; => "/projects/42?tab=logs&sort=asc"
```

`navigate!` belongs in a lifecycle callback or event handler, not in a render function —
changing the matched route while a render is still in progress can unmount the very
component that triggered the navigation. A real use: redirecting away from a login screen
once auth succeeds (full example in [`reagent-form`'s docs](form.md#real-world-example)):

```clojure
(ns myapp.feature.authentication.view
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [bangmod.router.core :as router]))

(defn login-panel []
  (let [user-sub (rf/subscribe [:auth/user])
        redirect! (fn [] (when @user-sub (router/navigate! :account)))]
    (r/create-class
     {:component-did-mount  (fn [_] (redirect!))
      :component-did-update (fn [_] (redirect!))
      :reagent-render       (fn [] [:div "..."])})))
```

## Reactive route state

```clojure
(ns myapp.feature.project.view
  (:require [bangmod.router.core :as router]))

(defn project-view []
  (let [{:keys [id]} @router/atom-params
        {:keys [tab]} @router/atom-query-params
        current-route @router/atom-matched-route]
    [:div
     [:h2 "Project ID: " id]
     [:p "Active tab: " (or tab "overview")]
     [:small "Matched handler: " (str current-route)]]))
```

## `registration-report`

Two mistakes the router can't surface on its own: a route with no matching component (falls
through to the default/fallback, silently), and a component registered under a keyword no
route table produces (dead code). `registration-report` audits the compiled table for both:

```clojure
(router/registration-report)
;; => {:routed               [:home :project-detail :settings-profile]
;;     :registered           [:home :project-detail :settings-profile :old-dashboard]
;;     :duplicates           []
;;     :orphan-routes        []
;;     :orphan-registrations [:old-dashboard]}
```

| Field                  | Meaning                                                              |
| ----------------------- | --------------------------------------------------------------------- |
| `:routed`               | handler keywords the compiled route table can match                   |
| `:registered`           | handler keywords with a component installed                           |
| `:duplicates`           | handlers registered more than once (second silently shadows first)    |
| `:orphan-routes`        | routable but no component — renders the default, silently             |
| `:orphan-registrations` | component registered for a route nothing points at — dead code        |

Worth wiring into a dev-only check after every feature has initialized.

## API reference

`bangmod.router.core`:

| Function / var | Signature | |
| --- | --- | --- |
| `start!` | `[{:keys [default-component]}]` | Starts the router: installs the fallback, wires re-frame, starts listening to history. Call once, after every feature has called `register-routes`. |
| `register-routes` | `[routes]` | Merges one bidi route table into the app's combined route set. |
| `url-for` | `[handler & args]` | Builds a URL. |
| `navigate!` | `[handler-or-url]` | Pushes history and re-matches. |
| `registration-report` | `[]` | See above. |
| `atom-matched-route` | reaction | Current route's handler keyword (`:default` if none matched). |
| `atom-params` | reaction | Current route's path parameters (`{}` if none). |
| `atom-query-params` | reaction | Current route's query parameters. |

`bangmod.router.views`:

| Component | |
| --- | --- |
| `[matched-route-panel]` | Renders whatever component is registered for `atom-matched-route`'s current value. Drop it once in your root component. |

## Gotchas

- **A duplicate route registration is a silent shadow, on purpose, differently in dev and
  prod.** Two components under the same handler keyword is a `defmulti`/`defmethod` — the
  second silently replaces the first. A `goog.DEBUG` build throws immediately; a production
  build only logs to the console, since a shadowed route is a bug but a white screen in
  production is worse. `registration-report`'s `:duplicates` catches this either way.
- **`start!` is boot-time, once, after every `register-routes` call.** Routes registered
  after `start!` still work, but nothing renders them until the next navigation.
- **An orphan route renders the default component, not an error** — no exception, no console
  warning. `registration-report`'s `:orphan-routes` is the only way to find it.
- **Overlapping URL patterns across features aren't detected.** Two features registering the
  same path under different handler keywords is first-registered-wins, silently —
  `register-route-key!` only catches an identical handler keyword, not an identical URL.

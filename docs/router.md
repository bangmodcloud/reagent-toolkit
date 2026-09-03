# reagent-router

`io.github.bangmodcloud/reagent-router` — namespace `bangmod.router.*`

A thin, opinionated wrapper around [bidi](https://github.com/juxt/bidi) (route matching) and
[pushy](https://github.com/kibu-oss/pushy) (HTML5 history) for Reagent/re-frame single-page
apps. Each feature of your app registers its own route table independently; the router owns
matching the current URL against all of them and re-rendering whichever component matched.

## Install

Add the artifact — see the [root README](../README.md#installation) for the full
`deps.edn` / git-dependency snippets.

## Quick start

A minimal app with two routes and a 404 fallback. This is the same shape every feature in a
larger app repeats: a `routes` table per feature, a component per route, one `start!` call at
boot.

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
  [:div
   [router-views/matched-route-panel]])

(defn init []
  ;; Each feature registers its own table; call this once per feature, in any order.
  (router/register-routes home-routes/routes)
  ;; Call once, after every feature has registered. Unmatched URLs render
  ;; `:default-component`.
  (router/start! {:default-component not-found/not-found-panel})
  (rdom/render [root] (.getElementById js/document "app")))
```

`register-routes` takes a [bidi](https://github.com/juxt/bidi) route table where each leaf is
`[handler-keyword component]` instead of bidi's usual bare handler keyword:

```clojure
["" {"/account" [:account view/page]}]
```

`:account` is the route's identity everywhere else in this API (`url-for`, `navigate!`,
`atom-matched-route`); `view/page` is the component `matched-route-panel` renders when that
route is current.

## API reference

From `bangmod.router.core`:

- **`(start! {:default-component fallback-component})`** — starts the router: installs the
  fallback component for unmatched URLs, wires re-frame integration, and starts listening to
  history changes. Call exactly once, after every feature has called `register-routes`.
- **`(register-routes routes)`** — merges one bidi route table into the app's combined route
  set. Each feature calls this itself during its own init, independent of the others.
- **`(url-for handler & args)`** — builds a URL path (and query string) for a route handler
  keyword. `args` are passed to `bidi/path-for` for path-parameter substitution; a map
  containing a `:query` key contributes a query string instead, e.g.
  `(router/url-for :account {:query {:tab "billing"}})`.
- **`(navigate! handler)`** — pushes a new history entry and re-matches, as if the user had
  followed a link to that route. `handler` is a route keyword (resolved with `url-for`) or a
  literal URL string.
- **`atom-params`** — reagent reaction over the current route's path parameters (`{}` if
  none).
- **`atom-query-params`** — reagent reaction over the current route's query parameters.
- **`atom-matched-route`** — reagent reaction over the current route's handler keyword
  (`:default` when nothing matched).
- **`(registration-report)`** — debugging helper, described below.

From `bangmod.router.views`:

- **`(matched-route-panel)`** — renders whatever component is registered for
  `atom-matched-route`'s current value. Drop it once, anywhere in your root component, in
  place of a hand-written `case`/`condp` over the current route.

### `registration-report`

```clojure
(router/registration-report)
;; => {:routed [...]                 ; handler keywords the compiled route table can match
;;     :registered [...]             ; handler keywords with a component installed
;;     :duplicates [...]             ; handler keywords registered more than once
;;     :orphan-routes [...]          ; routable but nothing renders for them — blank page
;;     :orphan-registrations [...]}  ; a component installed for a route nothing points at
```

Call it from a REPL, or wire it into a dev-only sanity check after all features have
initialized, to catch two classes of mistake the router cannot surface on its own: a route
table entry with no matching component (renders the default/fallback silently), and a
component registered under a keyword no route table actually produces (dead code).

## Real-world example

A feature module registering its own routes at init:

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

App boot, wiring every feature together and starting the router last:

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
  ;; ===== feature initialization (each registers its own routes)
  (authentication-feature/init)
  (account-feature/init)
  (admin-feature/init)
  (projects-feature/init)
  (docs-feature/init)
  ;; Unmatched routes fall back to the 404 page.
  (router/start! {:default-component not-found/not-found-panel}))
```

`navigate!` used to leave a page once some app state becomes true — here, redirecting away
from the login screen once the user is authenticated. The full component (including the login
form itself) is in the [`reagent-form` real-world example](form.md#real-world-example); this
is the redirect half of the same `login-panel`:

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

## Gotchas

- **`start!` is boot-time, once.** Everything that calls `register-routes` must run before
  it — the fallback component and the history listener are installed by `start!`, and routes
  registered afterwards still work, but nothing renders them until the next navigation.
- **A duplicate route registration is a silent shadow, on purpose, differently in dev and
  prod.** Two components registered under the same handler keyword is a `defmulti`/`defmethod`
  under the hood — the second `defmethod` silently replaces the first. In a `goog.DEBUG` build
  this throws immediately, loud and at the source of the mistake. In a production build it
  only logs to the console: a shadowed route is a bug, but a white screen in production is a
  worse one. `registration-report`'s `:duplicates` is how you find this class of bug in a
  build where it doesn't throw.
- **`navigate!` belongs in a lifecycle callback, not in render.** Call it from
  `component-did-mount` / `component-did-update` (as in the example above), a re-frame event
  handler, or any other code that isn't itself a component's render function. Calling it
  during render changes the matched route while that render is still in progress, which can
  unmount the very component that triggered the navigation partway through.
- **An orphan route renders the default component, not an error.** If a route table entry has
  no corresponding component registration (a typo in the handler keyword between the routes
  file and the component's `defmethod`, most commonly), the URL matches, but
  `matched-route-panel` has nothing to render for it and falls through to whatever
  `:default-component` is — no exception, no console warning. `registration-report`'s
  `:orphan-routes` catches this.

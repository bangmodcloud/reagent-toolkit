# reagent-router

`io.github.bangmodcloud/reagent-router` — namespace `bangmod.router.*`

Turns the browser URL into "which component is on screen". You write route tables that map
URL patterns to components, each feature registers its own, and one panel component renders
whatever the current URL matches. Under the hood: [bidi](https://github.com/juxt/bidi) does
the matching, [pushy](https://github.com/kibu-oss/pushy) drives HTML5 history.

## Install

See the [root README](../README.md#installation) for `deps.edn` / git-dependency snippets.

## Quick start

Three parts: a route table, the panel that renders the match, and `start!`.

```clojure
(ns myapp.core
  (:require [reagent.dom :as rdom]
            [bangmod.router.core :as router]
            [bangmod.router.views :as router-views]))

;; 1. Components, and a table mapping URL patterns to them.
(defn home-page []    [:div "Home"])
(defn about-page []   [:div "About"])
(defn not-found []    [:div "Nothing here."])

(def routes
  ["" {"/"      [:home  home-page]
       "/about" [:about about-page]}])

;; 2. The panel: renders whichever component the current URL matches.
(defn root []
  [:div
   [:nav
    [:a {:href (router/url-for :home)} "Home"] " "
    [:a {:href (router/url-for :about)} "About"]]
   [router-views/matched-route-panel]])

;; 3. Register, start, render — in that order.
(defn init []
  (router/register-routes routes)
  (router/start! {:default-component not-found})
  (rdom/render [root] (.getElementById js/document "app")))
```

Clicking a link swaps the panel's content without a page load — pushy intercepts clicks on
same-origin `<a href>`s and pushes history instead. A URL nothing matches renders
`:default-component`.

`register-routes` can be called as many times as you like, so you don't have to keep one
big table: split the routes by feature or module and let each one register its own — see
[One table per feature](#one-table-per-feature).

## Route tables

A route table is a bidi table with one change: **a leaf is `[handler-keyword component]`**,
not bidi's bare keyword. The keyword is the route's name everywhere else — `url-for`,
`navigate!`, `atom-matched-route` — and the component is what the panel renders.

```clojure
["" {"/"                 [:home            home-page]
     ["/projects/" :id]  [:project-detail  project-page]    ; path parameter
     "/settings"         {"/profile"  [:settings-profile  profile-page]
                          "/billing"  [:settings-billing  billing-page]}}]
```

Reading it:

- The outer `["" {...}]` is `[prefix table]`. The prefix is prepended to every pattern
  inside; `""` means none. A feature can use it to namespace itself: `["/admin" {...}]`.
- A **map** table is `pattern -> leaf-or-subtable`. A subtable nests: `"/settings"` +
  `"/profile"` matches `/settings/profile`.
- A **path parameter** is a pattern vector: `["/projects/" :id]` matches `/projects/42`
  with `{:id "42"}` in the params. Parameters are always strings.
- A **vector of pairs** works where a map does — `["" [["/" [:home home-page]]
  ["/about" [:about about-page]]]]` — and preserves order, which matters when two patterns
  could both match: bidi takes the first.

A table that doesn't fit this grammar — a leaf without a component, a route that isn't a
`[pattern matched]` pair — throws at `register-routes` time with the offending form in the
message, rather than compiling into something that quietly matches nothing.

## One table per feature

`register-routes` can be called any number of times; each call adds a table to the set the
router matches against. The pattern this is built for is each feature owning its routes and
registering them in its own `init`:

```clojure
(ns myapp.feature.projects.core
  (:require [bangmod.router.core :as router]
            [myapp.feature.projects.view :as view]))

(defn init []
  (router/register-routes
    ["/projects" {""          [:projects       view/list-page]
                  ["/" :id]   [:project-detail view/detail-page]}]))
```

```clojure
(ns myapp.core
  (:require [bangmod.router.core :as router]
            [myapp.feature.projects.core :as projects]
            [myapp.feature.account.core :as account]
            [myapp.feature.not-found.view :as not-found]))

(defn init []
  (projects/init)
  (account/init)
  (router/start! {:default-component not-found/page}))   ; last, once
```

Adding a feature is one `init` call here; nothing else needs to know its URLs. The one rule
is order: **every `register-routes` before `start!`**. Tables registered after `start!` are
matched from the next navigation on, not for the URL already on screen.

## Reading the current route

Three reagent reactions in `bangmod.router.core`, for use in any render fn:

| Reaction | Value |
| --- | --- |
| `@router/atom-matched-route` | the handler keyword of the current route, or `:default` when nothing matched |
| `@router/atom-params` | path parameters, e.g. `{:id "42"}`; `{}` when the route has none |
| `@router/atom-query-params` | query string as a map, e.g. `{:tab "logs"}` — keys keywordized, values strings |

```clojure
(ns myapp.feature.projects.view
  (:require [bangmod.router.core :as router]))

(defn detail-page []
  (let [{:keys [id]}  @router/atom-params          ; "/projects/42"       -> "42"
        {:keys [tab]} @router/atom-query-params]   ; "?tab=logs"          -> "logs"
    [:div
     [:h2 "Project " id]
     [:p "Tab: " (or tab "overview")]]))
```

They are reactions, so a component that derefs one re-renders when the URL changes. A page
component reads its own params this way rather than receiving them as arguments — the panel
renders every routed component with no arguments.

After `start!` the same state also lives in re-frame's app-db, under
`[:_router :matched-route]` (the full match: `:handler`, `:route-params`, `:query-params`)
and `[:_router :app-routes]` (the compiled tables), for subscriptions and event handlers
that want it.

## Navigating

```clojure
(router/url-for :project-detail :id 42)                       ; => "/projects/42"
(router/url-for :project-detail :id 42 {:query {:tab "logs"}}) ; => "/projects/42?tab=logs"

(router/navigate! :home)                    ; by name
(router/navigate! (router/url-for :project-detail :id 42))
(router/navigate! "/settings/profile")      ; or any path string
```

`url-for` takes the route's keyword and its path parameters as keyword arguments; a
trailing map with `:query` becomes the query string. It is the right thing to put in an
`:href` — links stay real links (open-in-new-tab, copy-address work) and pushy still
routes a plain click in place.

`navigate!` pushes the URL onto history and re-matches, as if the user had followed a link.
Call it from an event handler or a lifecycle callback, **never from a render fn**:
changing the matched route mid-render can unmount the very component that is rendering.
The typical case is a redirect once some state arrives:

```clojure
(defn login-page []
  (let [user (rf/subscribe [:auth/user])]
    (r/create-class
     {:component-did-update (fn [_] (when @user (router/navigate! :home)))
      :reagent-render       (fn [] [login-form])})))
```

## Unmatched URLs and the default component

`start!`'s `:default-component` renders whenever the URL matches no table — a 404 page,
typically. It is also what a route with a typo'd or missing component falls back to (see
the next section), so keep it recognisable. Without one, the panel renders
`"No component found for this route."`.

## Auditing the tables: `registration-report`

Two mistakes produce no error at runtime: a route whose component never got registered
(the URL renders the default component, silently), and a component registered under a
keyword no table produces (dead code). Both are visible in the compiled tables, so check
them in dev after every feature has initialized:

```clojure
(router/registration-report)
;; => {:routed               [:home :project-detail :settings-profile]
;;     :registered           [:home :project-detail :settings-profile :old-dashboard]
;;     :duplicates           []
;;     :orphan-routes        []
;;     :orphan-registrations [:old-dashboard]}
```

| Key | Meaning |
| --- | --- |
| `:routed` | handler keywords some table can match |
| `:registered` | handler keywords that have a component |
| `:duplicates` | keywords registered twice — the later component silently replaces the earlier |
| `:orphan-routes` | routable, but no component: renders the default component |
| `:orphan-registrations` | a component no route reaches |

A duplicate is also caught at registration time: in a dev build (`goog.DEBUG`) the second
`register-routes` for the same keyword throws; in production it logs to the console and the
later component wins, because a white screen is worse than a shadowed route.

## API reference

`bangmod.router.core`:

| | |
| --- | --- |
| `(register-routes table)` | Adds one route table. Call per feature, before `start!`. Throws on a malformed table. |
| `(start! {:default-component c})` | Once, after every `register-routes`: installs the fallback, mirrors route state into re-frame, starts listening to history and matches the current URL. |
| `(url-for kw & path-params {:query {...}}?)` | The path for a route: `(url-for :project-detail :id 42)`. Optional trailing `{:query m}` appends a query string. |
| `(navigate! kw-or-path)` | Push the URL for a route keyword, or a literal path, and re-match. Not from a render fn. |
| `atom-matched-route` | Reaction: current handler keyword, `:default` when unmatched. |
| `atom-params` | Reaction: path parameters map. |
| `atom-query-params` | Reaction: query parameters map. |
| `(registration-report)` | Dev audit of the compiled tables — see above. |

`bangmod.router.views`:

| | |
| --- | --- |
| `[matched-route-panel]` | Renders the component for the current route, with no arguments. Put it once in the root component, mounted after `start!`. |

## Gotchas

- **`start!` after every `register-routes`, once.** A table registered afterwards is only
  consulted on the next navigation.
- **Mount `matched-route-panel` after `start!`.** Before it, no route is matched and no
  default is installed, so the panel has nothing to render.
- **Never `navigate!` from a render fn** — lifecycle callbacks and event handlers only.
- **Same URL in two features is first-registered-wins, silently.** Registration only
  detects a repeated handler *keyword*, not a repeated *pattern*.
- **An orphan route renders the default component**, not an error. `registration-report`
  is the only thing that will tell you.
- **Every same-origin link is intercepted.** pushy takes over clicks on `<a href>`s that
  point at this origin, matched or not; an unmatched one renders the default component
  rather than loading the page. Left to the browser: other origins, `target="_blank"` /
  `"_self"`, modifier- or non-left-clicks, and anything with `data-pushy-ignore`.
- **Browser only.** `bangmod.router.internal` needs `js/window` at load time (pushy). The
  pure route-compilation half, `bangmod.router.table`, loads anywhere and is what the unit
  tests cover.

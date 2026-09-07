---
name: bmc-reagent-toolkit
description: Use when writing ClojureScript code against the reagent-toolkit libraries — bangmod.form (forms/validation), bangmod.http-api (declarative HTTP + SSE), bangmod.router (bidi/pushy routing for re-frame SPAs). Covers the exact APIs, the route-table grammar, the two ways to call the form API, the SSE server contract, and sharp edges.
---

# reagent-toolkit (bangmod.form / bangmod.http-api / bangmod.router)

Three independent ClojureScript libraries for Reagent/re-frame apps. This skill targets
**0.2.0**. Docs live in `docs/{form,http-api,router}.md` of
https://github.com/bangmodcloud/reagent-toolkit — trust this skill for signatures; read the
source under `modules/*/src/bangmod/` only when something here doesn't cover it.

```clojure
{:deps {io.github.bangmodcloud/reagent-form     {:mvn/version "0.2.0"}
        io.github.bangmodcloud/reagent-http-api {:mvn/version "0.2.0"}
        io.github.bangmodcloud/reagent-router   {:mvn/version "0.2.0"}}}
```

They do not depend on each other — add only what the task needs.

## bangmod.router

```clojure
(ns myapp.core
  (:require [bangmod.router.core :as router]
            [bangmod.router.views :as router-views]))
```

Boot order is the one rule: every feature calls `register-routes` first, then the app calls
`start!` exactly once.

- `(router/register-routes routes)` — merges one route table; each feature calls it in its
  own `init`.
- `(router/start! {:default-component fallback})` — installs the fallback for unmatched
  URLs, wires re-frame (`[:_router ...]` in app-db), starts history. Once, at boot, last.
- `[router-views/matched-route-panel]` — put once in the root view; renders whichever
  component matches the current URL.
- `(router/navigate! :account)` or `(router/navigate! "/settings/profile")` — from a
  lifecycle callback or event handler, NEVER from a render function (it can unmount the
  component mid-render).
- `(router/url-for :project-detail :id 42)` → `"/projects/42"`; append
  `{:query {:tab "logs"}}` as a final map arg for a query string.
- `@router/atom-params`, `@router/atom-query-params`, `@router/atom-matched-route` —
  reagent reactions over the current route.
- `(router/registration-report)` — dev check; returns `{:routed :registered :duplicates
  :orphan-routes :orphan-registrations}`.

### Route grammar

bidi tables where each **leaf is `[handler-keyword component]`** (not bidi's bare keyword).
Map tables and vector-of-pairs tables both work; path-param patterns go in the pattern
position:

```clojure
(def routes
  ["" {"/" [:home home-view]
       ["/projects/" :id] [:project-detail project-view]   ; :id lands in atom-params
       "/settings" {"/profile" [:settings-profile profile-view]}}])
```

Malformed tables (leaf without component, route that isn't a `[pattern matched]` pair)
throw at `register-routes` time.

### Sharp edges

- Registering the same handler keyword twice: throws in dev (`goog.DEBUG`), only
  `console.error` in production (the later component silently wins).
- Two features claiming the same URL under different keywords is NOT detected —
  first-registered wins.
- A routed keyword with no component renders the default component silently; catch with
  `registration-report`.
- Browser-only (`pushy` needs `js/window`) — do not load `bangmod.router.internal` in a
  node test build. `bangmod.router.table` (the pure compile half) is loadable anywhere.

## bangmod.http-api

```clojure
(ns myapp.api.account
  (:require [bangmod.http-api.core :as http-api :refer [defapi]]))

(defapi :account
  {:base-url config/API_BASE_URL}
  {:get        {:method :get  :uri "/api/query/account-projection/me" :response-format :json}
   :changes    {:method :sse  :uri "/api/query/account-projection/me"}   ; same URI as :get
   :auto-renew {:method :post :uri "/api/commands/account/auto-renew"
                :request-format :json :response-format :json}})
```

Endpoint spec keys: `:method` (`:get :post :put :patch :delete :sse`), `:uri` (`:param`
placeholders, values percent-encoded), `:request-format` / `:response-format`
(`:json :url :transit :raw` / `:json :text :transit :raw`), `:timeout` (ms, default 10000),
`:with-credentials`. `:_options` is reserved — never an endpoint name.

- `(http-api/execute :account :get)` / `(execute api ep {:path-params {:id 7} :params {...}
  :headers {...}})` — returns a core.async channel delivering ONE map:
  `{:success? true :data <parsed body>}` or `{:success? false :data <cljs-ajax error map>}`
  (failure `:data` has `:status`, `:response`, ...). Always take with `a/<!` in a `go`.
- `(http-api/subscribe :account :changes {:on-open #(...) :on-message (fn [data] ...)
  :on-error (fn [msg] ...) :events ["changed"]})` — SSE endpoints only; returns a handle.
  `:events` lists extra named SSE event types treated as messages (default `["changed"]`).
- `(http-api/unsubscribe! handle)` — ALWAYS call in `component-will-unmount`.
- `(http-api/set-auth-token-provider! (fn [] @auth/access-token))` — once at boot; injects
  `Bearer` on every request without an explicit `:authorization` header. SSE gets the token
  as `?access_token=` (EventSource cannot set headers).
- `(http-api/set-token-stale-handler! f)` — `f` returns a channel that closes when a fresh
  token is loaded; a 401 whose body is `{:reason "token-stale"}` triggers ONE reload+retry,
  shared across concurrent requests.
- `(http-api/init)` — optional; mirrors all data into re-frame app-db at `[:_http-api :data]`.
- `@(http-api/get-data-reaction :account :get)` — reaction over the endpoint's latest
  result; throws if the endpoint was never declared.

### The canonical SSE pattern

`:on-open` fires on EVERY (re)connection — it is where the full re-fetch of dependent data
belongs (the server subscribes before writing its first byte, so nothing slips between
snapshot and stream):

```clojure
(defn account-page []
  (let [changes-sub (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (load-account!)
        (reset! changes-sub
                (http-api/subscribe :account :changes
                                    {:on-open    (fn [] (load-ledger!))
                                     :on-message (fn [acct]
                                                   (rf/dispatch [:account/set acct])
                                                   (load-ledger!))})))
      :component-will-unmount
      (fn [] (http-api/unsubscribe! @changes-sub))
      :reagent-render
      (fn [] [:div ...])})))
```

### Sharp edges

- The data slot (reaction / app-db) keeps the last successful `:data` across failures — a
  failed call sets `:success? false` and puts the failure under `:error` there. The channel
  result is the raw failure either way.
- `execute` on an `:sse` endpoint throws, as does `subscribe` on a non-`:sse` one.
- Passing `:headers {:authorization ...}` replaces the auto-injected token for that call.
- The SSE reconnect protocol is a server contract: a `reconnect` event whose data is
  `{:reason "..."}` — reason `"token-stale"` reloads the token before re-opening; any other
  reason re-opens on backoff (1s doubling, capped 30s).
- Pure/testable halves: `bangmod.http-api.sse` and `bangmod.http-api.retry` load without a
  browser; `bangmod.http-api.internal` requires `ajax.core` (needs `js/XMLHttpRequest`).

## bangmod.form

```clojure
(ns myapp.views.login
  (:require [bangmod.form.core :as form]))
```

Create outside render; the form is registered globally under its id:

- `(form/create-form :login)` / `(form/create-form :login {:initial-values {...}})` —
  `:initial-values` is a map or anything derefable holding one. Re-creating with the same id
  silently replaces the registry entry (`form/get-form` follows the newest).
- `(form/make-api form)` — destructure once; returns bound fns:
  `:register-field :handle-submit :get-field-display-value :get-field-display-error
  :get-raw-field-value :change-field-value :validate-field :touch :deregister-fields
  :get-all-fields-errors :get-is-submitting :get-form-display-error`.
- `(form/create-success-submission-result)` / `(form/create-failed-submission-result msg)` —
  what `on-submit` must return (directly, or via a core.async read port).

### Fields

```clojure
[:input.input (register-field :email {:id "login-email" :type "email"
                                      :validators [v/required]
                                      :class (when (get-field-display-error :email) "input-error")})]
(when-let [err (get-field-display-error :email)] [:p.error-text err])
```

`register-field` returns the complete controlled-input props (`:value :on-change :on-blur
:on-focus :id :type` + passthrough of extra keys like `:class`). Config: `:validators`
(vector of `(fn [value]) -> error-string-or-nil`, first error wins), `:default-value`,
`:id` (defaults to field-name), `:type` (default `"text"`), `:placeholder` (NO default),
and `:on-change`/`:on-blur`/`:on-focus` overrides.

The generated `:on-change` reads `(.. e -target -value)` — for custom controls (date
pickers, selects) that pass a raw value, override it:
`:on-change #(change-field-value :start-date %)`.

### Submitting

`[:form {:on-submit (handle-submit on-submit)}]` — on submit it prevents default, touches +
validates every field; if any field errors, `on-submit` is NOT called (errors are now
visible). Otherwise `on-submit` receives the values map (`(fn [{:keys [email password]}]
...)`) and must return a submission result (or a channel of one). A throwing `on-submit`
becomes a failed submission — the form never sticks in `:is-submitting`.
`get-form-display-error` is `nil` while submitting; field errors are `nil` until touched.

### Direct protocol calls (`bangmod.form.api`)

`make-api` is a convenience wrapper over the `IForm` protocol — every fn can also be called
as `(api/register-field form :email {...})` with the form first. Three are ONLY available
this way:

- `(api/get-form-values form)` — all raw values as a map, any time.
- `(api/validate-all-fields form)` — touch + validate everything, return first error or nil
  (wizard-step checks).
- `(api/get-initial-values form)`.

### Nested forms

```clojure
[form/FieldGroup {:form parent :name :billing-address}
 (fn [nested-form] ...build (form/make-api nested-form) and register fields...)]

[form/FieldArray {:form parent :name :items}          ; also :element-removal-strategy :both|:element-only
 (fn [add-fn remove-fn item-forms]
   ;; item-forms: vector of sub-forms; (add-fn) or (add-fn {:qty 1}); (remove-fn idx)
   ...)]
```

`:form` may be the form value or its registry keyword.

### Performance model (why big forms stay fast)

Each field gets its own reactions; a keystroke re-renders only components reading THAT
field. FieldArray/FieldGroup register their aggregate value on the parent as a reaction
ONCE — edits inside a row never write parent state. The granularity is your component
boundaries: split big forms into per-section components so only the edited section
re-renders.

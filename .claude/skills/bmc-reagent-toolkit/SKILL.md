---
name: bmc-reagent-toolkit
description: Use when writing ClojureScript code against the reagent-toolkit libraries — bangmod.form (forms/validation), bangmod.http-api (declarative HTTP + SSE), bangmod.router (bidi/pushy routing for re-frame SPAs). Covers the exact APIs, the route-table grammar, the bangmod.form.api call shape, the SSE server contract, and sharp edges.
---

# reagent-toolkit (bangmod.form / bangmod.http-api / bangmod.router)

Three independent ClojureScript libraries for Reagent/re-frame apps. This skill targets
**0.3.0**. Docs live in `docs/{form,http-api,router}.md` of
https://github.com/bangmodcloud/reagent-toolkit — trust this skill for signatures; read the
source under `modules/*/src/bangmod/` only when something here doesn't cover it.

```clojure
{:deps {io.github.bangmodcloud/reagent-form     {:mvn/version "0.3.0"}
        io.github.bangmodcloud/reagent-http-api {:mvn/version "0.3.0"}
        io.github.bangmodcloud/reagent-router   {:mvn/version "0.3.0"}}}
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
  :headers {...}})` — fires the request and returns the endpoint's REACTION (it is
  `raw-execute` + `get-data-reaction`). On an `:sse` endpoint it opens the stream instead
  (opts as for `subscribe`) and returns the subscription handle, which derefs the same way.
  Either way `(:data @x)` is the latest body/frame. The component pattern — fires once on
  mount, deref in the body, close on unmount (a no-op for a plain request):
  `(r/with-let [x (http-api/execute :api :ep)] [:div (:data @x)] (finally (http-api/unsubscribe! x)))`.
  Never call it inside a render fn — that re-fires / re-opens on every render.
- `(http-api/raw-execute ...)` — same args, returns a core.async channel delivering ONE map:
  `{:success? true :data <parsed body>}` or `{:success? false :data <cljs-ajax error map>}`
  (failure `:data` has `:status`, `:response`, ...). Always take with `a/<!` in a `go`. Use
  it when you need THIS request's result as a value (dispatch on success, error branch).
- `(http-api/subscribe :account :changes {:on-open #(...) :on-message (fn [data] ...)
  :on-error (fn [msg] ...) :events ["changed"]})` — SSE endpoints only; returns a handle.
  `:events` lists extra named SSE event types treated as messages (default `["changed"]`).
- `(http-api/unsubscribe! handle)` — ALWAYS call in `component-will-unmount` / `with-let`'s
  `finally`. The handle derefs to `{:sse? true :connected? bool :data <latest frame>
  :message-count n :error msg-or-nil}`. No-op on a non-subscription (a plain reaction, nil).
- `(http-api/set-auth-token-provider! (fn [] @auth/access-token))` — once at boot; injects
  `Bearer` on every request without an explicit `:authorization` header. SSE gets the token
  as `?access_token=` (EventSource cannot set headers).
- `(http-api/set-token-stale-handler! f)` — `f` returns a channel that closes when a fresh
  token is loaded; a 401 whose body is `{:reason "token-stale"}` triggers ONE reload+retry,
  shared across concurrent requests.
- `(http-api/init)` — optional; mirrors all data into re-frame app-db at `[:_http-api :data]`.
- `@(http-api/get-data-reaction :account :get)` — the same reaction `execute` returns, without
  firing anything. It is the endpoint's stored slot, NOT the last call: `nil` before the first
  response, `{:success? true :data <body>}` after a success, and after a failed call that same
  map with `:success? false` and `:error <failure data>` merged in, so the last good `:data`
  survives. Bind the UI to `(:data @reaction)`; read failure detail off the `raw-execute`
  channel result, never off the reaction. Throws if the endpoint was never declared.

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
- `raw-execute` on an `:sse` endpoint throws, as does `subscribe` on a non-`:sse` one.
  `execute` accepts both.
- Passing `:headers {:authorization ...}` replaces the auto-injected token for that call.
- The SSE reconnect protocol is a server contract: a `reconnect` event whose data is
  `{:reason "..."}` — reason `"token-stale"` reloads the token before re-opening; any other
  reason re-opens on backoff (1s doubling, capped 30s).
- Pure/testable halves: `bangmod.http-api.sse` and `bangmod.http-api.retry` load without a
  browser; `bangmod.http-api.internal` requires `ajax.core` (needs `js/XMLHttpRequest`).

## bangmod.form

```clojure
(ns myapp.views.login
  (:require [bangmod.form.core :as form]     ; create-form, submission results, FieldArray/FieldGroup
            [bangmod.form.api  :as api]))    ; everything you do WITH a form — form is arg 1
```

Create outside render; the form is registered globally under its id:

- `(form/create-form :login)` / `(form/create-form :login {:initial-values {...}})` —
  `:initial-values` is a map or anything derefable holding one. Re-creating with the same id
  silently replaces the registry entry (`form/get-form` follows the newest).
- `(form/create-form-submission form [form values dispatch] body...)` — MACRO; builds the
  `:on-submit` handler. See Submitting.

`bangmod.form.api` (the `IForm` protocol) is THE way to call a form (there is no bound-map
wrapper — 0.3.x's `make-api` is gone). Every fn takes the form first:
`register-field deregister-fields get-field-display-value get-field-display-error
get-raw-field-value change-field-value validate-field touch get-all-fields-errors
get-form-values validate-all-fields get-initial-values get-is-submitting
get-form-display-error` (+ the lower-level `handle-submit` / `start-submission` /
`handle-form-submission-result`). Getters return VALUES (not reactions) and register the
reactive dependency when read inside a render — call them where you use them.

### Fields

```clojure
[:input.input (api/register-field login-form :email
                {:id "login-email" :type "email"
                 :validators [v/required]
                 :class (when (api/get-field-display-error login-form :email) "input-error")})]
(when-let [err (api/get-field-display-error login-form :email)] [:p.error-text err])
```

`register-field` returns the complete controlled-input props (`:value :on-change :on-blur
:on-focus :id :type` + passthrough of extra keys like `:class`). Config: `:validators`
(vector of `(fn [value]) -> error-string-or-nil`, first error wins), `:default-value`,
`:id` (defaults to field-name), `:type` (default `"text"`), `:placeholder` (NO default),
and `:on-change`/`:on-blur`/`:on-focus` overrides.

The generated `:on-change` reads `(.. e -target -value)` — for custom controls (date
pickers, selects) that pass a raw value, override it:
`:on-change #(api/change-field-value form :start-date %)`. Extra keys in the config flow
through to the element, so a control's own props ride along. react-datepicker
(`["react-datepicker" :default DatePicker]`):
`[:> DatePicker (api/register-field form :d {:date-format "dd/MM/yyyy" :selected
(api/get-field-display-value form :d) :on-change #(api/change-field-value form :d %)})]`
— the form then holds a `js/Date` (initial values must be `js/Date` too; stringify in the
submission body); the `:value` also passed is a Date, which react-datepicker ignores.
`get-form-values` reads `nil` for a never-touched field (initial values are copied in by
`touch`; a submit touches everything).

### Submitting

```clojure
(let [login-form (form/create-form :login)
      on-submit  (form/create-form-submission login-form [_ {:keys [email password]} dispatch]
                   (dispatch (-> (ch->promise (http-api/raw-execute :auth :login {:params {:email email :password password}}))
                                 (.then (fn [{:keys [success? data]}]
                                          (when-not success? (get-in data [:response :message] "Login failed")))))))]
  (fn [] [:form {:on-submit on-submit} ...
          (when-let [err (api/get-form-display-error login-form)] [:p.error-text err])]))
```

`create-form-submission` is a macro: `(form/create-form-submission form [form values dispatch]
body...)` → the `:on-submit` handler. Binding vector is EXACTLY three names (each may
destructure). On submit: preventDefault, touch + validate every field; if any field errors
or a submission is in flight the body does NOT run. Otherwise the body runs once and MUST
call `dispatch` exactly once with:
- `nil` → success
- a string → failure; becomes `get-form-display-error`
- a promise resolving to either → form stays submitting until it settles

Anything else is a programming error: form marked failed with the message AND the error is
thrown — dispatch with any other value (incl. a `[:success]` vector), dispatch twice, never
dispatching (unhandled submission), a rejected promise, a promise resolving to a non-outcome,
or a throwing body. Every path through the body must end in one `dispatch`.

core.async → promise bridge (define it in the app, 2 lines):
`(defn ch->promise [ch] (js/Promise. (fn [resolve _] (a/take! ch resolve))))`.

`get-form-display-error` is `nil` while submitting; field errors are `nil` until touched.
Lower-level: `(api/handle-submit form (fn [values] ...))` where
the fn returns `(form/create-success-submission-result)` /
`(form/create-failed-submission-result msg)` or a channel of one — don't use it in new
code.

Beyond submit: `(api/get-form-values form)` — all raw values as a map, any time;
`(api/validate-all-fields form)` — touch + validate everything, return first error or nil
(wizard-step checks); `(api/get-initial-values form)`.

### Nested forms

```clojure
[form/FieldGroup {:form parent :name :billing-address}
 (fn [nested-form] ...(api/register-field nested-form :street {...}) etc...)]

[form/FieldArray {:form parent :name :items}          ; also :element-removal-strategy :both|:element-only
 (fn [add-fn remove-fn item-forms]
   ;; item-forms: vector of sub-forms; (add-fn) or (add-fn {:qty 1}); (remove-fn idx)
   ...)]
```

`:form` may be the form value or its registry keyword. On the parent the array is ONE
field: its value is a vector of row maps, and the first row error becomes the field's error
(blocks submit). `add-fn`'s map is applied as touched values (validators run at once), not
as initial values.

FieldArray rows follow the parent's `:initial-values` for `:name` (a vector of row maps):
every render ensures at least one row per entry, so a reactive `:initial-values` grows the
array live. `:element-removal-strategy` decides what `(remove-fn idx)` means:
- `:both` (default) — array owns removal: drops the sub-form AND hides that initial-values
  entry, so the remaining rows keep their entries and nothing is re-added. Use with a plain
  `:initial-values` you never shrink yourself.
- `:element-only` — YOUR code owns removal: drops the sub-form only; you must remove the
  entry from the reactive `:initial-values` source in the SAME handler, synchronously
  (`(remove-fn idx) (swap! items remove-nth idx)`), or the "one row per entry" rule puts the
  row back / shifts later rows.
Never do both (shrink the source under `:both` → the next row goes blank); never do
neither (`:element-only` without shrinking → row comes back).

### Performance model (why big forms stay fast)

Each field gets its own reactions; a keystroke re-renders only components reading THAT
field. FieldArray/FieldGroup register their aggregate value on the parent as a reaction
ONCE — edits inside a row never write parent state. The granularity is your component
boundaries: split big forms into per-section components so only the edited section
re-renders.

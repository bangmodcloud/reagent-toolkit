# reagent-form

`io.github.bangmodcloud/reagent-form` — namespace `bangmod.form.*`

Form state, validation and submission handling for Reagent. `register-field` hands back a
ready-to-spread props map for an `[:input ...]` (value, change/blur/focus handlers, id, type
— no event wiring of your own); `handle-submit` gates submission on every field validating
first.

## Install

See the [root README](../README.md#installation) for `deps.edn` / git-dependency snippets.

## Quick start

```clojure
(ns myapp.feature.authentication.view
  (:require [bangmod.form.core :as form]
            [clojure.string :as str]))

(defn required [value]
  (when (str/blank? (str value))
    "This field is required."))

(defn login-form-card []
  (let [login-form (form/create-form :login)
        {:keys [register-field handle-submit get-field-display-error get-is-submitting]}
        (form/make-api login-form)
        on-submit (fn [{:keys [email password]}]
                    (js/console.log "submit:" email password)
                    (form/create-success-submission-result))]
    (fn []
      [:form {:on-submit (handle-submit on-submit)}
       [:div.form-group
        [:label {:for "email"} "Email"]
        [:input.input (register-field :email {:id "email" :type "email"
                                               :validators [required]})]
        (when-let [err (get-field-display-error :email)] [:p.error-text err])]

       [:div.form-group
        [:label {:for "password"} "Password"]
        [:input.input (register-field :password {:id "password" :type "password"
                                                  :validators [required]})]
        (when-let [err (get-field-display-error :password)] [:p.error-text err])]

       [:button.btn.btn-primary {:type "submit" :disabled (get-is-submitting)}
        (if (get-is-submitting) "Submitting..." "Log in")]])))
```

`(form/create-form :login)` registers the form under `:login` globally (`form/get-form
:login` retrieves it elsewhere), which is why it only needs calling once, outside render.

## API reference

There are two ways to call this API on a form. `(form/make-api form)` returns a map of the
12 functions listed below, already bound to `form` — destructure it once and spread the
result through your component, as in the quick start. Or skip `make-api` and call the
protocol functions in `bangmod.form.api` directly, passing `form` as the first argument
yourself: `(api/register-field form field-name field-config)`. Both operate on the same
`ReagentForm` instance, so mixing them on one form is fine — `make-api` is just a
convenience layer over `bangmod.form.api`'s `IForm` protocol, not a different API. Reach for
the direct form when you need one of the few `IForm` functions `make-api` doesn't expose —
see below.

`bangmod.form.core`:

| Function / component | Description |
| --- | --- |
| `(create-form form-id)` / `(create-form form-id {:keys [initial-values]})` | Creates and registers a form under `form-id`. `initial-values` is a map (or reagent atom/reaction of one) of `field-name -> value`, used before a field is touched. |
| `(make-api form)` | Returns the bound functions below as a map, meant to be destructured once. Throws if `form` isn't a `ReagentForm`. |
| `(create-success-submission-result)` / `(create-failed-submission-result msg)` | The two values an `on-submit` fn (passed to `handle-submit`) must produce, directly or via a `core.async` channel. |
| `FieldArray`, `FieldGroup` | Components for repeating/nested field groups — see below. |

Bound functions returned by `make-api`:

| Function | Description |
| --- | --- |
| `register-field field-name field-config` | Registers a field, returns input props: `:value`, `:on-change`, `:on-blur`, `:on-focus`, `:id`, `:type`, `:placeholder`, plus anything else from `field-config`. See below for `field-config`. |
| `deregister-fields field-name-or-list` | Removes one field (keyword) or several (collection) from form state. |
| `get-field-display-value field-name` | Current value, falling back to initial value then `:default-value`. |
| `get-field-display-error field-name` | Current error, or `nil` if the field hasn't been touched. |
| `get-raw-field-value field-name` | Current value with no fallback. |
| `change-field-value field-name value` | Sets a value, marks touched, validates. What the default `:on-change` calls. |
| `validate-field field-name` | Re-runs validators against the current value. |
| `touch field-name` | Marks touched (so its error becomes visible) and validates, without changing value. |
| `get-all-fields-errors` | `({:field name :error err} ...)` for every field currently in error. |
| `get-is-submitting` | `true` while a submission is in flight. |
| `get-form-display-error` | Returns a *reaction* over the form-level error from `create-failed-submission-result` — deref it (`@(get-form-display-error)`), unlike `get-field-display-error` which derefs for you. Suppressed (nil) while submitting. |
| `handle-submit on-submit-fn` | Returns an `:on-submit` handler — see below. |

`field-config` keys for `register-field`:

- `:validators` — vector of validator functions (below). Default `[]`.
- `:default-value` — value before the field has a real or initial value.
- `:id` — defaults to `field-name`. `:type` — defaults to `"text"`. `:placeholder` — defaults
  to `"Enter"`.
- `:on-change` / `:on-blur` / `:on-focus` — override the generated handler.

### Calling `bangmod.form.api` directly

`bangmod.form.api` defines the full `IForm` protocol `make-api` wraps — every function
above, plus a few `make-api` leaves out because they're rarely what a component needs:

| Function | Description |
| --- | --- |
| `(api/get-form-values form)` | A plain map of every field's current raw value — the same shape `handle-submit` passes to `on-submit-fn`, available any time, not just at submit. |
| `(api/validate-all-fields form)` | Touches and validates every field, returns the first error found (or `nil`). Runs the same check `handle-submit` runs, without submitting — useful for a "can I move to the next wizard step" check. |
| `(api/get-initial-values form)` | The form's `:initial-values`, as given to `create-form`. |

Same call shape either way — `form` first, then whatever the function normally takes:

```clojure
(require '[bangmod.form.api :as api])

(api/register-field login-form :email {:validators [v/required]})
(api/get-form-values login-form)
;; => {:email "a@b.com" :password "secret"}
```

This isn't a theoretical escape hatch — `FieldArray` and `FieldGroup` (below) are themselves
built this way: they hold a `form` value with no component of their own bound to it via
`make-api`, and call `api/register-field`, `api/change-field-value`, `api/get-form-values`
and `api/validate-all-fields` on it directly, because a nested/repeated form's own validator
needs `validate-all-fields`, which `make-api` doesn't expose.

### Writing a validator

A validator is a 1-arg function: the field's raw value in, an error (truthy, conventionally
a string) or `nil` out. Validators run in order; the first to return an error wins.

```clojure
(defn required [value]
  (when (clojure.string/blank? (str value))
    "This field is required."))
```

### Submitting

`(handle-submit on-submit-fn)` returns a fn for `:on-submit`. It calls `.preventDefault`,
touches and validates every field, and — only if none now has an error — marks the form
submitting and calls `(on-submit-fn field-values)` with a plain map of every field's raw
value (destructure directly: `(fn [{:keys [email password]}] ...)`). If any field has an
error, `on-submit-fn` is never called; the errors are already visible since every field was
just touched. `on-submit-fn`'s return value — directly, or eventually via a `core.async`
channel — must be `(create-success-submission-result)` or `(create-failed-submission-result
msg)`; either way this clears `get-is-submitting` and, on failure, sets
`get-form-display-error` to `msg`.

## Field arrays and field groups

`FieldArray` and `FieldGroup` register a field whose value is itself a list of sub-forms or a
single nested sub-form. Both take `:form` (a form, or a form-id keyword) and `:name` (the
field name they register under on the parent), plus a render prop.

**`FieldGroup`** — render prop receives the nested form to build a `make-api` from, same as
any other form:

```clojure
[form/FieldGroup {:form parent-form :name :billing-address}
 (fn [nested-form]
   (let [{:keys [register-field get-field-display-error]} (form/make-api nested-form)]
     [:div.address-group
      [:div.form-group
       [:label "Street"]
       [:input (register-field :street {:validators [v/required]})]
       (when-let [err (get-field-display-error :street)] [:span.error err])]]))]
```

**`FieldArray`** — render prop receives `(add-fn remove-fn forms)`: call `add-fn` (optionally
with a map of initial values) to append a sub-form, `(remove-fn index)` to drop one, and
render `forms` (a vector of sub-forms) yourself:

```clojure
[form/FieldArray {:form parent-form :name :items}
 (fn [add-fn remove-fn item-forms]
   [:div
    (doall
     (map-indexed
      (fn [idx item-form]
        (let [{:keys [register-field get-field-display-error]} (form/make-api item-form)]
          ^{:key idx}
          [:div.item-row
           [:input (register-field :title {:placeholder "Item title"})]
           [:input (register-field :qty {:type "number" :validators [v/required]})]
           [:button {:type "button" :on-click #(remove-fn idx)} "Remove"]
           (when-let [err (get-field-display-error :qty)] [:span.error err])]))
      item-forms))
    [:button {:type "button" :on-click #(add-fn {:qty 1})} "+ Add item"]])]
```

`:element-removal-strategy` (default `:both`, or `:element-only`) controls how a removed
element's slot is treated against `:initial-values` on re-render.

## Custom controls (non-native `:on-change`)

The generated `:on-change` reads `(.. event -target -value)` — right for a plain `<input>`,
not for a control (a date picker, a `react-select`) that hands `:on-change` something else.
Override it and write straight to form state with `change-field-value`:

```clojure
[date-picker (register-field :start-date
               {:validators [v/required]
                :on-change  #(change-field-value :start-date %)})]
```

## Real-world example

A complete login form: two validated fields, an API-driven error banner, a loading state, and
a redirect once authentication succeeds (the redirect half on its own, with more context, is
in [`reagent-router`'s docs](router.md#navigation-and-url-generation) — this is the same
`login-panel`, in full):

```clojure
(ns myapp.feature.authentication.view
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [bangmod.router.core :as router]
            [bangmod.form.core :as form]
            [myapp.feature.authentication.event :as auth]
            [myapp.validators :as v]))

(defn- login-form-card [login-form]
  (let [{:keys [register-field handle-submit get-field-display-error]}
        (form/make-api login-form)
        api-err @(rf/subscribe [:auth/error])
        loading? @(rf/subscribe [:auth/loading?])
        on-submit (fn [{:keys [email password]}]
                    (auth/login! (str/lower-case (str/trim (str email)))
                                 password "client-app-id")
                    (form/create-success-submission-result))]
    [:div.login-box
     (when api-err
       [:div.banner.banner-danger
        (cond
          (str/includes? api-err "disabled") "This account has been disabled. Contact your Administrator."
          (str/includes? api-err "credentials") "Invalid email or password."
          :else api-err)])

     [:form {:on-submit (handle-submit on-submit)}
      [:div.form-group
       [:label {:for "login-email"} "Email"]
       [:input.input (register-field :email {:id "login-email" :type "email"
                                             :validators [v/required]
                                             :class (when (get-field-display-error :email) "input-error")})]
       (when-let [err (get-field-display-error :email)] [:p.error-text err])]

      [:div.form-group
       [:label {:for "login-password"} "Password"]
       [:input.input (register-field :password {:id "login-password" :type "password"
                                                 :validators [v/required]
                                                 :class (when (get-field-display-error :password) "input-error")})]
       (when-let [err (get-field-display-error :password)] [:p.error-text err])]

      [:button.btn.btn-primary {:type "submit" :disabled loading?}
       (if loading? "Logging in..." "Log in")]]]))

(defn login-panel []
  (let [login-form (form/create-form :login)
        user-sub (rf/subscribe [:auth/user])
        redirect! (fn [] (when @user-sub (router/navigate! :account)))]
    (r/create-class
     {:component-did-mount  (fn [_] (redirect!))
      :component-did-update (fn [_] (redirect!))
      :reagent-render       (fn [] [login-form-card login-form])})))
```

`auth/login!` dispatches the login request and updates `:auth/user`, `:auth/error`,
`:auth/loading?` asynchronously (typically built on [`reagent-http-api`](http-api.md)) —
`on-submit` returns success immediately since, from the form's point of view, "submitting" is
just "kick off the login"; the redirect is what reacts to it actually completing.

## Gotchas

- **The default `:on-change` assumes a native DOM change event** — see "Custom controls"
  above for anything else.
- **`:placeholder` defaults to `"Enter"`**, not to nothing — pass `""` if you want none.
- **An invalid submit touches every field and returns; `on-submit-fn` is never called.**
  There's no separate "on invalid" callback — check `get-field-display-error` /
  `get-all-fields-errors` in render.
- **`get-form-display-error` goes quiet while submitting**, so a stale error from a previous
  attempt won't flash before the new attempt's result replaces it.
- **`create-form` registers into process-global state keyed by `form-id`.** Calling it again
  with the same id silently replaces the previous form in that registry — a component still
  holding the old `ReagentForm` value keeps working, just disconnected from what `get-form`
  now resolves to elsewhere.

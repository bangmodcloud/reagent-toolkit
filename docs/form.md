# reagent-form

`io.github.bangmodcloud/reagent-form` — namespace `bangmod.form.*`

Form state, validation and submission handling for Reagent. `register-field` hands back a
ready-to-spread props map for an `[:input ...]` (value, change/blur/focus handlers, id, type
— no event wiring of your own); `handle-submit` gates submission on every field validating
first.

## Why reagent-form?

Two reasons, and they're the whole pitch.

**A field is one expression.** No action types, no per-field event handlers, no schema DSL,
nothing to register anywhere else. `register-field` returns the complete controlled-input
wiring — value, `on-change`, `on-blur`, `on-focus`, id, type — as a props map you spread
straight onto the input, and `handle-submit` is the entire submission pipeline
(validate everything → collect values → call your function → track submitting/error state):

```clojure
[:input (api/register-field login-form :email {:type "email" :validators [required]})]
```

That line is a working, validated, controlled field. The [quick start](#quick-start) below
is a complete login form and fits on one screen.

**Big forms don't re-render on every keystroke.** Field state lives in one atom, but no
component ever watches that atom directly — `register-field` sets up a
[reagent reaction](https://github.com/reagent-project/reagent/blob/master/doc/ManagingState.md)
*per field* (one for the display value, one for the display error), and a reaction only
notifies its watchers when its own output actually changes. So a keystroke in `:email`
recomputes cheap lookups for the other fields' reactions, but their outputs are unchanged —
only components that read `:email` re-render. The cost of a keystroke scales with the
components showing *that field*, not with the size of the form.

Nested forms push this further: `FieldArray` and `FieldGroup` register their aggregate value
on the parent **as a reaction, once** — typing inside a row of a 50-row array never writes
to the parent form's state at all. The parent only reads through that reaction when
something asks for the values (submit, `get-form-values`); until then the edit stays local
to the row's own sub-form. Validation churn is invisible too: error reactions emit `nil`
until a field has been touched, so re-validating untouched fields changes nothing the render
layer can see.

The one honest caveat: the granularity is your component boundaries. A single component that
renders fifty `register-field` calls still re-renders as one unit when any of them changes —
split big forms into per-section components (`FieldGroup`/`FieldArray` push you that way
anyway) and only the edited section re-renders.

## Install

See the [root README](../README.md#installation) for `deps.edn` / git-dependency snippets.

## Quick start

```clojure
(ns myapp.feature.authentication.view
  (:require [bangmod.form.core :as form]
            [bangmod.form.api :as api]
            [clojure.string :as str]))

(defn required [value]
  (when (str/blank? (str value))
    "This field is required."))

(defn login-form-card []
  (let [login-form (form/create-form :login)
        on-submit  (fn [{:keys [email password]}]
                     (js/console.log "submit:" email password)
                     (form/create-success-submission-result))]
    (fn []
      [:form {:on-submit (api/handle-submit login-form on-submit)}
       [:div.form-group
        [:label {:for "email"} "Email"]
        [:input.input (api/register-field login-form :email {:id "email" :type "email"
                                                              :validators [required]})]
        (when-let [err (api/get-field-display-error login-form :email)] [:p.error-text err])]

       [:div.form-group
        [:label {:for "password"} "Password"]
        [:input.input (api/register-field login-form :password {:id "password" :type "password"
                                                                 :validators [required]})]
        (when-let [err (api/get-field-display-error login-form :password)] [:p.error-text err])]

       [:button.btn.btn-primary {:type "submit" :disabled (api/get-is-submitting login-form)}
        (if (api/get-is-submitting login-form) "Submitting..." "Log in")]])))
```

`(form/create-form :login)` registers the form under `:login` globally (`form/get-form
:login` retrieves it elsewhere), which is why it only needs calling once, outside render.
Everything else is a function in `bangmod.form.api` that takes the form as its first
argument.

## API reference

Two namespaces. `bangmod.form.core` creates forms and holds the nested-form components;
`bangmod.form.api` is everything you do *with* a form — the `IForm` protocol, every function
taking the form as its first argument: `(api/register-field form :email {...})`.

`bangmod.form.core`:

| Function / component | Description |
| --- | --- |
| `(create-form form-id)` / `(create-form form-id {:keys [initial-values]})` | Creates and registers a form under `form-id`. `initial-values` is a map of `field-name -> value` (or anything derefable holding one — reagent atom/reaction/cursor, plain atom), used before a field is touched. |
| `(get-form form-id)` | The form registered under `form-id`, from anywhere. Throws if there is none. |
| `(create-success-submission-result)` / `(create-failed-submission-result msg)` | The two values an `on-submit` fn (passed to `handle-submit`) must produce, directly or via a `core.async` channel. |
| `FieldArray`, `FieldGroup` | Components for repeating/nested field groups — see below. |
| `(make-api form)` | Convenience: a map of the `bangmod.form.api` functions below, pre-bound to `form`, for destructuring once — see [`make-api`](#make-api-the-pre-bound-map). |

`bangmod.form.api` — `form` is always the first argument:

| Function | Description |
| --- | --- |
| `(register-field form field-name field-config)` | Registers a field, returns input props: `:value`, `:on-change`, `:on-blur`, `:on-focus`, `:id`, `:type`, `:placeholder`, plus anything else from `field-config`. See below for `field-config`. |
| `(deregister-fields form field-name-or-list)` | Removes one field (keyword) or several (collection) from form state. |
| `(get-field-display-value form field-name)` | Current value, falling back to initial value then `:default-value`. |
| `(get-field-display-error form field-name)` | Current error, or `nil` if the field hasn't been touched. |
| `(get-raw-field-value form field-name)` | Current value with no fallback. |
| `(change-field-value form field-name value)` | Sets a value, marks touched, validates. What the default `:on-change` calls. |
| `(validate-field form field-name)` | Re-runs validators against the current value. |
| `(touch form field-name)` | Marks touched (so its error becomes visible) and validates, without changing value. |
| `(get-all-fields-errors form)` | `({:field name :error err} ...)` for every field currently in error. |
| `(get-form-values form)` | A plain map of every field's current raw value — the same shape `handle-submit` passes to `on-submit-fn`, available any time, not just at submit. |
| `(validate-all-fields form)` | Touches and validates every field, returns the first error found (or `nil`). The same check `handle-submit` runs, without submitting — a "can I move to the next wizard step" check. |
| `(get-initial-values form)` | The form's `:initial-values`, as given to `create-form`. |
| `(get-is-submitting form)` | `true` while a submission is in flight. |
| `(get-form-display-error form)` | Form-level error from `create-failed-submission-result` (or from an `on-submit` that threw). `nil` while submitting. |
| `(handle-submit form on-submit-fn)` | Returns an `:on-submit` handler — see below. |

(The protocol's remaining three — `-init-form`, `make-field-subscription`,
`handle-form-submission-result` — are what the form calls on itself; nothing to call.)

Every getter that reads state (`get-field-display-value`, `get-field-display-error`,
`get-form-display-error`, ...) returns the value, not a reaction, and reading it inside a
render registers the reactive dependency — call it where you use it.

`field-config` keys for `register-field`:

- `:validators` — vector of validator functions (below). Default `[]`.
- `:default-value` — value before the field has a real or initial value.
- `:id` — defaults to `field-name`. `:type` — defaults to `"text"`. `:placeholder` — no
  default; passed through only if you provide one.
- `:on-change` / `:on-blur` / `:on-focus` — override the generated handler.

### `make-api`: the pre-bound map

When one component makes many calls against one form, `(form/make-api form)` saves
repeating the form argument: it returns a map of the `bangmod.form.api` functions above with
`form` already bound, meant to be destructured once.

```clojure
(let [{:keys [register-field handle-submit get-field-display-error]} (form/make-api login-form)]
  [:form {:on-submit (handle-submit on-submit)}
   [:input (register-field :email {:validators [v/required]})]
   (when-let [err (get-field-display-error :email)] [:span.error err])])
```

It is only a convenience layer over `bangmod.form.api` — both operate on the same
`ReagentForm`, so mixing them on one form is fine. Three functions are *not* in the map
(`get-form-values`, `validate-all-fields`, `get-initial-values`); call those through
`bangmod.form.api`. It throws if `form` isn't a `ReagentForm`.

### Writing a validator

A validator is a 1-arg function: the field's raw value in, an error (truthy, conventionally
a string) or `nil` out. Validators run in order; the first to return an error wins.

```clojure
(defn required [value]
  (when (clojure.string/blank? (str value))
    "This field is required."))
```

### Submitting

`(api/handle-submit form on-submit-fn)` returns a fn for `:on-submit`. It calls `.preventDefault`,
touches and validates every field, and — only if none now has an error — marks the form
submitting and calls `(on-submit-fn field-values)` with a plain map of every field's raw
value (destructure directly: `(fn [{:keys [email password]}] ...)`). If any field has an
error, `on-submit-fn` is never called; the errors are already visible since every field was
just touched. `on-submit-fn`'s return value — directly, or eventually via any
core.async read port — must be `(create-success-submission-result)` or
`(create-failed-submission-result msg)`; either way this clears `get-is-submitting` and, on
failure, sets `get-form-display-error` to `msg`. An `on-submit-fn` that throws (or returns
something else entirely) is treated as a failed submission — the form never sticks in a
submitting state.

## Field arrays and field groups

`FieldArray` and `FieldGroup` register a field whose value is itself a list of sub-forms or a
single nested sub-form. Both take `:form` (a form, or a form-id keyword) and `:name` (the
field name they register under on the parent), plus a render prop.

**`FieldGroup`** — render prop receives the nested form; register fields on it like any
other form:

```clojure
[form/FieldGroup {:form parent-form :name :billing-address}
 (fn [nested-form]
   [:div.address-group
    [:div.form-group
     [:label "Street"]
     [:input (api/register-field nested-form :street {:validators [v/required]})]
     (when-let [err (api/get-field-display-error nested-form :street)] [:span.error err])]])]
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
        ^{:key idx}
        [:div.item-row
         [:input (api/register-field item-form :title {:placeholder "Item title"})]
         [:input (api/register-field item-form :qty {:type "number" :validators [v/required]})]
         [:button {:type "button" :on-click #(remove-fn idx)} "Remove"]
         (when-let [err (api/get-field-display-error item-form :qty)] [:span.error err])])
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
[date-picker (api/register-field form :start-date
               {:validators [v/required]
                :on-change  #(api/change-field-value form :start-date %)})]
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
            [bangmod.form.api :as api]
            [myapp.feature.authentication.event :as auth]
            [myapp.validators :as v]))

(defn- login-form-card [login-form]
  (let [api-err @(rf/subscribe [:auth/error])
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

     [:form {:on-submit (api/handle-submit login-form on-submit)}
      [:div.form-group
       [:label {:for "login-email"} "Email"]
       [:input.input (api/register-field login-form :email
                       {:id "login-email" :type "email"
                        :validators [v/required]
                        :class (when (api/get-field-display-error login-form :email) "input-error")})]
       (when-let [err (api/get-field-display-error login-form :email)] [:p.error-text err])]

      [:div.form-group
       [:label {:for "login-password"} "Password"]
       [:input.input (api/register-field login-form :password
                       {:id "login-password" :type "password"
                        :validators [v/required]
                        :class (when (api/get-field-display-error login-form :password) "input-error")})]
       (when-let [err (api/get-field-display-error login-form :password)] [:p.error-text err])]

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
- **An invalid submit touches every field and returns; `on-submit-fn` is never called.**
  There's no separate "on invalid" callback — check `get-field-display-error` /
  `get-all-fields-errors` in render.
- **`get-form-display-error` goes quiet while submitting**, so a stale error from a previous
  attempt won't flash before the new attempt's result replaces it.
- **`create-form` registers into process-global state keyed by `form-id`.** Calling it again
  with the same id silently replaces the previous form in that registry — a component still
  holding the old `ReagentForm` value keeps working, just disconnected from what `get-form`
  now resolves to elsewhere.

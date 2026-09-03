# reagent-form

`io.github.bangmodcloud/reagent-form` — namespace `bangmod.form.*`

Form state, validation and submission handling for Reagent. A form is a stateful object you
create once per screen; `register-field` hands back a ready-to-spread props map for an
`[:input ...]` (value, change/blur/focus handlers, id, type — no event wiring of your own),
and `handle-submit` gates submission on every field validating first.

## Install

Add the artifact — see the [root README](../README.md#installation) for the full
`deps.edn` / git-dependency snippets.

## Quick start

```clojure
(ns myapp.feature.authentication.view
  (:require [bangmod.form.core :as form]
            [myapp.validators :as v]))

(defn login-form-card []
  (let [login-form (form/create-form :login)
        {:keys [register-field handle-submit get-field-display-error]}
        (form/make-api login-form)
        on-submit (fn [{:keys [email password]}]
                    (js/console.log "submit:" email password)
                    (form/create-success-submission-result))]
    (fn []
      [:form {:on-submit (handle-submit on-submit)}
       [:div.form-group
        [:label {:for "login-email"} "Email"]
        [:input.input (register-field :email
                                      {:id "login-email"
                                       :type "email"
                                       :validators [v/required]})]
        (when-let [err (get-field-display-error :email)]
          [:p.error-text err])]

       [:div.form-group
        [:label {:for "login-password"} "Password"]
        [:input.input (register-field :password
                                      {:id "login-password"
                                       :type "password"
                                       :validators [v/required]})]
        (when-let [err (get-field-display-error :password)]
          [:p.error-text err])]

       [:button.btn.btn-primary {:type "submit"} "Log in"]])))
```

`v/required` is a validator you write yourself — see "Writing a validator" below;
`(form/create-form :login)` registers the form under `:login` globally (`form/get-form :login`
retrieves it elsewhere), which is why this only needs calling once, outside the render
function.

## API reference

From `bangmod.form.core`:

- **`(create-form form-id)`** / **`(create-form form-id {:keys [initial-values]})`** —
  creates a form and registers it under `form-id` (any value usable as a map key; a keyword
  in every example above). `initial-values` is a map (or a reagent atom/reaction of one) of
  `field-name -> value`, used as a field's value before it's touched.
- **`(make-api form)`** — returns a map of functions bound to `form`, meant to be
  destructured once and spread through your component:
  `:handle-submit`, `:get-form-display-error`, `:get-is-submitting`, `:register-field`,
  `:deregister-fields`, `:get-field-display-value`, `:get-field-display-error`,
  `:get-raw-field-value`, `:get-all-fields-errors`, `:change-field-value`, `:validate-field`,
  `:touch`. Throws if `form` isn't a `ReagentForm`.
- **`(create-success-submission-result)`** / **`(create-failed-submission-result error-msg)`**
  — the two values an `on-submit` function (passed to `handle-submit`) must produce, either
  directly or as the eventual value of a `core.async` channel it returns.
- **`FieldArray`**, **`FieldGroup`** — components for repeating/nested field groups. See
  "Field arrays and field groups" below.

The functions returned by `make-api` (signatures as called once bound):

- **`register-field field-name field-config)`** — registers a field and returns a props map
  to spread onto an `[:input ...]` (or similar): `:value`, `:on-change`, `:on-blur`,
  `:on-focus`, `:id`, `:type`, `:placeholder`, plus anything else you pass through
  `field-config` (any of `field-config`'s own `:validators`, `:default-value`, `:on-change`,
  `:on-blur`, `:on-focus`, `:value`, `:id`, `:type`, `:placeholder` keys override the
  computed default for that key). `field-config`:
  - `:validators` — vector of validator functions (see below). Defaults to `[]`.
  - `:default-value` — value before the field has a real value or an initial value.
  - `:id` — defaults to `field-name`.
  - `:type` — defaults to `"text"`.
  - `:placeholder` — defaults to `"Enter"`.
- **`(deregister-fields field-name-or-list)`** — removes one field (a bare keyword) or several
  (a collection of keywords) from the form's state.
- **`(get-field-display-value field-name)`** — the field's current value, falling back to its
  initial value, then its `:default-value`.
- **`(get-field-display-error field-name)`** — the field's current validation error, or `nil`
  if the field hasn't been touched yet (so errors don't appear before the user has interacted
  with a field, or before a submit attempt has touched every field at once).
- **`(get-raw-field-value field-name)`** — the field's current value with no initial-value or
  default-value fallback.
- **`(change-field-value field-name value)`** — sets a field's value, marks it touched, and
  validates it. This is what the default `:on-change` calls.
- **`(validate-field field-name)`** — re-runs that field's validators against its current
  value and stores the result.
- **`(touch field-name)`** — marks a field touched (so its error, if any, becomes visible) and
  validates it, without changing its value.
- **`(get-all-fields-errors)`** — `({:field field-name :error error} ...)` for every field
  currently carrying an error, touched or not.
- **`(get-is-submitting)`** — `true` while a submission is in flight.
- **`(get-form-display-error)`** — the form-level error set by
  `create-failed-submission-result`, suppressed (returns `nil`) while `get-is-submitting` is
  true.
- **`(handle-submit on-submit-fn)`** — returns an event handler for `:on-submit` on a
  `[:form ...]`. See "Submitting" below.

### Writing a validator

A validator is a 1-arg function: it receives the field's raw value and returns an error
(anything truthy — conventionally a string) or `nil`/falsy for no error:

```clojure
(defn required [value]
  (when (clojure.string/blank? (str value))
    "This field is required."))
```

`:validators` is a vector; they run in order, and the first one to return a non-nil error
wins — later validators in the list don't run once one has failed.

### Submitting

`(handle-submit on-submit-fn)` returns a fn suitable for `:on-submit`. When it fires it:

1. Calls `.preventDefault` on the DOM event (if one was passed).
2. Touches and validates every registered field.
3. If any field now has an error, stops — `on-submit-fn` is never called, and the errors are
   now visible (every field was just touched).
4. Otherwise marks the form submitting and calls `(on-submit-fn field-values)`, where
   `field-values` is a plain map of `field-name -> raw value` for every registered field —
   destructure it directly, e.g. `(fn [{:keys [email password]}] ...)`.
5. `on-submit-fn`'s return value tells the form how it went: either
   `(form/create-success-submission-result)` / `(form/create-failed-submission-result msg)`
   directly, or a `core.async` channel that eventually delivers one of those two. Either way,
   this clears `get-is-submitting` and, on failure, sets `get-form-display-error` to `msg`.

### Field arrays and field groups

`FieldArray` and `FieldGroup` are Reagent components for a field whose value is itself a list
of sub-forms (`FieldArray`) or a single nested sub-form (`FieldGroup`) — a repeating group of
inputs, or a nested object, registered as one field on the parent form. Both take a `:form`
(a form value, or a form-id keyword to look up with `form/get-form`) and a `:name` (the field
name they register themselves under on the parent), and a render prop as their second
argument:

- **`[FieldGroup {:form parent-form :name :billing-address} render-fn]`** — `render-fn`
  receives the nested form (a `ReagentForm`, same as returned by `create-form`) to build its
  own `make-api` from and register fields on, same as any other form.
- **`[FieldArray {:form parent-form :name :line-items :element-removal-strategy :both}
  render-fn]`** — `render-fn` receives `(add-fn remove-fn forms)`: call `add-fn` (optionally
  with a map of initial values for the new element) to append a sub-form, `(remove-fn index)`
  to drop one, and render `forms` (a vector of `ReagentForm`s, one per element) yourself.
  `:element-removal-strategy` defaults to `:both` (only other accepted value is
  `:element-only`) and affects how a removed element's slot is treated against
  `:initial-values` on re-render.

These are advanced, lower-level pieces — there's no size-reduced example here worth padding
out; read `bangmod.form.field-array` and `bangmod.form.field-group` directly if you need them,
they're four and five short functions each.

## Real-world example

A complete login form: two validated fields, an API-driven error banner, a loading state on
the submit button, and a redirect once authentication succeeds (the redirect logic on its own
is also shown, with more context, in the
[`reagent-router` real-world example](router.md#real-world-example) — this is the same
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
          (str/includes? api-err "disabled")
          "This account has been disabled. Contact your Administrator."
          (str/includes? api-err "credentials")
          "Invalid email or password."
          :else api-err)])

     [:form {:on-submit (handle-submit on-submit)}
      [:div.form-group
       [:label {:for "login-email"} "Email"]
       [:input.input (register-field :email
                                     {:id "login-email"
                                      :type "email"
                                      :validators [v/required]
                                      :class (when (get-field-display-error :email)
                                               "input-error")})]
       (when-let [err (get-field-display-error :email)]
         [:p.error-text err])]

      [:div.form-group
       [:label {:for "login-password"} "Password"]
       [:input.input (register-field :password
                                     {:id "login-password"
                                      :type "password"
                                      :validators [v/required]
                                      :class (when (get-field-display-error :password)
                                               "input-error")})]
       (when-let [err (get-field-display-error :password)]
         [:p.error-text err])]

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

`auth/login!` here dispatches the actual login request and updates `:auth/user`,
`:auth/error` and `:auth/loading?` asynchronously (typically itself built on
[`reagent-http-api`](http-api.md)) — `on-submit` returns success immediately because, from the
form's point of view, "submission" is just "kick off the login"; the redirect above is what
actually reacts to the login completing.

## Gotchas

- **The default `:on-change` assumes a native DOM change event.** `register-field`'s
  generated `:on-change` reads `(.. event -target -value)`, which is exactly right for a
  plain `[:input ...]` or `[:textarea ...]`, but not for a custom control library that hands
  your `:on-change` something else — pass your own `:on-change` in `field-config` for those.
- **`:placeholder` defaults to `"Enter"`, not to nothing.** If you don't want a placeholder,
  pass an empty string explicitly.
- **An invalid submit touches every field and returns — `on-submit-fn` is never called.**
  There's no separate "on invalid" callback; check `get-field-display-error` /
  `get-all-fields-errors` in the render to show what's wrong, same as the example above.
- **`get-form-display-error` goes quiet while submitting.** It's suppressed for the duration
  of `get-is-submitting` being true, so a stale error from a previous attempt won't flash
  before the new attempt's own result replaces it.
- **`create-form` registers into process-global state keyed by `form-id`.** Calling it again
  with the same id silently replaces the previous form in that registry — `get-form form-id`
  from then on returns the new one. A component still holding a direct reference to the old
  `ReagentForm` value keeps working, just disconnected from whatever `get-form` now resolves
  to elsewhere. Nothing warns you if two call sites accidentally reuse the same id.

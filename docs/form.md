# reagent-form

`io.github.bangmodcloud/reagent-form` — namespace `bangmod.form.*`

Form state, validation and submission handling for Reagent. `register-field` hands back a
ready-to-spread props map for an `[:input ...]` (value, change/blur/focus handlers, id, type
— no event wiring of your own); `create-form-submission` gates submission on every field
validating first and gives your code one `dispatch` to report how it went.

## Why reagent-form?

Two reasons, and they're the whole pitch.

**A field is one expression.** No action types, no per-field event handlers, no schema DSL,
nothing to register anywhere else. `register-field` returns the complete controlled-input
wiring — value, `on-change`, `on-blur`, `on-focus`, id, type — as a props map you spread
straight onto the input, and `create-form-submission` is the entire submission pipeline
(validate everything → collect values → run your body → track submitting/error state):

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

A sign-up form: three validated fields, a cross-field check, and a request whose failure
message becomes the form's error.

```clojure
(ns myapp.feature.signup.view
  (:require [bangmod.form.core :as form]
            [bangmod.form.api :as api]
            [bangmod.http-api.core :as http-api]
            [clojure.core.async :as a]
            [clojure.string :as str]))

(defn required [value]
  (when (str/blank? (str value))
    "This field is required."))

(defn ch->promise
  "A core.async channel's first value, as a promise."
  [ch]
  (js/Promise. (fn [resolve _] (a/take! ch resolve))))

(defn signup-form-card []
  (let [signup-form (form/create-form :signup)
        on-submit
        (form/create-form-submission signup-form [_ {:keys [email password confirm-password]} dispatch]
          (if (not= password confirm-password)
            (dispatch "Passwords don't match.")
            (dispatch (-> (ch->promise (http-api/raw-execute :auth :signup
                                                             {:params {:email email :password password}}))
                          (.then (fn [{:keys [success? data]}]
                                   (when-not success?
                                     (get-in data [:response :message] "Sign-up failed."))))))))]
    (fn []
      [:form {:on-submit on-submit}
       [:div.form-group
        [:label {:for "email"} "Email"]
        [:input.input (api/register-field signup-form :email {:id "email" :type "email"
                                                               :validators [required]})]
        (when-let [err (api/get-field-display-error signup-form :email)] [:p.error-text err])]

       [:div.form-group
        [:label {:for "password"} "Password"]
        [:input.input (api/register-field signup-form :password {:id "password" :type "password"
                                                                  :validators [required]})]
        (when-let [err (api/get-field-display-error signup-form :password)] [:p.error-text err])]

       [:div.form-group
        [:label {:for "confirm-password"} "Confirm password"]
        [:input.input (api/register-field signup-form :confirm-password {:id "confirm-password" :type "password"
                                                                          :validators [required]})]
        (when-let [err (api/get-field-display-error signup-form :confirm-password)] [:p.error-text err])]

       (when-let [err (api/get-form-display-error signup-form)] [:p.error-text err])

       [:button.btn.btn-primary {:type "submit" :disabled (api/get-is-submitting signup-form)}
        (if (api/get-is-submitting signup-form) "Creating account..." "Sign up")]])))
```

`(form/create-form :signup)` registers the form under `:signup` globally (`form/get-form
:signup` retrieves it elsewhere), which is why it only needs calling once, outside render.
`create-form-submission` builds the `:on-submit` handler once, next to it. Everything else
is a function in `bangmod.form.api` that takes the form as its first argument.

## API reference

Two namespaces. `bangmod.form.core` creates forms and holds the nested-form components;
`bangmod.form.api` is everything you do *with* a form — the `IForm` protocol, every function
taking the form as its first argument: `(api/register-field form :email {...})`.

`bangmod.form.core`:

| Function / component | Description |
| --- | --- |
| `(create-form form-id)` / `(create-form form-id {:keys [initial-values]})` | Creates and registers a form under `form-id`. `initial-values` is a map of `field-name -> value` (or anything derefable holding one — reagent atom/reaction/cursor, plain atom), used before a field is touched. |
| `(get-form form-id)` | The form registered under `form-id`, from anywhere. Throws if there is none. |
| `(create-form-submission form [form values dispatch] body...)` | **Macro.** Builds the `:on-submit` handler: on a valid submit the body runs with the form, its values and a `dispatch` it must call exactly once — see [Submitting](#submitting). |
| `(create-success-submission-result)` / `(create-failed-submission-result msg)` | The two values an `on-submit` fn given to the lower-level `api/handle-submit` must produce, directly or via a `core.async` channel. Not used with `create-form-submission`. |
| `FieldArray`, `FieldGroup` | Components for repeating/nested field groups — see below. |

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
| `(get-form-values form)` | A plain map of every field's current raw value — the same map a submission body receives as `values`, available any time, not just at submit. |
| `(validate-all-fields form)` | Touches and validates every field, returns the first error found (or `nil`). The same check a submit runs, without submitting — a "can I move to the next wizard step" check. |
| `(get-initial-values form)` | The form's `:initial-values`, as given to `create-form`. |
| `(get-is-submitting form)` | `true` while a submission is in flight. |
| `(get-form-display-error form)` | Form-level error from `create-failed-submission-result` (or from an `on-submit` that threw). `nil` while submitting. |
| `(handle-submit form on-submit-fn)` | The lower-level submit handler `create-form-submission` supersedes: returns an `:on-submit` handler that calls `(on-submit-fn values)` and expects a submission result (or a core.async read port of one) back. |
| `(start-submission form)` | The gate both submit paths go through: touches every field, then — unless a submission is in flight or a field is in error — marks the form submitting and returns the values map. `nil` when it refused. Pair with `handle-form-submission-result` (`[:success]` / `[:failed msg]`) only if you are building your own submit handler. |

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

### Writing a validator

A validator is a 1-arg function: the field's raw value in, an error (truthy, conventionally
a string) or `nil` out. Validators run in order; the first to return an error wins.

```clojure
(defn required [value]
  (when (clojure.string/blank? (str value))
    "This field is required."))
```

### Submitting

```clojure
(form/create-form-submission form [form values dispatch]
  body...)
```

A macro that reads like a `fn` with the form in front, and returns the handler to put on
`:on-submit`. The binding vector is exactly three names — the form, the values map, and
`dispatch` — and each may destructure (`[_ {:keys [email password]} dispatch]` is the usual
shape).

On submit it calls `.preventDefault`, touches and validates every field, and — only if none
now has an error and no submission is already in flight — marks the form submitting and
runs the body once. If any field has an error the body never runs; the errors are already
visible since every field was just touched.

The body reports the outcome by calling `dispatch` **exactly once** with one of:

| `dispatch` argument | Meaning |
| --- | --- |
| `nil` | Success. `get-is-submitting` clears, `get-form-display-error` is `nil`. |
| a string | Failure. `get-is-submitting` clears, `get-form-display-error` becomes the string. |
| a promise | Resolves to either of the above; the form stays submitting until it settles. |

```clojure
(dispatch nil)                                   ; done
(dispatch "Passwords don't match.")              ; sync failure
(dispatch (-> (ch->promise (http-api/raw-execute :auth :login {:params values}))
              (.then (fn [{:keys [success? data]}]
                       (when-not success? (get-in data [:response :message]))))))
```

Mapping a response to `nil`-or-message is the whole integration: a promise's `.then` that
returns `nil` on success and the error string otherwise. For a core.async channel, a
two-line `ch->promise` (see the quick start) is all the bridge you need.

Everything else is a programming error, and is treated as one — the form is marked failed
with the error's message (so it never sticks in a submitting state) **and the error is
thrown**:

- `dispatch` called with anything but `nil`, a string or a promise
- `dispatch` called more than once
- the body returns without ever calling `dispatch` — the submission is unhandled
- the promise rejects, or resolves to anything but `nil` or a string (these surface as a
  rejected promise, i.e. an "Uncaught (in promise)" in the console)
- the body itself throws

The lower-level `(api/handle-submit form on-submit-fn)` still exists: `on-submit-fn` gets
the values map and must return
`(create-success-submission-result)` / `(create-failed-submission-result msg)`, directly or
via a core.async read port; a throwing `on-submit-fn` becomes a failed submission. Both
paths share `api/start-submission` and `api/handle-form-submission-result`, so their state
transitions are identical.

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
        on-submit (form/create-form-submission login-form [_ {:keys [email password]} dispatch]
                    (auth/login! (str/lower-case (str/trim (str email)))
                                 password "client-app-id")
                    (dispatch nil))]
    [:div.login-box
     (when api-err
       [:div.banner.banner-danger
        (cond
          (str/includes? api-err "disabled") "This account has been disabled. Contact your Administrator."
          (str/includes? api-err "credentials") "Invalid email or password."
          :else api-err)])

     [:form {:on-submit on-submit}
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
the body dispatches `nil` immediately since, from the form's point of view, "submitting" is
just "kick off the login"; the redirect is what reacts to it actually completing. (If you'd
rather the form own the loading and error state, dispatch a promise of the response mapped
to an error message instead, as in the quick start.)

## Gotchas

- **The default `:on-change` assumes a native DOM change event** — see "Custom controls"
  above for anything else.
- **An invalid submit touches every field and returns; the submission body never runs.**
  There's no separate "on invalid" callback — check `get-field-display-error` /
  `get-all-fields-errors` in render.
- **A submission body that never calls `dispatch` throws** — every path through the body
  must end in one `dispatch`. A `(when ...)` that falls through to `nil` without dispatching
  is the classic way to hit this.
- **`get-form-display-error` goes quiet while submitting**, so a stale error from a previous
  attempt won't flash before the new attempt's result replaces it.
- **`create-form` registers into process-global state keyed by `form-id`.** Calling it again
  with the same id silently replaces the previous form in that registry — a component still
  holding the old `ReagentForm` value keeps working, just disconnected from what `get-form`
  now resolves to elsewhere.

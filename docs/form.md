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

## Custom controls (non-native `:on-change`)

The generated `:on-change` reads `(.. event -target -value)` — right for a plain `<input>`,
not for a control that hands `:on-change` the value itself. Override it, and pass the
control whatever extra props it wants through `register-field` (unknown keys flow through).
With [react-datepicker](https://github.com/Hacker0x01/react-datepicker), which gives
`onChange` a `js/Date` and reads the current value from `selected`:

```clojure
(:require ["react-datepicker" :default DatePicker])

[:> DatePicker (api/register-field form :submit-date
                 {:date-format "dd/MM/yyyy"
                  :selected    (api/get-field-display-value form :submit-date)
                  :on-change   #(api/change-field-value form :submit-date %)})]
```

The form holds a `js/Date` (so an initial value for the field is a `js/Date` too — format
it in the submission body). `:on-blur`/`:on-focus`/`:id` reach the picker as they would an
`<input>`; the `:value` it also receives is that same `js/Date`, which react-datepicker
ignores (it only honours a string `value`).

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

On the parent, the array is one field: `(api/get-form-values parent)` gives `:items` as a
vector of row maps, and the field's validator runs `validate-all-fields` on every row, so a
row error blocks the parent's submit and surfaces as
`(api/get-field-display-error parent :items)` (the first row's first error). Values passed
to `add-fn` are set through `change-field-value`, i.e. as touched values, not as initial
values — a validator on such a field runs right away.

### Rows and `:initial-values`

The parent's `:initial-values` for the array's name is a vector of row maps. On render the
array makes sure there is at least one row per entry — `[{...} {...} {...}]` shows up as
three rows, each sub-form reading its own map as its initial values — and any rows added
past that start empty (or from what you gave `add-fn`).

That "at least one row per entry" is a live rule, re-checked on every render, which is what
makes `:initial-values` reactive for arrays: pass a reaction/atom holding the list and rows
appear as the list grows. It is also why removing a row has two possible meanings, which is
what `:element-removal-strategy` chooses between.

### Removing rows: `:element-removal-strategy`

`(remove-fn idx)` always drops the sub-form at `idx`. What happens to the initial-values
entry that row was reading is the strategy:

| Strategy | Who owns the removal | What `remove-fn` does | Use when |
| --- | --- | --- | --- |
| `:both` (default) | the array | drops the sub-form **and** hides that entry of `:initial-values` from the array, so the remaining rows keep lining up with their original entries and the slot is not re-filled | `:initial-values` is a plain value (or you never change it yourself); the array's rows *are* the truth until submit |
| `:element-only` | your code | drops the sub-form only; `:initial-values` is left alone — it is expected to lose that entry itself | `:initial-values` is a reaction over app state you update on removal (deleted on the server, removed from an atom, ...) |

With `:both`, a form created as

```clojure
(form/create-form :order {:initial-values {:items [{:title "A"} {:title "B"} {:title "C"}]}})
```

renders A, B, C; `(remove-fn 1)` leaves A and C, and they stay A and C — the array remembers
that the second entry is gone and does not re-add it from the still-three-entry
`:initial-values`. Submit produces `{:items [{:title "A"} {:title "C"}]}`.

With `:element-only`, the array removes the sub-form and trusts *you* to shrink the source,
because the "one row per entry" rule will otherwise put a row straight back:

```clojure
(defonce items (r/atom [{:title "A"} {:title "B"} {:title "C"}]))
(form/create-form :order {:initial-values (r/reaction {:items @items})})

[form/FieldArray {:form :order :name :items :element-removal-strategy :element-only}
 (fn [add-fn remove-fn item-forms]
   ...
   [:button {:type "button"
             :on-click (fn []
                         (remove-fn idx)
                         (swap! items #(into (subvec % 0 idx) (subvec % (inc idx)))))}
    "Remove"]
   ...)]
```

Do both in the same handler, synchronously: rows shift to follow their entries by position,
so if `:initial-values` still has the old entry when the next render runs, the array
re-fills to the old length — the rows after `idx` show the shifted entries, and an empty
row appears at the end once your source does shrink. (If the removal is an async server
call, remove from the local source first and reconcile after; don't wait for the response.)

Mixing them up is the failure mode to know: shrinking the source yourself under `:both`
removes the entry twice — the array hides one slot *and* the source lost one — so the row
after the removed one goes blank; leaving the source alone under `:element-only` brings the
row back.

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
| `(get-form-values form)` | A plain map of every field's current raw value — the same map a submission body receives as `values`, available any time, not just at submit. A field nobody has touched yet reads `nil` here, not its initial value (`touch` copies the initial value in; a submit touches everything first). |
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

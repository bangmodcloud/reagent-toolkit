# reagent-form

[![Clojars Project](https://img.shields.io/clojars/v/io.github.bangmodcloud/reagent-form.svg?color=blue)](https://clojars.org/io.github.bangmodcloud/reagent-form)

A declarative, zero-boilerplate form management and validation library for [Reagent](https://reagent-project.github.io/).

---

## Why reagent-form?

Managing form state in front-end SPAs often involves tedious boilerplate: binding `:value`, writing `:on-change` listeners, managing `touched` states, preventing premature error flashes, and tracking in-flight submission state.

`reagent-form` eliminates this friction:

* 🎯 **Spread-and-go props:** `register-field` returns a map (`:value`, `:on-change`, `:on-blur`, `:id`, `:type`) ready to spread directly onto any `[:input ...]`.
* 🛡️ **Polite error reporting:** Validation errors stay hidden until a field has been touched or the user attempts to submit.
* ⚡ **Async submission ready:** Return a `core.async` channel or a direct result from `on-submit`. Submitting states and disabled states are tracked automatically.
* 🧩 **Modular & composable:** First-class support for nested objects (`FieldGroup`) and dynamic repeating rows (`FieldArray`).

---

## Installation

Add to your `deps.edn`:

```clojure
io.github.bangmodcloud/reagent-form {:mvn/version "0.1.0"}
```

---

## Quick Start

```clojure
(ns myapp.views.login
  (:require [bangmod.form.core :as form]
            [clojure.string :as str]))

;; 1. Define a pure validator
(defn required [value]
  (when (str/blank? (str value))
    "This field is required."))

(defn login-form-card []
  (let [login-form (form/create-form :login)
        {:keys [register-field handle-submit get-field-display-error get-is-submitting]} 
        (form/make-api login-form)
        
        on-submit (fn [{:keys [email password]}]
                    (js/console.log "Logging in:" email password)
                    ;; Return success or a core.async channel delivering a result
                    (form/create-success-submission-result))]
    (fn []
      [:form {:on-submit (handle-submit on-submit)}
       ;; Email field
       [:div.form-group
        [:label {:for "email"} "Email"]
        [:input.input (register-field :email
                                      {:id "email"
                                       :type "email"
                                       :validators [required]})]
        (when-let [err (get-field-display-error :email)]
          [:p.error-text err])]

       ;; Password field
       [:div.form-group
        [:label {:for "password"} "Password"]
        [:input.input (register-field :password
                                      {:id "password"
                                       :type "password"
                                       :validators [required]})]
        (when-let [err (get-field-display-error :password)]
          [:p.error-text err])]

       ;; Submit button
       [:button.btn.btn-primary {:type "submit" :disabled (get-is-submitting)}
        (if (get-is-submitting) "Submitting..." "Log in")]])))
```

---

## Core Concepts

### 1. Creating a Form & Binding the API

Create a form instance outside the inner render loop:

```clojure
(let [login-form (form/create-form :login {:initial-values {:email "user@example.com"}})
      {:keys [register-field handle-submit ...]} (form/make-api login-form)]
  (fn []
    ...))
```

* `form-id`: A unique identifier (typically a keyword like `:login`). Stored globally so `(form/get-form :login)` can access it anywhere in your app.
* `options`:
  * `:initial-values` — A map (or Reagent atom/reaction of one) of `field-name -> value`.

### 2. Registering Inputs (`register-field`)

Calling `(register-field field-name field-config)` generates all DOM attributes needed for a form input:

```clojure
[:input (register-field :username
                        {:id "username"
                         :type "text"
                         :placeholder "Choose a username"
                         :validators [required min-length-4]})]
```

**Config Options:**
* `:validators` — Vector of 1-arg validator functions. Defaults to `[]`.
* `:default-value` — Fallback value when no initial or current value exists.
* `:id` — Element id (defaults to `field-name`).
* `:type` — Input type (defaults to `"text"`).
* `:placeholder` — Input placeholder (defaults to `"Enter"`).
* `:on-change` / `:on-blur` / `:on-focus` — Custom event overrides if you aren't using standard DOM inputs.

### 3. Writing Validators

A validator is a simple 1-arg function receiving the field's current value:
* Returns an **error message** (string or truthy value) if invalid.
* Returns **`nil`** (or falsy) if valid.

```clojure
(defn email? [value]
  (when-not (re-matches #".+@.+\..+" (str value))
    "Please enter a valid email address."))

(defn min-length [min-len]
  (fn [value]
    (when (< (count (str value)) min-len)
      (str "Must be at least " min-len " characters."))))
```

> **Validator Evaluation:** Validators run sequentially. The first validator to return an error terminates the check—subsequent validators for that field are skipped.

### 4. Handling Submission (`handle-submit`)

`(handle-submit on-submit-fn)` wraps your submit callback with validation guards:

```clojure
(let [on-submit (fn [values]
                  ;; `values` is a plain map: {:email "...", :password "..."}
                  (if (valid-credentials? values)
                    (form/create-success-submission-result)
                    (form/create-failed-submission-result "Invalid credentials.")))]
  [:form {:on-submit (handle-submit on-submit)} ...])
```

**Submission Lifecycle:**
1. Calls `event.preventDefault()`.
2. Marks **all** registered fields as `touched` and runs validation.
3. If any field fails validation, submission halts immediately—`on-submit-fn` is never invoked, and all validation errors become visible to the user.
4. If valid, sets `get-is-submitting` to `true` and calls `(on-submit-fn values)`.
5. Accepts either a direct result or a `core.async` channel delivering `(create-success-submission-result)` or `(create-failed-submission-result msg)`.

---

## Nested Forms and Arrays

### `FieldGroup` (Nested Maps)
Used when a section of your form corresponds to a nested map (e.g. `:shipping-address`):

```clojure
[form/FieldGroup {:form parent-form :name :shipping-address}
 (fn [nested-form]
   (let [{:keys [register-field]} (form/make-api nested-form)]
     [:div.address-group
      [:input (register-field :street {:placeholder "Street Address"})]
      [:input (register-field :city {:placeholder "City"})]]))]
```

### `FieldArray` (Dynamic Repeating Rows)
Used for repeatable sub-forms like invoice line items or dynamic tag lists:

```clojure
[form/FieldArray {:form parent-form :name :items}
 (fn [add-fn remove-fn item-forms]
   [:div
    (map-indexed
     (fn [idx item-form]
       (let [{:keys [register-field]} (form/make-api item-form)]
         ^{:key idx}
         [:div.row
          [:input (register-field :item-name)]
          [:input (register-field :quantity {:type "number"})]
          [:button {:type "button" :on-click #(remove-fn idx)} "Remove"]]))
     item-forms)
    [:button {:type "button" :on-click #(add-fn {:quantity 1})} "Add Item"]])]
```

---

## API Reference

### Top-Level (`bangmod.form.core`)

| Function / Component | Description |
| :--- | :--- |
| `(create-form form-id opts?)` | Instantiates and registers a form globally. Options: `{:initial-values {...}}`. |
| `(make-api form)` | Returns a map of bound functions for the component. |
| `(create-success-submission-result)` | Return value indicating successful submit (`[:success]`). |
| `(create-failed-submission-result msg)` | Return value indicating submission error (`[:failed msg]`). |
| `FieldGroup` | Component for nested child forms. |
| `FieldArray` | Component for repeatable array items. |

### Bound API Functions (returned by `make-api`)

| Function | Signature | Description |
| :--- | :--- | :--- |
| `register-field` | `[field-name config]` | Returns props map for `[:input ...]` (`:value`, `:on-change`, `:on-blur`, etc.). |
| `handle-submit` | `[on-submit-fn]` | Wraps submission handler with automatic validation and event prevention. |
| `get-field-display-value` | `[field-name]` | Current value with fallback to initial and default values. |
| `get-field-display-error` | `[field-name]` | Active validation error (returns `nil` if untouched). |
| `get-raw-field-value` | `[field-name]` | Raw field value without default/initial fallbacks. |
| `change-field-value` | `[field-name val]` | Programmatically updates value, touches, and validates field. |
| `touch` | `[field-name]` | Marks field touched without modifying its value. |
| `validate-field` | `[field-name]` | Re-evaluates validators against current value. |
| `deregister-fields` | `[name-or-names]` | Removes field(s) from form state. |
| `get-all-fields-errors` | `[]` | Returns list of `{:field k :error err}` for all invalid fields. |
| `get-is-submitting` | `[]` | Returns `true` while submission is pending. |
| `get-form-display-error` | `[]` | Form-level error returned from `create-failed-submission-result`. |

---

## Gotchas & Pro-Tips

> [!NOTE]
> **Native DOM Events vs Custom Components**
> The default `:on-change` generated by `register-field` extracts `(.. event -target -value)`. If you are wrapping a third-party React component that returns raw values instead of synthetic events (e.g. `(on-change 42)`), supply your own `:on-change` function in the `field-config` map:
> ```clojure
> (register-field :score {:on-change (fn [val] (change-field-value :score val))})
> ```

> [!TIP]
> **Placeholder Defaults**
> `:placeholder` defaults to `"Enter"`. If you prefer an empty placeholder, explicitly pass `:placeholder ""`.

> [!IMPORTANT]
> **Form Registry & IDs**
> `(create-form :login)` stores the instance globally under `:login`. Calling `create-form` with an identical ID replaces the previous form in the registry. Keep form IDs distinct or recreate them explicitly per route.

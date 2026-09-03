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
* 🔌 **Seamless 3rd-party integration:** Easily bind custom UI controls (Ant Design DatePickers, custom dropdowns, rich text editors).

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

## Integrating with 3rd-Party UI Components (e.g. Ant Design DatePicker)

Standard HTML `<input>` elements pass a synthetic DOM event where the value lives in `(.. event -target -value)`. 

However, modern UI libraries like **Ant Design (`antd`)**, **React-Select**, or **MUI** typically pass the raw selected value (or a Date/Day.js object) directly into `onChange`.

You can integrate these components with `reagent-form` by overriding `:on-change` using `change-field-value`:

### Example: Ant Design DatePicker Component

First, create a clean Reagent wrapper around the AntD `DatePicker`:

```clojure
(ns myapp.components.datepicker
  (:require ["antd" :refer [DatePicker]]
            ["dayjs" :as dayjs]))

(defn date-picker [{:keys [value on-change on-blur class is-showtime]}]
  [:div
   [:> DatePicker
    {:showTime    is-showtime
     :value       (when value (dayjs. value))
     :class       class
     :on-blur     (or on-blur #())
     ;; Ant Design passes (date, date-string) directly:
     :on-change   (fn [date _date-string]
                    (when on-change
                      (on-change (if date (js->clj date :keywordize-keys true) nil))))
     :style       {:width "100%"}}]])
```

### Using it inside your form:

Notice how `register-field` pairs with `change-field-value` and `get-field-display-error`:

```clojure
(defn voucher-dates-form [form-api]
  (let [{:keys [register-field change-field-value get-field-display-error]} form-api]
    [:div.form-row
     [:div.form-group
      [:label "Start Date"]
      [date-picker (register-field :startDate
                     {:validators  [v/required]
                      ;; Pass the raw date value directly to form state:
                      :on-change   #(change-field-value :startDate %)
                      :is-showtime true
                      :class       (when (get-field-display-error :startDate) "error-picker")})]
      (when-let [err (get-field-display-error :startDate)]
        [:div.text-danger err])]

     [:div.form-group
      [:label "Expire Date"]
      [date-picker (register-field :expireDate
                     {:validators  [v/required]
                      :on-change   #(change-field-value :expireDate %)
                      :is-showtime true
                      :class       (when (get-field-display-error :expireDate) "error-picker")})]
      (when-let [err (get-field-display-error :expireDate)]
        [:div.text-danger err])]]))
```

---

## Nested Forms and Arrays

### `FieldArray`: Real-World Patterns

`FieldArray` handles lists of sub-forms registered under one parent field name. 

#### Pattern A: Dynamic Add / Remove Items (e.g. Invoice Items or Hardware Slots)

```clojure
[form/FieldArray {:form parent-form :name :items}
 (fn [add-fn remove-fn item-forms]
   [:div.items-container
    (doall
     (map-indexed
      (fn [idx item-form]
        (let [{:keys [register-field get-field-display-error]} (form/make-api item-form)]
          ^{:key idx}
          [:div.item-row
           [:input.form-control (register-field :title {:placeholder "Item title"})]
           [:input.form-control (register-field :qty {:type "number" :validators [v/required v/positive]})]
           [:button.btn-danger {:type "button" :on-click #(remove-fn idx)} "Remove"]
           (when-let [err (get-field-display-error :qty)]
             [:span.error err])]))
      item-forms))

    [:button.btn-secondary {:type "button" :on-click #(add-fn {:qty 1})} "+ Add Item"]])]
```

#### Pattern B: Multi-Currency Pricing Table (Pre-populated Rows)

In multi-region enterprise applications, you often have a fixed set of supported currencies, and the user must input the price for each one:

```clojure
;; Initial values provided to create-form:
;; {:values [{:currency "THB" :price 100}
;;           {:currency "USD" :price 3.5}]}

[form/FieldArray {:form form-keyword :name :values}
 (fn [_add _remove array-forms]
   [:div.currency-table
    (doall
     (map-indexed
      (fn [idx currency-form]
        (let [{:keys [register-field get-field-display-value get-field-display-error]}
              (form/make-api currency-form)
              currency (get-field-display-value :currency)]
          ^{:key (str "currency-" idx)}
          [:div.input-group.my-2
           ;; Hidden field to preserve currency identifier
           [:input.d-none (register-field :currency {})]
           
           [:input.form-control
            (register-field :price
              {:validators  [v/required v/number (v/greater-than 0)]
               :type        "number"
               :placeholder "0.00"
               :class       (when (get-field-display-error :price) "form-error")})]
           
           [:span.input-group-text currency]
           (when-let [err (get-field-display-error :price)]
             [:div.text-danger err])]))
      array-forms))])]
```

---

### `FieldGroup`: Nested Objects

When a form field is an isolated nested map (e.g. `:billing-address` inside a user profile):

```clojure
[form/FieldGroup {:form parent-form :name :billing-address}
 (fn [nested-form]
   (let [{:keys [register-field get-field-display-error]} (form/make-api nested-form)]
     [:div.address-group
      [:div.form-group
       [:label "Street Address"]
       [:input.form-control (register-field :street {:validators [v/required]})]
       (when-let [err (get-field-display-error :street)]
         [:span.error err])]

      [:div.form-group
       [:label "City"]
       [:input.form-control (register-field :city {:validators [v/required]})]
       (when-let [err (get-field-display-error :city)]
         [:span.error err])]]))]]
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
> **Custom Components & `:on-change`**
> When binding 3rd-party components (like React DatePickers or Dropdowns), use `:on-change #(change-field-value :field-name %)` to store the raw value into the form state.

> [!TIP]
> **Placeholder Defaults**
> `:placeholder` defaults to `"Enter"`. If you prefer an empty placeholder, explicitly pass `:placeholder ""`.

> [!IMPORTANT]
> **Form Registry & IDs**
> `(create-form :login)` stores the instance globally under `:login`. Calling `create-form` with an identical ID replaces the previous form in the registry. Keep form IDs distinct or recreate them explicitly per route.

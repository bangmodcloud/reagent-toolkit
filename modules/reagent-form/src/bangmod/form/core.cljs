(ns bangmod.form.core
  (:require [bangmod.form.form :as form]
            [bangmod.form.field-array :as field-array]
            [bangmod.form.field-group :as field-group]
            [bangmod.form.api :as api]
            ;; The runtime `create-form-submission` (core.clj) expands into — required here
            ;; so it is loaded wherever the macro is used.
            [bangmod.form.submission])
  (:require-macros [bangmod.form.core]))


(defn create-form
  ([form-id]
   (form/create-form form-id {}))
  ([form-id options]
   (form/create-form form-id options)))

(def get-form
  "The form registered under `form-id`. Throws if there is none."
  form/get-form)

(def FieldArray field-array/FieldArray)
(def FieldGroup field-group/FieldGroup)

(defn create-success-submission-result
  []
  [:success])

(defn create-failed-submission-result
  [error-msg]
  [:failed error-msg])
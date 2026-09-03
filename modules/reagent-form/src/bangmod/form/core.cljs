(ns bangmod.form.core
  (:require [bangmod.form.form :as form]
            [bangmod.form.field-array :as field-array]
            [bangmod.form.field-group :as field-group]
            [bangmod.form.api :as api]))


(defn create-form
  ([form-id]
   (form/create-form form-id {}))
  ([form-id options]
   (form/create-form form-id options)))

(defn make-api [form]
  (if (satisfies? api/IForm form)
    {:handle-submit #(api/handle-submit form %1)
     :get-form-display-error #(api/get-form-display-error form)
     :get-is-submitting #(api/get-is-submitting form)
     :register-field #(api/register-field form %1 %2)
     :deregister-fields #(api/deregister-fields form %1)
     :get-field-display-value #(api/get-field-display-value form %1)
     :get-field-display-error #(api/get-field-display-error form %1)
     :get-raw-field-value #(api/get-raw-field-value form %1)
     :get-all-fields-errors #(api/get-all-fields-errors form)
     :change-field-value #(api/change-field-value form %1 %2)
     :validate-field #(api/validate-field form %1)
     :touch #(api/touch form %1)}
    (throw (js/Error. "form is not ReagentForm."))))

(def FieldArray field-array/FieldArray)
(def FieldGroup field-group/FieldGroup)

(defn create-success-submission-result
  []
  [:success])

(defn create-failed-submission-result
  [error-msg]
  [:failed error-msg])
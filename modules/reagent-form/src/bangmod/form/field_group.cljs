(ns bangmod.form.field-group
  (:require [bangmod.form.api :as api]
            [bangmod.form.form :as form]
            [reagent.ratom :as ra]))

(defn create-field-group-form
  [initial-values]
  (let [form (form/create-form {:initial-values initial-values})]
    form))

(def FieldGroup
  (fn [{:keys [form name]} _]
    (let [form (if (keyword? form)
                 (form/get-form form)
                 form)
          a-initial-values (ra/make-reaction (fn []
                                             (get-in (api/get-initial-values form) [name] {})))
          field-group-form (create-field-group-form a-initial-values)
          a-value (ra/make-reaction (fn []
                                      (api/get-form-values field-group-form)))
          validator (fn [_]
                      (let [error (api/validate-all-fields field-group-form)]
                        error))
          _ (api/register-field form name {:validators [validator]})
          _ (api/change-field-value form name a-value)]
      (fn [_ render]
        (let []
          [:<>
           (render field-group-form)]
          )))))
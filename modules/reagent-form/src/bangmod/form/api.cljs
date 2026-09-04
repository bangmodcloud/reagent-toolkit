(ns bangmod.form.api)

(defprotocol IForm
  (-init-form [this])
  (handle-submit [this on-submit-fn])
  (handle-form-submission-result [this submission-result])
  (get-is-submitting [this])
  (get-all-fields-errors [this])
  (get-form-display-error [this])
  (get-initial-values [this])
  (get-form-values [this])
  ;; FIELD OPERATIONS
  (make-field-subscription [this field-name])
  (register-field [this field-name field-config])
  (deregister-fields [this field-name-list])
  (get-field-display-value [this field-name])
  (get-field-display-error [this field-name])
  (get-raw-field-value [this field-name])
  (change-field-value [this field-name field-value])
  (validate-all-fields [this])
  (validate-field [this field-name])
  (touch [this field-name])
  )


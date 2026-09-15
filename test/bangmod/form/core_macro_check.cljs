(ns bangmod.form.core-macro-check
  "Not a test — a namespace the :lib-check build compiles to prove
   `form/create-form-submission` is reachable through `[bangmod.form.core :as form]`
   alone (core.cljs self-requires its macros) and that its binding vector destructures.
   The runtime contract is tested in `bangmod.form.submission-test`."
  (:require [bangmod.form.core :as form]
            [bangmod.form.api :as api]))

(defn on-submit [login-form]
  (form/create-form-submission login-form [f {:keys [email]} dispatch]
    (api/get-form-values f)
    (dispatch (when-not email "Email is required"))))

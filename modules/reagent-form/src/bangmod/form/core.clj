(ns bangmod.form.core
  "Macro half of `bangmod.form.core`; `core.cljs` pulls it in with `:require-macros`, so
   `[bangmod.form.core :as form]` gives `form/create-form-submission` with nothing extra.")

(defmacro create-form-submission
  "Builds a form's `:on-submit` handler. Reads like a `fn` with the form in front:

     (form/create-form-submission login-form [form values dispatch]
       (let [{:keys [email password]} values]
         (dispatch (-> (ch->promise (http-api/raw-execute :auth :login {:params {...}}))
                       (.then (fn [{:keys [success? data]}]
                                (when-not success? (:message data))))))))

   The binding vector is exactly `[form values dispatch]` — each may destructure. The body
   runs once per valid submit (every field touched, none in error, no submission already
   in flight) and must call `dispatch` exactly once with nil (success), a string (the
   form-level error) or a promise resolving to either. Anything else — another value, a
   second call, a rejected promise, a promise resolving to something else, or never
   calling `dispatch` at all — marks the form failed with the error's message and throws.
   See `bangmod.form.submission/handler` for the runtime."
  [form binding & body]
  (when-not (and (vector? binding) (= 3 (count binding)))
    (throw (ex-info (str "create-form-submission expects a binding vector of exactly "
                         "[form values dispatch], got " (pr-str binding))
                    {:binding binding})))
  (let [[form-sym values-sym dispatch-sym] binding]
    `(bangmod.form.submission/handler
       ~form
       (fn [~form-sym ~values-sym ~dispatch-sym] ~@body))))

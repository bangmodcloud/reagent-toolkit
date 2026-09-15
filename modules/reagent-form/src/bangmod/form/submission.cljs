(ns bangmod.form.submission
  "The runtime behind `bangmod.form.core/create-form-submission`.

   Kept free of reagent on purpose: it only speaks `IForm`, so the dispatch contract below
   is unit-tested under `:node-test` against a reified form, which the reagent-backed
   `ReagentForm` cannot be."
  (:require [bangmod.form.api :as api]))

(defn- thenable? [x]
  (and (some? x) (fn? (.-then x))))

(defn- error-message [e]
  (or (some-> e .-message) (str e)))

(defn- bad-outcome-message [what outcome]
  (str "create-form-submission: " what " nil (success), a string (error message) or a "
       "promise of either, got " (pr-str outcome)))

(defn handler
  "`(handler form (fn [form values dispatch] ...))` — the `:on-submit` handler the macro
   builds. On submit it prevents the event's default, then goes through
   `api/start-submission` (touch all, refuse while submitting or with a field in error,
   mark submitting). If that started, `f` runs once with the form, its values, and
   `dispatch`.

   `dispatch` is how `f` reports the outcome, exactly once:
     nil       — success
     string    — failure; becomes `get-form-display-error`
     promise   — resolves to either of the above
   Anything else, a second call, a rejected promise, a promise resolving to anything else,
   or `f` returning without ever calling `dispatch` (the submission is unhandled) is a
   programming error: the form is marked failed with the error's message — so it never
   sticks in submitting — and the error is thrown. For a promise that means the returned
   promise rejects.

   Returns nil, or for a promise outcome the chained promise (so a test can await it)."
  [form f]
  (fn [event]
    (when event (.preventDefault event))
    (when-let [values (api/start-submission form)]
      (let [dispatched (volatile! ::none)
            fail!      (fn [msg] (api/handle-form-submission-result form [:failed msg]))
            fail-throw (fn [msg data]
                         (fail! msg)
                         (throw (ex-info msg (assoc data :form form))))
            settle!    (fn [outcome]
                         (cond
                           (nil? outcome)    (api/handle-form-submission-result form [:success])
                           (string? outcome) (fail! outcome)
                           :else (fail-throw (bad-outcome-message
                                               "a promise given to dispatch must resolve to"
                                               outcome)
                                             {:outcome outcome})))
            dispatch   (fn [outcome]
                         ;; Thrown, not fail-throw'n: the throw crosses `f` and lands in
                         ;; the catch below, which marks the form failed once.
                         (when-not (keyword-identical? ::none @dispatched)
                           (throw (ex-info "create-form-submission: dispatch called more than once"
                                           {:form form :outcome outcome})))
                         (when-not (or (nil? outcome) (string? outcome) (thenable? outcome))
                           (throw (ex-info (bad-outcome-message "dispatch expects" outcome)
                                           {:form form :outcome outcome})))
                         (vreset! dispatched outcome)
                         nil)]
        (try
          (f form values dispatch)
          (catch :default e
            (fail! (error-message e))
            (throw e)))
        (let [outcome @dispatched]
          (cond
            (keyword-identical? ::none outcome)
            (fail-throw "create-form-submission: dispatch was never called — the submission is unhandled"
                        {:values values})

            (thenable? outcome)
            (.then outcome
                   settle!
                   (fn [err]
                     (fail! (error-message err))
                     (throw err)))

            :else
            (do (settle! outcome) nil)))))))

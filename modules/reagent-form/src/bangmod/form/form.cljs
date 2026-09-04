(ns bangmod.form.form
  (:require [bangmod.form.api :as api :refer [IForm]]
            [reagent.core :as r]
            [reagent.ratom :as ra]
            [clojure.core.async :as async]
            [cljs.core.async.impl.protocols :as async-protocols]))

(defn atom? [subject]
  ;; Anything derefable counts: reagent Reaction/RAtom/RCursor, plain cljs atoms, delays.
  (satisfies? IDeref subject))

(deftype ReagentForm [a-fields ^:mutable all-validators a-form a-initial-values ^:mutable subscription]
  IForm
  (get-form-values [this]
    (into {} (for [[k v] @a-fields]
               (let [value (get-in v [:value])
                     value (if (atom? value)
                             @value
                             value)]
                 [k value]))))
  (make-field-subscription [this field-name]
    (when (nil? (get-in subscription [:field :display-value field-name]))
      (set! subscription (assoc-in subscription [:field :value field-name] (ra/cursor a-fields [field-name :value])))
      (set! subscription (assoc-in subscription [:field :display-value field-name] (ra/make-reaction #(or (get-in @a-fields [field-name :value])
                                                                                                          (get-in @a-initial-values [field-name])
                                                                                                          (get-in @a-fields [field-name :default-value])))))
      (set! subscription (assoc-in subscription [:field :display-error field-name] (ra/make-reaction #(when (get-in @a-fields [field-name :touched])
                                                                                                        (get-in @a-fields [field-name :error])))))))
  (get-raw-field-value [this field-name]
    (api/make-field-subscription this field-name)
    (let [value @(get-in subscription [:field :value field-name])]
      (if (atom? value)
        @value
        value)))
  (get-field-display-value [this field-name]
    (api/make-field-subscription this field-name)
    (let [value @(get-in subscription [:field :display-value field-name])]
      (if (atom? value)
        @value
        value)))
  (get-field-display-error [this field-name]
    (api/make-field-subscription this field-name)
    @(get-in subscription [:field :display-error field-name]))
  (get-all-fields-errors [this]
    (->> @a-fields (map (fn [[fields-name {:keys [error]}]]
                          (when error {:field fields-name :error error}))) (remove nil?)))

  (register-field [this field-name {:keys [validators default-value on-change value on-blur id type placeholder on-focus] :as field-config}]
    (set! all-validators (assoc all-validators field-name (or validators [])))
    (if (nil? (get @a-fields field-name))
      (swap! a-fields assoc field-name {:default-value default-value
                                        :error nil
                                        :value nil
                                        :touched false})
      ;; Re-registration happens on every render; only write when something changed, so
      ;; render passes don't ping the ratom's watchers for nothing.
      (when (not= default-value (get-in @a-fields [field-name :default-value]))
        (swap! a-fields assoc-in [field-name :default-value] default-value)))
    (api/make-field-subscription this field-name)
    ;; No :placeholder default on purpose — a library must not put words in the UI.
    ;; Whatever the caller passed (placeholder included) flows through the merge.
    (merge
      (apply dissoc field-config [:validators :default-value :on-change :value])
      {:value     (or value
                      (api/get-field-display-value this field-name))
       :on-focus  (or on-focus
                      #(api/touch this field-name))
       :on-change (or on-change
                      #(->> % .-target .-value (api/change-field-value this field-name)))
       :on-blur   (or on-blur
                      #(api/validate-field this field-name))
       :id (or id field-name)
       :type (or type "text")}))
  (deregister-fields [this field-name-list]
    (let [names (if (coll? field-name-list) field-name-list [field-name-list])]
      (set! all-validators (apply dissoc all-validators names))
      (swap! a-fields (fn [fields] (apply dissoc fields names)))))
  (change-field-value [this field-name field-value]
    (swap! a-fields (fn [fields]
                      (-> fields
                          (assoc-in [field-name :value] field-value)
                          (assoc-in [field-name :touched] true))))
    (api/validate-field this field-name))
  (touch [this field-name]
    (when-not (get-in @a-fields [field-name :touched])
      (swap! a-fields (fn [fields]
                        (-> fields
                            (assoc-in [field-name :value] (or (get-in fields [field-name :value])
                                                              (get-in @a-initial-values [field-name])
                                                              (get-in fields [field-name :default-value])))
                            (assoc-in [field-name :touched] true)))))
    (api/validate-field this field-name))
  (validate-field [this field-name]
    (let [field-value (api/get-raw-field-value this field-name)]
      (loop [validators (get all-validators field-name)
             error nil]
        (if (or (some? error) (empty? validators))
          (swap! a-fields (fn [fields] (assoc-in fields [field-name :error] error)))
          (recur (rest validators)
                 ((first validators) field-value))))))
  (validate-all-fields [this]
    (doseq [field-name (keys @a-fields)]
      (api/touch this field-name))
    (->> (keys @a-fields)
         (some (fn [e] (get-in @a-fields [e :error])))))
  (-init-form [this]
    (set! subscription (assoc-in subscription [:form :display-error] (ra/make-reaction #(when-not (get-in @a-form [:is-submitting])
                                                                                          (get-in @a-form [:error]))))))
  (get-initial-values [this]
    @a-initial-values)
  (get-form-display-error [this]
    ;; Deref'd here so it behaves like get-field-display-error: the caller gets the value,
    ;; and reading it inside a render still registers the reactive dependency.
    @(get-in subscription [:form :display-error]))
  (get-is-submitting [this]
    (get-in @a-form [:is-submitting]))

  (handle-form-submission-result [this result-bundle]
    (let [[result form-error-msg] (if (sequential? result-bundle)
                                    result-bundle
                                    [result-bundle])]
      (case result
        :success (swap! a-form (fn [form]
                                 (-> form
                                     (assoc-in [:error] nil)
                                     (assoc-in [:is-submitting] false))))
        (:fail :failed) (swap! a-form (fn [form]
                                        (-> form
                                            (assoc-in [:error] (if (nil? form-error-msg)
                                                                 "Form submission error with no error message !!"
                                                                 form-error-msg))
                                            (assoc-in [:is-submitting] false))))
        ;; A wrong result must not leave the form stuck submitting — surface it as a form
        ;; error and log it, instead of throwing (which used to freeze :is-submitting).
        (let [msg (str "Wrong result from form submission: " (pr-str result-bundle))]
          (js/console.error msg)
          (swap! a-form (fn [form]
                          (-> form
                              (assoc-in [:error] msg)
                              (assoc-in [:is-submitting] false))))))))
  (handle-submit [this on-submit-fn]
    (fn [event]
      (when event
        (.preventDefault event))
      ;; touch all fields to validate
      (doseq [field-name (keys @a-fields)]
        (api/touch this field-name))
      (when-not (get-in @a-form [:is-submitting])
        (let [has-field-error? (some (fn [[_ field-data]]
                                       (some? (get-in field-data [:error])))
                                     @a-fields)]
          (when-not has-field-error?
            (swap! a-form (fn [form]
                            (-> form
                                (assoc-in [:is-submitting] true)
                                (assoc-in [:error] nil))))
            (let [values (api/get-form-values this)
                  ;; A throwing on-submit is a failed submission, not a frozen form.
                  result (try (on-submit-fn values)
                              (catch :default e
                                (js/console.error "on-submit threw:" e)
                                [:failed (or (some-> e .-message) (str e))]))]
              (if (satisfies? async-protocols/ReadPort result)
                (async/go (api/handle-form-submission-result this (async/<! result)))
                (api/handle-form-submission-result this result)))))))))

(defonce ^:private forms (atom {}))

(defn get-form
  [form-id]
  (let [form (get-in @forms [form-id])]
    (when (nil? form)
      (throw (js/Error. (str "Form with id=" form-id " not found."))))
    form))

(defn- coerce-initial-values [initial-values]
  (cond
    (atom? initial-values) initial-values
    (nil? initial-values) (r/atom {})
    (map? initial-values) (r/atom initial-values)
    :else (throw (js/Error. (str "initial-values must be a map or something derefable"
                                 " (reagent atom/reaction/cursor, plain atom), got: "
                                 (pr-str initial-values))))))

(defn create-form
  ([form-id {:keys [initial-values]}]
   (let [form (->ReagentForm (r/atom {}) {} (r/atom {}) (coerce-initial-values initial-values) {})]
     (api/-init-form form)
     (swap! forms (fn [v] (assoc-in v [form-id] form)))
     (get-form form-id)))
  ([{:keys [initial-values]}]
   (let [form (->ReagentForm (r/atom {}) {} (r/atom {}) (coerce-initial-values initial-values) {})]
     (api/-init-form form)
     form)))

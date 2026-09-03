(ns bangmod.form.form
  (:require [bangmod.form.api :as api :refer [IForm]]
            [reagent.core :as r]
            [reagent.ratom :as ra]
            [clojure.core.async :as async]
            [clojure.core.async.impl.channels :as async-channel]
            [reagent.ratom :as ratom]
            ))

(defn atom? [subject]
  (or (instance? ra/Reaction subject)
      (instance? ra/RAtom subject)
      (instance? ra/RCursor subject)))

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
    (set! all-validators (assoc-in all-validators [field-name] (or validators [])))
    (if (nil? (get-in @a-fields [field-name]))
      (swap! a-fields (fn [fields]
                        (assoc-in fields [field-name] {:default-value default-value
                                                       :type nil
                                                       :error nil
                                                       :value nil
                                                       :touched false
                                                       :id nil})))
      (swap! a-fields (fn [fields]
                        (assoc-in fields [field-name :default-value] default-value))))
    (api/make-field-subscription this field-name)
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
       :type (or type "text")
       :placeholder (or placeholder "Enter")}))
  (deregister-fields [this field-name-list]
    (swap! a-fields (fn [fields]
                      (apply dissoc fields (if (coll? field-name-list)
                                             field-name-list
                                             (vector field-name-list))))))
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
                            (assoc-in [field-name :value] (or (get-in @a-fields [field-name :value])
                                                              (get-in @a-initial-values [field-name])
                                                              (get-in @a-fields [field-name :default-value])))
                            (assoc-in [field-name :touched] true)))))
    (api/validate-field this field-name))
  (validate-field [this field-name]
    (let [field-value (api/get-raw-field-value this field-name)]
      (loop [validators (get-in all-validators [field-name])
             error nil]
        (if (or (-> error nil? not) (empty? validators))
          (swap! a-fields (fn [fields] (assoc-in fields [field-name :error] error)))
          (recur (rest validators)
                 ((first validators) field-value))))))
  (validate-all-fields [this]
    (doseq [field-name (keys @a-fields)]
      (api/touch this field-name))
    (->> (keys @a-fields)
         (some (fn [e] (get-in @a-fields [e :error])))
         ))
  (-init-form [this]
    (set! subscription (assoc-in subscription [:form :display-error] (ra/make-reaction #(when-not (get-in @a-form [:is-submitting])
                                                                                          (get-in @a-form [:error]))))))
  (get-initial-values [this]
    @a-initial-values)
  (get-form-display-error [this]
    (get-in subscription [:form :display-error]))
  (get-is-submitting [this]
    (get-in @a-form [:is-submitting]))

  (handle-form-submission-result [this result-bundle]
    (let [[result form-error-msg] result-bundle]
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
        (throw (js/Error. "Wrong result from form submission: " result)))))
  (handle-submit [this on-submit-fn]
    (fn [event]
      ;; prevent default
      (when event
        (.preventDefault event))
      ;; touch all fields to validate
      (loop [field-names (map first @a-fields)]
        (when-not (empty? field-names)
          (api/touch this (first field-names))
          (recur (rest field-names))))
      ;; GO !!
      (when-not (get-in @a-form [:is-submitting])
        (let [has-field-error? (some (fn [[_ field-data]]
                                       (-> (get-in field-data [:error]) nil? not))
                                     @a-fields)]
          (when-not has-field-error?
            (swap! a-form (fn [form]
                            (-> form
                                (assoc-in [:is-submitting] true)
                                (assoc-in [:error] nil))))
            (let [values (api/get-form-values this)
                  result (on-submit-fn values)]
              (if (instance? async-channel/ManyToManyChannel result)
                (async/go (api/handle-form-submission-result this (async/<! result)))
                (api/handle-form-submission-result this result)))))))))

(def ^:private forms (atom {}))

(defn get-form
  [form-id]
  (let [form (get-in @forms [form-id])]
    (when (nil? form)
      (throw (js/Error. (str "Form with id=" form-id " not found."))))
    form))



(defn create-form
  ([form-id {:keys [initial-values]}]
   (let [a-initial-value (if (atom? initial-values)
                           initial-values
                           (if (nil? initial-values)
                             (r/atom {})
                             (if (map? initial-values)
                               (r/atom initial-values)
                               (do
                                 (throw (js/Error. (str "initial value must be a map or Atom like instance")))))))
         form (->ReagentForm (r/atom {}) {} (r/atom {}) a-initial-value {})]
     (api/-init-form form)
     (swap! forms (fn [v] (assoc-in v [form-id] form)))
     (get-form form-id)))
  ([{:keys [initial-values]}]
   (let [a-initial-value (if (or (instance? ra/Reaction initial-values)
                                 (instance? ra/RAtom initial-values)
                                 (instance? ra/RCursor initial-values))
                           initial-values
                           (if (nil? initial-values)
                             (r/atom {})
                             (if (map? initial-values)
                               (r/atom initial-values)
                               (do
                                 (cljs.pprint/pprint initial-values)
                                 (throw (js/Error. (str "initial value must be a map or Atom like instance")))))))
         form (->ReagentForm (r/atom {}) {} (r/atom {}) a-initial-value {})]
     (api/-init-form form)
     form)))



;(defn submit-form-submission-result
;  [form-id result]
;  (let [form (get-form form-id)]
;    (handle-form-submission-result form result)))
(ns bangmod.form.field-array
  (:require [bangmod.form.api :as api]
            [bangmod.form.form :as form]
            [reagent.core :as r]
            [reagent.ratom :as ra]))

(defn create-field-array-form
  [initial-values]
  (let [form (form/create-form {:initial-values initial-values})]
    form))

(def FieldArray
  (fn [{:keys [form name element-removal-strategy]} _]
    (let [a-field-array-forms (r/atom [])
          a-field-array-form-size (ra/make-reaction (fn []
                                                      (count @a-field-array-forms)))
          a-added-array-form-count (r/atom 0)
          a-deleted-nths (r/atom [])
          form (if (keyword? form)
                 (form/get-form form)
                 form)
          element-removal-strategy (or element-removal-strategy :both)
          _ (when-not (some #(= element-removal-strategy %) [:both :element-only])
              (throw (js/Error. (str "Unsupported element-removal-strategy " element-removal-strategy))))
          validator (fn [_]
                      (let [errors (map (fn [form]
                                          (api/validate-all-fields form))
                                        @a-field-array-forms)
                            errors (filter (fn [error] (-> error nil? not))
                                           errors)]
                        (if (empty? errors)
                          nil
                          (first errors))))
          _ (api/register-field form name {:validators [validator]})
          a-value (ra/make-reaction (fn []
                                      (vec (map (fn [form]
                                                  (api/get-form-values form))
                                                @a-field-array-forms))))
          _ (api/change-field-value form name a-value)
          a-filtered-initial-values  (ra/make-reaction (fn []
                                                         (let [parent-initial-values (get-in (api/get-initial-values form) [name] [])]
                                                           (if (= :both element-removal-strategy)
                                                             (reduce (fn [acc v]
                                                                       (concat
                                                                         (take v acc)
                                                                         (drop (inc v) acc)))
                                                                     parent-initial-values
                                                                     @a-deleted-nths)
                                                             parent-initial-values))
                                                         ))
          remove-fn (fn [index]
                      (swap! a-deleted-nths (fn [old-value]
                                              (concat old-value [index])))
                      (swap! a-field-array-forms (fn [old-value]
                                                   (concat (subvec (vec old-value) 0 index)
                                                           (subvec (vec old-value) (inc index))))))
          add-fn (fn [& args]
                   (swap! a-field-array-forms (fn [old-value]
                                                (let [n @a-added-array-form-count
                                                      _ (swap! a-added-array-form-count inc)
                                                      a-initial-values (ra/make-reaction (fn []
                                                                                           (let [deleted-nths @a-deleted-nths
                                                                                                 actual-n (reduce (fn [acc v]
                                                                                                                    (if (<= v acc)
                                                                                                                      (dec acc)
                                                                                                                      acc))
                                                                                                                  n
                                                                                                                  deleted-nths)
                                                                                                 filtered-initial-values  @a-filtered-initial-values]
                                                                                             (nth filtered-initial-values actual-n {}))))
                                                      form (create-field-array-form a-initial-values)]
                                                  (let [[v] args]
                                                    (when (and (-> v nil? not) (map? v))
                                                      (doseq [vk (keys v)]
                                                        (api/change-field-value form vk (get v vk)))))
                                                  (concat old-value [form])))))
          ]
      (fn [_ render]
        (let []
          [:<>
           (let [form-size @a-field-array-form-size
                 initial-value-size (count  @a-filtered-initial-values)
                 diff (- initial-value-size form-size)]
             (when (> diff 0)
               (dotimes [n diff]
                 (add-fn))))
           (render add-fn remove-fn @a-field-array-forms)]
          )))))



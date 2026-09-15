(ns bangmod.form.submission-test
  (:require [cljs.test :refer [deftest is testing async]]
            [bangmod.form.api :as api]
            [bangmod.form.submission :as submission]))

;; A form that only knows how to start a submission and record how it ended — the two
;; `IForm` calls `handler` makes. `log` collects every result it receives.
(defn- fake-form
  ([] (fake-form {:email "a@b.c"}))
  ([values]
   (let [log (atom [])]
     {:log log
      :form (reify api/IForm
              (start-submission [_] values)
              (handle-form-submission-result [_ result] (swap! log conj result)))})))

(defn- run
  "Builds the handler for `f` on a fresh fake form, fires it with a stub event, and
   returns [log return-value]."
  [f & [values]]
  (let [{:keys [log form]} (if values (fake-form values) (fake-form))
        prevented (atom false)
        ret ((submission/handler form f) #js {:preventDefault #(reset! prevented true)})]
    (is @prevented "the event's default is prevented")
    [@log ret log]))

(deftest sync-outcomes-test
  (testing "dispatch nil is success"
    (is (= [[:success]] (first (run (fn [_ _ dispatch] (dispatch nil)))))))

  (testing "dispatch a string is failure with that message"
    (is (= [[:failed "Wrong password"]]
           (first (run (fn [_ _ dispatch] (dispatch "Wrong password")))))))

  (testing "the body receives the form and the values start-submission produced"
    (let [seen (atom nil)
          {:keys [form]} (fake-form {:x 1})]
      ((submission/handler form (fn [f v dispatch] (reset! seen [f v]) (dispatch nil))) nil)
      (is (= {:x 1} (second @seen)))
      (is (identical? form (first @seen)))))

  (testing "a nil event is tolerated"
    (let [{:keys [log form]} (fake-form)]
      ((submission/handler form (fn [_ _ dispatch] (dispatch nil))) nil)
      (is (= [[:success]] @log)))))

(deftest gate-test
  (testing "when start-submission refuses, the body never runs"
    (let [ran (atom false)
          form (reify api/IForm (start-submission [_] nil))]
      ((submission/handler form (fn [_ _ _] (reset! ran true))) nil)
      (is (false? @ran)))))

(deftest contract-violations-test
  (testing "never calling dispatch throws and marks the form failed"
    (let [{:keys [log form]} (fake-form)
          h (submission/handler form (fn [_ _ _] :forgot))]
      (is (thrown-with-msg? js/Error #"dispatch was never called" (h nil)))
      (is (= 1 (count @log)))
      (is (= :failed (ffirst @log)))))

  (testing "dispatching anything but nil / string / promise throws at the call site"
    (let [{:keys [log form]} (fake-form)
          h (submission/handler form (fn [_ _ dispatch] (dispatch {:oops true})))]
      (is (thrown-with-msg? js/Error #"dispatch expects" (h nil)))
      (is (= [[:failed (str "create-form-submission: dispatch expects nil (success), a string "
                            "(error message) or a promise of either, got {:oops true}")]]
             @log))))

  (testing "a submission result vector is not an outcome either"
    (let [{:keys [form]} (fake-form)
          h (submission/handler form (fn [_ _ dispatch] (dispatch [:success])))]
      (is (thrown-with-msg? js/Error #"dispatch expects" (h nil)))))

  (testing "calling dispatch twice throws"
    (let [{:keys [log form]} (fake-form)
          h (submission/handler form (fn [_ _ dispatch] (dispatch nil) (dispatch "again")))]
      (is (thrown-with-msg? js/Error #"more than once" (h nil)))
      (is (= [[:failed "create-form-submission: dispatch called more than once"]] @log))))

  (testing "a body that throws marks the form failed with its message and rethrows"
    (let [{:keys [log form]} (fake-form)
          h (submission/handler form (fn [_ _ _] (throw (js/Error. "boom"))))]
      (is (thrown-with-msg? js/Error #"boom" (h nil)))
      (is (= [[:failed "boom"]] @log)))))

(deftest promise-outcomes-test
  (async done
    (let [resolved-nil (let [{:keys [log form]} (fake-form)]
                         (-> ((submission/handler form (fn [_ _ dispatch] (dispatch (js/Promise.resolve nil)))) nil)
                             (.then (fn [_] (is (= [[:success]] @log))))))
          resolved-str (let [{:keys [log form]} (fake-form)]
                         (-> ((submission/handler form (fn [_ _ dispatch] (dispatch (js/Promise.resolve "Taken")))) nil)
                             (.then (fn [_] (is (= [[:failed "Taken"]] @log))))))
          rejected     (let [{:keys [log form]} (fake-form)]
                         (-> ((submission/handler form (fn [_ _ dispatch] (dispatch (js/Promise.reject (js/Error. "network"))))) nil)
                             (.then (fn [_] (is false "a rejected promise must propagate"))
                                    (fn [err]
                                      (is (= "network" (.-message err)))
                                      (is (= [[:failed "network"]] @log))))))
          bad-resolve  (let [{:keys [log form]} (fake-form)]
                         (-> ((submission/handler form (fn [_ _ dispatch] (dispatch (js/Promise.resolve 42)))) nil)
                             (.then (fn [_] (is false "a promise resolving to a non-outcome must reject"))
                                    (fn [err]
                                      (is (re-find #"must resolve to" (.-message err)))
                                      (is (= 1 (count @log)))
                                      (is (= :failed (ffirst @log)))))))]
      (-> (js/Promise.all #js [resolved-nil resolved-str rejected bad-resolve])
          (.then (fn [_] (done))
                 (fn [err] (is false (str "unexpected: " err)) (done)))))))

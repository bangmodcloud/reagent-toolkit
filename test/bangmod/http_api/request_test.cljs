(ns bangmod.http-api.request-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.core.async :as a]
            [bangmod.http-api.request :as request]
            [bangmod.http-api.retry :as retry]))

(def ^:private path [:api :endpoint])

(defn- endpoint
  "A fresh slot store and counter, plus a log of every value the slot has held — so a test
   can assert a value was never written, not merely that it was overwritten."
  []
  (let [a-data (atom {})
        written (atom [])]
    (add-watch a-data ::log (fn [_ _ old new]
                              (when (not= (get-in old path) (get-in new path))
                                (swap! written conj (get-in new path)))))
    {:a-data a-data :a-sent (atom {}) :written written}))

(defn- send!
  "Sends one request through a stub transport that answers nothing by itself: each
   attempt's `on-result` lands on `:attempts` for the test to answer, in whatever order it
   chooses."
  [{:keys [a-data a-sent]}]
  (let [attempts (a/chan 10)]
    {:attempts attempts
     :result   (request/send! {:a-data a-data :a-sent a-sent :path path
                               :fire!  (fn [on-result] (a/put! attempts on-result))})}))

(defn- ok [data] {:success? true :data data})
(defn- failed [status] {:success? false :data {:status status :response {:reason "boom"}}})
(def ^:private stale {:success? false :data {:status 401 :response {:reason "token-stale"}}})

(defn- slot [{:keys [a-data]}] (get-in @a-data path))

(deftest numbering-test
  (let [a-sent (atom {})]
    (is (= 1 (request/number! a-sent [:x :e])))
    (is (= 2 (request/number! a-sent [:x :e])))
    (is (= 1 (request/number! a-sent [:x :other])) "one counter per endpoint")
    (is (= 1 (request/number! a-sent [:y :e])) "one counter per api, too")
    (is (request/latest? a-sent [:x :e] 2))
    (is (not (request/latest? a-sent [:x :e] 1)))))

(deftest later-request-answers-first-test
  (async done
    (a/go
      (let [ep (endpoint)
            a  (send! ep)
            b  (send! ep)
            answer-a (a/<! (:attempts a))
            answer-b (a/<! (:attempts b))]
        (answer-b (ok "B"))
        (is (= (ok "B") (a/<! (:result b))) "B's channel gets B")
        (is (= (ok "B") (slot ep)))
        (answer-a (ok "A"))
        (is (= (ok "A") (a/<! (:result a))) "A's channel still gets A")
        (is (= (ok "B") (slot ep)) "A, answering late, does not replace B")
        (is (= [(ok "B")] @(:written ep)))
        (done)))))

(deftest earlier-request-answers-first-test
  (async done
    (a/go
      (let [ep (endpoint)
            _  (swap! (:a-data ep) assoc-in path (ok "before"))
            _  (reset! (:written ep) [])
            a  (send! ep)
            b  (send! ep)
            answer-a (a/<! (:attempts a))
            answer-b (a/<! (:attempts b))]
        (answer-a (ok "A"))
        (is (= (ok "A") (a/<! (:result a))) "A's channel gets A")
        (is (= (ok "before") (slot ep)) "while B is in flight the slot keeps its previous value")
        (answer-b (ok "B"))
        (is (= (ok "B") (a/<! (:result b))) "B's channel gets B")
        (is (= (ok "B") (slot ep)))
        (is (= [(ok "B")] @(:written ep)) "A was never written into the slot")
        (done)))))

(deftest single-request-success-test
  (async done
    (a/go
      (let [ep (endpoint)
            r  (send! ep)]
        ((a/<! (:attempts r)) (ok {:id 1}))
        (is (= (ok {:id 1}) (a/<! (:result r))))
        (is (= (ok {:id 1}) (slot ep)))
        (done)))))

(deftest single-request-failure-test
  (async done
    (a/go
      (testing "with nothing stored yet, the slot is just the failure"
        (let [ep (endpoint)
              r  (send! ep)]
          ((a/<! (:attempts r)) (failed 500))
          (is (= (failed 500) (a/<! (:result r))) "the channel gets the raw result")
          (is (= {:success? false :error {:status 500 :response {:reason "boom"}}} (slot ep)))))
      (testing "the latest request fails after an earlier good answer: the good :data stays"
        (let [ep (endpoint)
              r1 (send! ep)]
          ((a/<! (:attempts r1)) (ok {:id 1}))
          (a/<! (:result r1))
          (let [r2 (send! ep)]
            ((a/<! (:attempts r2)) (failed 500))
            (is (= (failed 500) (a/<! (:result r2))))
            (is (= {:success? false :data {:id 1} :error {:status 500 :response {:reason "boom"}}}
                   (slot ep))))))
      (done))))

(deftest superseded-request-fails-late-test
  (async done
    (a/go
      (let [ep (endpoint)
            a  (send! ep)
            b  (send! ep)
            answer-a (a/<! (:attempts a))
            answer-b (a/<! (:attempts b))]
        (answer-b (ok "B"))
        (a/<! (:result b))
        (answer-a (failed 500))
        (is (= (failed 500) (a/<! (:result a))) "A's channel still gets A's failure")
        (is (= (ok "B") (slot ep)) "the slot is unchanged — no :success? false, no :error")
        (done)))))

(defn- with-stale-handler
  "Runs `body-fn` (returning a channel) with a token-stale handler registered, restoring the
   previous one when it closes."
  [body-fn]
  (let [prev @retry/token-stale-handler]
    (retry/set-token-stale-handler! (fn [] (doto (a/promise-chan) (a/put! true))))
    (a/go (a/<! (body-fn))
          (retry/set-token-stale-handler! prev))))

(deftest latest-request-token-stale-retry-test
  (async done
    (a/go
      (a/<! (with-stale-handler
              #(a/go
                 (let [ep (endpoint)
                       r  (send! ep)]
                   ((a/<! (:attempts r)) stale)
                   (is (nil? (slot ep)) "the stale 401 itself is not delivered")
                   ((a/<! (:attempts r)) (ok "retried"))
                   (is (= (ok "retried") (a/<! (:result r))))
                   (is (= (ok "retried") (slot ep)) "the retry keeps the request's number")))))
      (done))))

(deftest superseded-request-token-stale-retry-test
  (async done
    (a/go
      (a/<! (with-stale-handler
              #(a/go
                 (let [ep (endpoint)
                       a  (send! ep)
                       answer-a (a/<! (:attempts a))
                       b  (send! ep)]
                   (answer-a stale)
                   ((a/<! (:attempts b)) (ok "B"))
                   (a/<! (:result b))
                   ((a/<! (:attempts a)) (ok "A retried"))
                   (is (= (ok "A retried") (a/<! (:result a))))
                   (is (= (ok "B") (slot ep)) "A's retry is still A — superseded by B")))))
      (done))))

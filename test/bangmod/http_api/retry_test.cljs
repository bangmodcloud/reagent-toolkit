(ns bangmod.http-api.retry-test
  (:require [cljs.test :refer [deftest is testing]]
            [bangmod.http-api.retry :as retry]))

(deftest reload-before-reopen-test
  (testing "only a stale token needs a reload before re-opening the stream"
    (is (true? (retry/reload-before-reopen? "token-stale"))))
  (testing "the reasons a plain re-open already recovers from"
    (is (false? (retry/reload-before-reopen? "token-expired")))
    (is (false? (retry/reload-before-reopen? "subscriber-dropped")))
    (is (false? (retry/reload-before-reopen? nil)))))

(deftest token-stale-401?-test
  (let [stale {:success? false :data {:status 401 :response {:reason "token-stale"}}}]
    (is (true? (retry/token-stale-401? stale)))

    (testing "a 401 for any other reason is the caller's to handle"
      (is (false? (retry/token-stale-401?
                    {:success? false :data {:status 401 :response {:reason "unauthorized"}}})))
      (is (false? (retry/token-stale-401?
                    {:success? false :data {:status 401 :response {}}}))))

    (testing "a different status never qualifies, whatever the body says"
      (is (false? (retry/token-stale-401?
                    {:success? false :data {:status 403 :response {:reason "token-stale"}}}))))

    (testing "a successful response never qualifies"
      (is (false? (retry/token-stale-401? (assoc stale :success? true)))))))

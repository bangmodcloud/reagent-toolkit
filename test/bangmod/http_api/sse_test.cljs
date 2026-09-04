(ns bangmod.http-api.sse-test
  (:require [cljs.test :refer [deftest is testing]]
            [bangmod.http-api.sse :as sse]))

(deftest replace-path-params-test
  (is (= "/api/leaves/42" (sse/replace-path-params "/api/leaves/:id" {:id 42})))
  (is (= "/api/a/1/b/2" (sse/replace-path-params "/api/a/:x/b/:y" {:x 1 :y 2})))
  (testing "no params leaves the uri untouched"
    (is (= "/api/leaves" (sse/replace-path-params "/api/leaves" nil))))
  (testing "a param name that is a prefix of another cannot corrupt it"
    (is (= "/x/1/2" (sse/replace-path-params "/x/:id/:idx" {:id 1 :idx 2})))
    (is (= "/x/2/1" (sse/replace-path-params "/x/:idx/:id" {:id 1 :idx 2}))))
  (testing "values are percent-encoded so an id cannot change the path shape"
    (is (= "/api/p/a%20b%2Fc" (sse/replace-path-params "/api/p/:id" {:id "a b/c"})))))

(deftest stream-url-test
  (testing "base-url, path params, query params and the token all land in one url"
    (is (= "https://api.example.com/stream/7?since=10&access_token=abc"
           (sse/stream-url "https://api.example.com" "/stream/:id" {:id 7}
                           {:since 10} "abc"))))
  (testing "an existing query string is appended to, not replaced"
    (is (= "/stream?a=1&access_token=abc"
           (sse/stream-url nil "/stream?a=1" nil nil "abc"))))
  (testing "no params and no token means no query string at all"
    (is (= "/stream" (sse/stream-url nil "/stream" nil nil nil))))
  (testing "values are percent-encoded"
    (is (= "/stream?q=a%20b&access_token=a%2Fb"
           (sse/stream-url nil "/stream" nil {:q "a b"} "a/b")))))

(deftest state-transitions-test
  (let [s0 (sse/initial-state)]
    (is (= {:sse? true :connected? false :last-message nil :message-count 0 :error nil} s0))

    (testing "message-count increments even when the payload repeats"
      (let [s1 (sse/apply-message s0 {:x 1})
            s2 (sse/apply-message s1 {:x 1})]
        (is (= 1 (:message-count s1)))
        (is (= 2 (:message-count s2)))
        (is (true? (:connected? s2)))))

    (testing "an error disconnects but keeps the counter"
      (let [s (-> s0 (sse/apply-message {:x 1}) (sse/mark-error "boom"))]
        (is (= false (:connected? s)))
        (is (= "boom" (:error s)))
        (is (= 1 (:message-count s)))))

    (testing "re-opening clears the error"
      (let [s (-> s0 (sse/mark-error "boom") sse/mark-open)]
        (is (true? (:connected? s)))
        (is (nil? (:error s)))))

    (testing "every transition tolerates a nil state"
      (is (= 1 (:message-count (sse/apply-message nil {:x 1}))))
      (is (true? (:connected? (sse/mark-open nil))))
      (is (= "boom" (:error (sse/mark-error nil "boom")))))))

(deftest backoff-test
  (is (= [1000 2000 4000 8000 16000 30000] (mapv sse/backoff-ms (range 6))))
  (testing "capped at 30s, and stays capped"
    (is (= 30000 (sse/backoff-ms 6)))
    (is (= 30000 (sse/backoff-ms 50)))))

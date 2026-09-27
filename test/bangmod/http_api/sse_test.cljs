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
  (testing "base-url, path params and query params land in one url"
    (is (= "https://api.example.com/stream/7?since=10"
           (sse/stream-url "https://api.example.com" "/stream/:id" {:id 7} {:since 10}))))
  (testing "an existing query string is appended to, not replaced"
    (is (= "/stream?a=1&b=2" (sse/stream-url nil "/stream?a=1" nil {:b 2}))))
  (testing "no params means no query string at all"
    (is (= "/stream" (sse/stream-url nil "/stream" nil nil))))
  (testing "values are percent-encoded"
    (is (= "/stream?q=a%20b" (sse/stream-url nil "/stream" nil {:q "a b"})))))

(deftest stream-url-never-carries-a-token-test
  (testing "no token in the URL — a stream authenticates by request header"
    (doseq [url [(sse/stream-url nil "/stream" nil nil)
                 (sse/stream-url "https://api.example.com" "/s/:id" {:id 1} {:since 10})
                 (sse/stream-url nil "/s?a=1" nil {:b 2})]]
      (is (not (re-find #"(?i)access_token|token" url)) url))))

(defn- feed-all
  "Feeds `chunks` in order through one parser; returns [final-state all-events]."
  ([chunks] (feed-all (sse/parser) chunks))
  ([state chunks]
   (reduce (fn [[st evs] chunk]
             (let [[st' new-evs] (sse/feed st chunk)]
               [st' (into evs new-evs)]))
           [state []]
           chunks)))

(defn- events-of [chunks] (second (feed-all chunks)))

(deftest parser-basics-test
  (testing "one data line, dispatched on the blank line, default name \"message\""
    (is (= [{:type "message" :data "hello" :id nil}]
           (events-of ["data: hello\n\n"]))))
  (testing "nothing is dispatched until the blank line"
    (let [[st evs] (sse/feed (sse/parser) "data: hello\n")]
      (is (= [] evs))
      (is (= [{:type "message" :data "hello" :id nil}] (second (sse/feed st "\n"))))))
  (testing "multi-line data joins with \\n"
    (is (= ["a\nb\nc"] (map :data (events-of ["data: a\ndata: b\ndata:c\n\n"])))))
  (testing "only ONE leading space is stripped after the colon"
    (is (= ["  x"] (map :data (events-of ["data:   x\n\n"]))))
    (is (= ["x"] (map :data (events-of ["data:x\n\n"])))))
  (testing "an empty data field still counts as data"
    (is (= [""] (map :data (events-of ["data\n\n"]))))
    (is (= ["\n"] (map :data (events-of ["data:\ndata:\n\n"])))))
  (testing "a blank line with no data dispatches nothing"
    (is (= [] (events-of ["\n\n\n"])))
    (is (= [] (events-of ["event: changed\n\n"])))))

(deftest parser-comments-test
  (testing "comment lines are ignored — a heartbeat is not an event"
    (is (= [] (events-of [": ping\n\n" ":\n\n"]))))
  (testing "a comment inside an event does not interrupt it"
    (is (= [{:type "message" :data "a\nb" :id nil}]
           (events-of ["data: a\n: keep-alive\ndata: b\n\n"])))))

(deftest parser-named-event-test
  (testing "event: names the next dispatch"
    (is (= [{:type "changed" :data "{\"x\":1}" :id nil}]
           (events-of ["event: changed\ndata: {\"x\":1}\n\n"]))))
  (testing "the name resets after each dispatch"
    (is (= ["reconnect" "message"]
           (map :type (events-of ["event: reconnect\ndata: {}\n\ndata: y\n\n"])))))
  (testing "unknown fields are ignored"
    (is (= [{:type "message" :data "x" :id nil}]
           (events-of ["foo: bar\ndata: x\n\n"])))))

(deftest parser-id-and-retry-test
  (testing "id: is carried on the event and persists into later ones"
    (is (= ["7" "7" "8"]
           (map :id (events-of ["id: 7\ndata: a\n\ndata: b\n\nid: 8\ndata: c\n\n"])))))
  (testing "the last id is on the state, even when the event carried no data"
    (is (= "9" (:last-id (first (feed-all ["id: 9\n\n"]))))))
  (testing "an empty id: resets it"
    (is (= "" (:last-id (first (feed-all ["id: 9\n\nid\n\n"]))))))
  (testing "an id containing NULL is ignored"
    (is (= "1" (:last-id (first (feed-all ["id: 1\n\nid: a\u0000b\n\n"]))))))
  (testing "a parser started with a previous connection's id keeps it"
    (is (= ["5"] (map :id (second (feed-all (sse/parser "5") ["data: x\n\n"]))))))
  (testing "retry: digits only, in ms"
    (is (= 2500 (:retry (first (feed-all ["retry: 2500\n\n"])))))
    (is (nil? (:retry (first (feed-all ["retry: 2.5\n\n"])))))
    (is (nil? (:retry (first (feed-all ["retry: soon\n\n"])))))
    (is (= 100 (:retry (first (feed-all ["retry: 100\n\nretry: x\n\n"])))))))

(deftest parser-line-endings-test
  (let [want [{:type "changed" :data "a\nb" :id "1"}]]
    (testing "LF, CRLF and CR all end a line"
      (is (= want (events-of ["id: 1\nevent: changed\ndata: a\ndata: b\n\n"])))
      (is (= want (events-of ["id: 1\r\nevent: changed\r\ndata: a\r\ndata: b\r\n\r\n"])))
      (is (= want (events-of ["id: 1\revent: changed\rdata: a\rdata: b\r\r"]))))
    (testing "mixed in one stream"
      (is (= want (events-of ["id: 1\r\nevent: changed\rdata: a\ndata: b\r\n\n"]))))))

(deftest parser-chunk-boundaries-test
  (let [stream "id: 1\r\nevent: changed\r\ndata: a\r\n: hb\r\ndata: b\r\n\r\ndata: second\r\n\r\n"
        want [{:type "changed" :data "a\nb" :id "1"}
              {:type "message" :data "second" :id "1"}]]
    (testing "the whole stream in one chunk"
      (is (= want (events-of [stream]))))
    (testing "split at every position — mid-line, mid-event, between a CR and its LF"
      (doseq [i (range (inc (count stream)))]
        (is (= want (events-of [(subs stream 0 i) (subs stream i)])) (str "split at " i))))
    (testing "one character per chunk"
      (is (= want (events-of (map str stream))))))
  (testing "a CR ending one chunk and a LF opening the next are ONE line end"
    ;; Two line ends would be a blank line, dispatching `a` on its own.
    (is (= ["a\nb"] (map :data (events-of ["data: a\r" "\ndata: b\r\n\r\n"]))))
    (is (= ["a\nb"] (map :data (events-of ["data: a\r" "" "\ndata: b\n\n"])))))
  (testing "after the CR's own LF, a further LF is a line end again"
    (is (= ["a"] (map :data (events-of ["data: a\r" "\n" "\n"])))))
  (testing "an event unfinished when the stream stops is never dispatched"
    (is (= [] (events-of ["data: half"])))
    (is (= [] (events-of ["data: half\n"]))))
  (testing "a leading BOM is dropped, even arriving in a chunk of its own"
    (is (= ["x"] (map :data (events-of ["﻿data: x\n\n"]))))
    (is (= ["x"] (map :data (events-of ["﻿" "data: x\n\n"]))))))

(deftest response-action-test
  (testing "200 + text/event-stream opens"
    (is (= :open (sse/response-action 200 "text/event-stream" nil)))
    (is (= :open (sse/response-action 200 "text/event-stream; charset=utf-8" nil)))
    (is (= :open (sse/response-action 200 "Text/Event-Stream" nil))))
  (testing "200 with anything else is not a stream"
    (is (= :backoff (sse/response-action 200 "application/json" nil)))
    (is (= :backoff (sse/response-action 200 nil nil))))
  (testing "401 token-stale reloads the token"
    (is (= :reload-token (sse/response-action 401 "application/json" "token-stale"))))
  (testing "any other 401 ends the session"
    (is (= :session-over (sse/response-action 401 "application/json" "unauthorized")))
    (is (= :session-over (sse/response-action 401 nil nil))))
  (testing "other statuses back off, whatever the body says"
    (is (= :backoff (sse/response-action 503 "text/html" nil)))
    (is (= :backoff (sse/response-action 403 "application/json" "token-stale")))
    (is (= :backoff (sse/response-action 204 nil nil))))
  (testing "a network error (no status) backs off"
    (is (= :backoff (sse/response-action nil nil nil)))))

(deftest state-transitions-test
  (let [s0 (sse/initial-state)]
    (is (= {:sse? true :connected? false :data nil :last-message nil :message-count 0 :error nil}
           s0))

    (testing "the latest frame lands under :data, same key as an execute slot"
      (is (= {:x 1} (:data (sse/apply-message s0 {:x 1})))))

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

(deftest reconnect-delay-test
  (testing "no retry: from the server — the backoff alone"
    (is (= 1000 (sse/reconnect-delay 0 nil)))
    (is (= 4000 (sse/reconnect-delay 2 nil))))
  (testing "retry: is a floor"
    (is (= 5000 (sse/reconnect-delay 0 5000)))
    (is (= 60000 (sse/reconnect-delay 10 60000))))
  (testing "never below the backoff"
    (is (= 8000 (sse/reconnect-delay 3 10)))))

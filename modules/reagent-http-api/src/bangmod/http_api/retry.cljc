(ns bangmod.http-api.retry
  "The decision half of the stale-token retry, kept free of the ajax transport so it can be
   unit-tested (`:node-test` has no XMLHttpRequest, so anything requiring `ajax.core` is
   untestable there — the same reason `sse.cljc` is separate)."
  (:require [clojure.core.async :as a]
            #?(:cljs [cljs.core.async.impl.protocols :as async-protocols]
               :clj  [clojure.core.async.impl.protocols :as async-protocols])))

(def stale-token-reason
  "The one 401 — and the one SSE `reconnect` reason — that means \"reload your token and
   retry\" rather than \"log in again\". One string, both transports."
  "token-stale")

(defn reload-before-reopen?
  "Must the token be reloaded before re-opening an SSE stream the server closed with this
   `reconnect` reason?

   `token-expired` and `subscriber-dropped` are recovered by re-opening — the client's token
   provider already holds a usable token in both cases. `token-stale` is not: the re-open
   re-reads the SAME token, the server refuses it again, the connection closes, and the
   backoff turns into a hot loop against a credential that will never become valid."
  [reason]
  (= stale-token-reason reason))

;; Optional 0-arg fn returning a channel that delivers once a fresh token is in hand.
;; Registered by the app's auth feature; with none registered a stale-token 401 is handed to
;; the caller unchanged, exactly as every 401 was before.
(defonce token-stale-handler (atom nil))

(defonce ^:private in-flight (atom nil))

(defn set-token-stale-handler! [f]
  (reset! token-stale-handler f))

(defn token-stale-401?
  "Is this `execute` result the server saying the token is merely out of date?

   cljs-ajax puts the parsed body of a FAILED request under [:data :response], not [:data] —
   spelled out here so that read has exactly one home."
  [result]
  (and (not (:success? result))
       (= 401 (get-in result [:data :status]))
       (= stale-token-reason (get-in result [:data :response :reason]))))

;; Optional 1-arg fn called with the failed result when a request comes back 401 for a
;; reason other than `token-stale` — the session is over, not merely out of date. Registered
;; by the app's auth feature (typically: forget the token, remember the route, go to login).
;; With none registered the 401 is handed to the caller unchanged.
(defonce unauthorized-handler (atom nil))

(defn set-unauthorized-handler! [f]
  (reset! unauthorized-handler f))

(defn session-over-401?
  "Is this `execute` result a 401 that a token reload cannot fix — every 401 that is not
   `token-stale`? Those two are the only 401s the module distinguishes: one is retried once
   after a reload, this one is the session ending."
  [result]
  (and (not (:success? result))
       (= 401 (get-in result [:data :status]))
       (not (token-stale-401? result))))

(defn reload-token!
  "The single in-flight token reload, as a channel. Concurrent stale requests park on the
   same promise-chan instead of each firing their own refresh — a burst of parallel requests
   would otherwise be a refresh storm.

   The registered handler must not itself issue a request that can come back `token-stale`,
   or it would park on the reload it is inside — serve the refresh endpoint without the auth
   check that produces `token-stale`."
  []
  (or @in-flight
      (let [p (a/promise-chan)]
        (if (compare-and-set! in-flight nil p)
          (do (a/go
                (when-let [handler @token-stale-handler]
                  (let [res (handler)]
                    ;; A handler answering with something other than a channel has already
                    ;; done its work; taking from it would throw inside a go block, where
                    ;; nothing would report it.
                    (when (satisfies? async-protocols/ReadPort res)
                      (a/<! res))))
                (a/put! p true)
                (reset! in-flight nil))
              p)
          (or @in-flight (doto (a/promise-chan) (a/put! true)))))))

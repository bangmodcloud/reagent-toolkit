(ns bangmod.http-api.sse
  "The testable half of `:method :sse`: URL building and the `a-data` state transitions.

   It exists as its own namespace because `bangmod.http-api.internal` requires `ajax.core`,
   which touches `js/XMLHttpRequest` at load time and so may not load under `:node-test`.
   Keeping the pure code out of there is what lets the reconnect POLICY be unit-tested even
   though the transport itself can only be verified in a browser."
  (:require [clojure.string :as str]))

(defn percent-encode [s]
  #?(:cljs (js/encodeURIComponent (str s))
     :clj (java.net.URLEncoder/encode (str s) "UTF-8")))

(defn replace-path-params
  "`:param` placeholders in `uri` filled from `path-params` — the same rule
   `bangmod.http-api.internal` applies to a request URI."
  [uri path-params]
  (reduce-kv (fn [u k v] (str/replace u (str ":" (name k)) (str v)))
             uri
             (or path-params {})))

(defn- query-string [params token]
  (let [pairs (cond-> (vec (for [[k v] params]
                             (str (percent-encode (name k)) "=" (percent-encode v))))
                token (conj (str "access_token=" (percent-encode token))))]
    (when (seq pairs) (str/join "&" pairs))))

(defn stream-url
  "The full URL an `EventSource` is opened against.

   `EventSource` cannot set request headers, so the bearer token travels as
   `?access_token=` (RFC 6750 §2.3) — the one route in this API that accepts it."
  [base-url uri path-params params token]
  (let [path (str (or base-url "") (replace-path-params uri path-params))
        qs (query-string params token)]
    (if qs
      (str path (if (str/includes? path "?") "&" "?") qs)
      path)))

;; --- the `a-data` value, which `make-reaction` REPLACES ----------------------
;;
;; `:message-count` is the field that earns its place: two consecutive messages can be `=` (a
;; snapshot resent after a reconnect, or a value that changed and changed back), and a
;; reaction over an equal value does not re-fire — without the counter that second message
;; would be invisible to any consumer reading `a-data` reactively. Appending to a vector was
;; the alternative and grows without bound on a long-lived connection.

(defn initial-state []
  {:sse? true :connected? false :last-message nil :message-count 0 :error nil})

(defn apply-message [state data]
  (assoc (or state (initial-state))
         :sse? true
         :connected? true
         :last-message data
         :message-count (inc (:message-count state 0))
         :error nil))

(defn mark-open [state]
  (assoc (or state (initial-state)) :sse? true :connected? true :error nil))

(defn mark-error [state message]
  (assoc (or state (initial-state)) :sse? true :connected? false :error message))

(defn backoff-ms
  "Reconnect delay for `attempt` (0-based), capped at 30 s. Used only for a `CLOSED`
   EventSource — a `CONNECTING` one is already retrying on the server's `retry:` field."
  [attempt]
  (min 30000 (* 1000 (bit-shift-left 1 (min attempt 5)))))

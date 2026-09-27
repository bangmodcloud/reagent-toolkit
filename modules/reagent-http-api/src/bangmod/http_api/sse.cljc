(ns bangmod.http-api.sse
  "The testable half of `:method :sse`: URL building, the event-stream parser, the
   response -> action decision and the `a-data` state transitions.

   It exists as its own namespace because `bangmod.http-api.internal` requires `ajax.core`,
   which touches `js/XMLHttpRequest` at load time and so may not load under `:node-test`.
   Keeping the pure code out of there is what lets the parser and the reconnect POLICY be
   unit-tested even though the transport itself (`fetch` + a stream reader) can only be
   verified in a browser."
  (:require [clojure.string :as str]
            [bangmod.http-api.retry :as retry]))

(defn percent-encode [s]
  #?(:cljs (js/encodeURIComponent (str s))
     ;; URLEncoder is form-encoding: it turns a space into "+", which is wrong in a
     ;; path/query segment — normalize to %20 to match encodeURIComponent.
     :clj (str/replace (java.net.URLEncoder/encode (str s) "UTF-8") "+" "%20")))

(defn replace-path-params
  "`:param` placeholders in `uri` filled from `path-params` — the one rule for both request
   URIs (`bangmod.http-api.internal`) and stream URLs.

   Longest name substituted first, so `:id` never matches inside `:idx`; values are
   percent-encoded, so an id containing `/` or a space cannot change the path shape."
  [uri path-params]
  (->> (or path-params {})
       (sort-by (fn [[k _]] (count (name k))) >)
       (reduce (fn [u [k v]]
                 (str/replace u (str ":" (name k)) (percent-encode (str v))))
               uri)))

(defn- query-string [params]
  (when (seq params)
    (str/join "&" (for [[k v] params]
                    (str (percent-encode (name k)) "=" (percent-encode v))))))

(defn stream-url
  "The full URL a stream is opened against: base URL, path params, query params — and
   never a credential. The token goes out in a request header, put there by the same
   injector `execute` uses."
  [base-url uri path-params params]
  (let [path (str (or base-url "") (replace-path-params uri path-params))
        qs (query-string params)]
    (if qs
      (str path (if (str/includes? path "?") "&" "?") qs)
      path)))

;; --- the event-stream parser --------------------------------------------------
;;
;; WHATWG HTML §9.2.6 ("Interpreting an event stream"), fed one decoded chunk at a time. A
;; chunk boundary can fall anywhere — mid-line, mid-event, between the CR and LF of one
;; CRLF — so everything not yet terminated is carried in the state:
;;
;;   :buf       the unterminated tail of the last chunk
;;   :skip-lf?  the last chunk ended on a CR, so a LF opening the next one is its other half
;;   :data      the data lines of the event being assembled
;;   :event     its `event:` name, nil until one is seen
;;   :last-id   the last event ID — persists across events, as the spec says
;;   :retry     the latest valid `retry:` in ms, nil until one is seen
;;   :bom?      nothing has been read yet, so a leading U+FEFF is still to be dropped
;;
;; An event still being assembled when the stream ends is never dispatched (spec: it is
;; discarded), which falls out of only dispatching on a blank line.

(defn parser
  "A fresh parser state. `last-id` carries the last event ID over from a previous
   connection, which the spec keeps across reconnects."
  ([] (parser nil))
  ([last-id]
   {:buf "" :skip-lf? false :bom? true :data [] :event nil :last-id last-id :retry nil}))

(defn- digits? [s]
  (boolean (re-matches #"[0-9]+" s)))

(defn- parse-int [s]
  #?(:cljs (js/parseInt s 10)
     :clj  (Long/parseLong s)))

(defn- process-field [state field value]
  (case field
    "data"  (update state :data conj value)
    "event" (assoc state :event value)
    ;; An id containing NULL is ignored outright (spec) — it could not go in a header.
    "id"    (if (str/includes? value "\u0000") state (assoc state :last-id value))
    ;; Digits only, else ignored (spec).
    "retry" (if (digits? value) (assoc state :retry (parse-int value)) state)
    state))

(defn- process-line
  "One complete line -> [state event-or-nil]."
  [state line]
  (cond
    (= "" line)
    ;; Dispatch. No data lines means nothing to dispatch, but the event name still resets.
    [(assoc state :data [] :event nil)
     (when (seq (:data state))
       {:type (if (str/blank? (:event state)) "message" (:event state))
        :data (str/join "\n" (:data state))
        :id   (:last-id state)})]

    (str/starts-with? line ":")
    [state nil]                                     ; a comment — servers' heartbeat

    :else
    (let [i (str/index-of line ":")
          field (if i (subs line 0 i) line)
          value (if i (subs line (inc i)) "")
          value (if (str/starts-with? value " ") (subs value 1) value)]
      [(process-field state field value) nil])))

(defn- line-end
  "Index of the first CR or LF in `s` at or after `from`, or nil."
  [s from]
  (let [cr (str/index-of s "\r" from)
        lf (str/index-of s "\n" from)]
    (if (and cr lf) (min cr lf) (or cr lf))))

(defn- char-at= [s i c]
  (and (< i (count s)) (= c (subs s i (inc i)))))

(defn feed
  "Feeds one decoded text chunk to the parser: `(feed state chunk) -> [state events]`.
   Each event is `{:type <event name, default \"message\"> :data <string> :id <last event
   ID or nil>}`, in stream order. Lines end on CRLF, LF or CR."
  [state chunk]
  (if (= "" chunk)
    [state []]
    (let [chunk (if (and (:skip-lf? state) (str/starts-with? chunk "\n")) (subs chunk 1) chunk)
          s (str (:buf state) chunk)
          ;; A UTF-8 BOM may open the stream, and only the stream.
          s (if (and (:bom? state) (str/starts-with? s "\uFEFF")) (subs s 1) s)
          bom? (and (:bom? state) (= "" s))
          n (count s)]
      (loop [st (assoc state :bom? bom? :skip-lf? false)
             from 0
             events []]
        (if-let [i (line-end s from)]
          (let [cr? (char-at= s i "\r")
                crlf? (and cr? (char-at= s (inc i) "\n"))
                [st ev] (process-line st (subs s from i))
                ;; A CR as the very last character may be the first half of a CRLF split
                ;; across chunks: the line ends here either way, and a LF opening the next
                ;; chunk is skipped.
                st (assoc st :skip-lf? (and cr? (= (inc i) n)))]
            (recur st (+ i (if crlf? 2 1)) (if ev (conj events ev) events)))
          [(assoc st :buf (subs s from)) events])))))

;; --- the response -> action decision --------------------------------------------

(defn response-action
  "What to do with a stream's HTTP response. `status` nil means the request never got
   one (a network error); `reason` is the `reason` field of a 401's JSON body, if any.

     :open          200 + text/event-stream — read it
     :reload-token  401 token-stale — reload the token, then re-open
     :session-over  any other 401 — the session is over, do not re-open
     :backoff       anything else — mark the error, re-open on the backoff"
  [status content-type reason]
  (cond
    (and (= 200 status)
         (some-> content-type str/lower-case str/trim (str/starts-with? "text/event-stream")))
    :open

    (and (= 401 status) (= retry/stale-token-reason reason)) :reload-token
    (= 401 status) :session-over
    :else :backoff))

;; --- the `a-data` value, which `make-reaction` REPLACES ----------------------
;;
;; `:data` is the latest frame, under the same key an `execute` slot uses for its body, so a
;; component bound to `(:data @x)` reads the same way whether `x` came from a request or a
;; stream. `:last-message` is the same value under its older name.
;;
;; `:message-count` is the field that earns its place: two consecutive messages can be `=` (a
;; snapshot resent after a reconnect, or a value that changed and changed back), and a
;; reaction over an equal value does not re-fire — without the counter that second message
;; would be invisible to any consumer reading `a-data` reactively. Appending to a vector was
;; the alternative and grows without bound on a long-lived connection.

(defn initial-state []
  {:sse? true :connected? false :data nil :last-message nil :message-count 0 :error nil})

(defn apply-message [state data]
  (assoc (or state (initial-state))
         :sse? true
         :connected? true
         :data data
         :last-message data
         :message-count (inc (:message-count state 0))
         :error nil))

(defn mark-open [state]
  (assoc (or state (initial-state)) :sse? true :connected? true :error nil))

(defn mark-error [state message]
  (assoc (or state (initial-state)) :sse? true :connected? false :error message))

(defn backoff-ms
  "Reconnect delay for `attempt` (0-based), capped at 30 s. `attempt` is reset by every
   successful open, so a stream that was healthy re-opens after 1 s."
  [attempt]
  (min 30000 (* 1000 (bit-shift-left 1 (min attempt 5)))))

(defn reconnect-delay
  "The delay before re-open number `attempt`: the backoff, raised to the server's `retry:`
   (ms) when it sent one — the server's value is a floor, never a way to reconnect faster
   than the backoff allows."
  [attempt retry-ms]
  (max (backoff-ms attempt) (or retry-ms 0)))

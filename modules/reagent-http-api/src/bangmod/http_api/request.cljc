(ns bangmod.http-api.request
  "One `execute` request end to end — numbered, sent, retried once on a stale token, then
   delivered to its own channel and (if it is still the latest) to the endpoint's slot —
   with the transport passed in. Kept free of `ajax.core` and reagent so it can be
   unit-tested: `:node-test` has no XMLHttpRequest (the same reason `retry.cljc` and
   `sse.cljc` are separate)."
  (:require [clojure.core.async :as a]
            [bangmod.http-api.retry :as retry]))

(defn number!
  "Numbers a new request to the endpoint at `path` ([api-name endpoint-name]) — one counter
   per endpoint in `a-sent` — and returns its number. That number is the endpoint's latest
   until the next request to it is numbered."
  [a-sent path]
  (get-in (swap! a-sent update-in path (fnil inc 0)) path))

(defn latest?
  "Is request `n` still the latest sent to the endpoint at `path`?"
  [a-sent path n]
  (= n (get-in @a-sent path)))

(defn into-slot
  "The slot's next value once `result` arrives. The slot keeps the last GOOD :data across
   failures — a UI bound to the reaction must not go blank because one refresh failed. The
   failure itself lands under :error."
  [prev result]
  (if (:success? result)
    result
    (assoc (or prev {}) :success? false :error (:data result))))

(defn send!
  "Sends one request and returns a `chan 1` that receives its result.

   `fire!` is the transport: `(fn [on-result])`, called once per attempt, sending the
   request (rebuilt, so a retry carries the reloaded token) and calling `on-result` with
   {:success? bool :data ...} when it answers.

   The request is numbered for the endpoint at `path` in `a-sent` before it is sent. Its
   result goes into the slot in `a-data` only while that number is still the latest — an
   answer to a superseded request never replaces the slot, whichever order the answers
   arrive in. The channel always gets the request's own result. The stale-token retry is
   the same request: it keeps the number."
  [{:keys [a-data a-sent path fire!]}]
  (let [n (number! a-sent path)
        c (a/chan 1)
        deliver! (fn [result]
                   (when (latest? a-sent path n)
                     (swap! a-data update-in path into-slot result))
                   (a/put! c result))]
    (fire! (fn [result]
             (cond
               (and (retry/token-stale-401? result) @retry/token-stale-handler)
               ;; Exactly ONE retry. A second `token-stale` is surfaced to the caller rather
               ;; than looped on — the reload either produced a usable token or the session
               ;; is genuinely over, and the handler routes that to re-authentication.
               (a/go (a/<! (retry/reload-token!))
                     (fire! (fn [retried]
                              ;; The retry's own 401 is the session ending, not a second stale.
                              (when (and (retry/session-over-401? retried) @retry/unauthorized-handler)
                                (@retry/unauthorized-handler retried))
                              (deliver! retried))))

               (and (retry/session-over-401? result) @retry/unauthorized-handler)
               ;; The caller still gets the result — its own error branch renders as it
               ;; would have — and the handler runs beside it, once, from here, so no
               ;; caller has to remember to route a 401 to login.
               (do (@retry/unauthorized-handler result)
                   (deliver! result))

               :else (deliver! result))))
    c))

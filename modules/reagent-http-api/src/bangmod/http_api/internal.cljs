(ns bangmod.http-api.internal
  (:require [ajax.core :as ajax]
            [bangmod.http-api.retry :as retry]
            [bangmod.http-api.sse :as sse]
            [reagent.core :as r]
            [clojure.core.async :as a]
            [reagent.ratom :as ratom]
            [clojure.string :as str]))

(def api-specs (atom {}))
(def a-data (r/atom {}))
(def a-reactions (r/atom {}))

;; Optional provider fn returning the current bearer token (or nil). When set,
;; every request without an explicit :authorization header gets one injected.
(defonce auth-token-provider (atom nil))

(defn set-auth-token-provider!
  "Registers a 0-arg fn that returns the current access token (or nil)."
  [f]
  (reset! auth-token-provider f))

(defn- replace-path-params
  "Replace :param placeholders in URI with actual values from path-params map.
   e.g. \"/api/leaves/:id\" with {:id 123} => \"/api/leaves/123\""
  [uri path-params]
  (reduce-kv (fn [u k v]
               (str/replace u (str ":" (name k)) (str v)))
             uri
             (or path-params {})))

(defn- build-request-map
  "Build an ajax-compatible request map from an endpoint spec and runtime options.
   
   endpoint-spec keys:
     :method           - HTTP method (:get, :post, :put, :patch, :delete)
     :uri              - URI path, may contain :param placeholders
     :request-format   - :json, :url, :raw, :transit (default: none for GET)
     :response-format  - :json, :text, :raw, :transit (default: :json)
     :timeout          - request timeout in ms (default: 10000)
   
   runtime opts keys:
     :path-params  - map of path parameter replacements
     :params       - query params (GET) or body params (POST/PUT/PATCH)
     :headers      - additional headers (e.g. {:authorization \"Bearer ...\"})
     :on-success   - success callback (set internally)
     :on-failure   - failure callback (set internally)"
  [api-options endpoint-spec opts]
  (let [{:keys [base-url]} api-options
        {:keys [method uri request-format response-format timeout with-credentials]} endpoint-spec
        {:keys [path-params params headers]} opts
        token (when-let [provider @auth-token-provider] (provider))
        final-headers (cond-> (or headers {})
                        (and token (not (contains? (or headers {}) :authorization)))
                        (assoc :authorization (str "Bearer " token)))
        full-uri (str (or base-url "")
                      (replace-path-params uri path-params))
        req-format (case request-format
                     :json (ajax/json-request-format)
                     :url (ajax/url-request-format)
                     :transit (ajax/transit-request-format)
                     :raw {:content-type "text/plain"
                           :write (fn [data] data)}
                     nil)
        resp-format (case (or response-format :json)
                      :json (ajax/json-response-format {:keywords? true})
                      :text (ajax/text-response-format)
                      :transit (ajax/transit-response-format)
                      :raw (ajax/raw-response-format)
                      (ajax/json-response-format {:keywords? true}))]
    (cond-> {:uri             full-uri
             :method          (or method :get)
             :timeout         (or timeout 10000)
             :response-format resp-format}
      req-format          (assoc :format req-format)
      params              (assoc :params params)
      (seq final-headers) (assoc :headers final-headers)
      with-credentials    (assoc :with-credentials true))))

(defn make-reaction
  [api-name]
  (doseq [endpoint-name (keys (get @api-specs api-name))]
    (when (not= endpoint-name :_options)
      (swap! a-reactions
             (fn [old-state]
               (assoc-in old-state [api-name endpoint-name]
                         (ratom/make-reaction
                           (fn [] (get-in @a-data [api-name endpoint-name])))))))))

(defn defapi
  [api-name options endpoints-spec]
  (swap! api-specs (fn [old-state]
                     (-> old-state
                         (assoc-in [api-name :_options] options)
                         (#(reduce-kv (fn [s k v]
                                        (assoc-in s [api-name k] v))
                                      %
                                      endpoints-spec)))))
  (make-reaction api-name))

(defn execute
  [api-name endpoint-name opts]
  (let [api-options (get-in @api-specs [api-name :_options])
        endpoint-spec (get-in @api-specs [api-name endpoint-name])
        _ (when (nil? endpoint-spec)
            (throw (ex-info (str "Endpoint not found: " (name endpoint-name)
                                 " in API: " (name api-name))
                            {:api-name api-name :endpoint-name endpoint-name})))
        ;; `:sse` is not an HTTP verb. Left to fall through, it would reach
        ;; `ajax/ajax-request` as one and fail somewhere far less legible.
        _ (when (= :sse (:method endpoint-spec))
            (throw (ex-info (str (name api-name) "/" (name endpoint-name)
                                 " is an SSE endpoint — use subscribe, not execute")
                            {:api-name api-name :endpoint-name endpoint-name :method :sse})))
        c (a/chan 1)
        deliver! (fn [result]
                   (swap! a-data (fn [old-data]
                                   (assoc-in old-data [api-name endpoint-name] result)))
                   (a/put! c result))
        fire! (fn [on-result]
                ;; Rebuilt per attempt on purpose: `build-request-map` reads the bearer token
                ;; from `auth-token-provider` as it builds, so the retry below picks up the
                ;; reloaded one. Reusing the first request map would retry with the token the
                ;; server just refused.
                (ajax/ajax-request
                  (assoc (build-request-map api-options endpoint-spec opts)
                         :handler (fn [[success? response-data]]
                                    (on-result {:success? success? :data response-data})))))]
    (fire! (fn [result]
             (if (and (retry/token-stale-401? result) @retry/token-stale-handler)
               ;; Exactly ONE retry. A second `token-stale` is surfaced to the caller rather
               ;; than looped on — the reload either produced a usable token or the session
               ;; is genuinely over, and the handler routes that to re-authentication.
               (a/go (a/<! (retry/reload-token!))
                     (fire! deliver!))
               (deliver! result))))
    c))

;; --- subscriptions (`:method :sse`) -----------------------------------------
;;
;; A separate lifecycle from `execute`, which is single-shot end to end: one request, one
;; :handler callback, one `assoc-in`, one value on a `chan 1`. A subscription is one
;; connection and many messages, so it gets its own pair rather than a fourth value in
;; `execute`'s :method slot.

(defn- put-sse! [api-name endpoint-name f]
  (swap! a-data update-in [api-name endpoint-name] f))

(defn- parse-message [raw]
  (try
    (js->clj (js/JSON.parse raw) :keywordize-keys true)
    (catch :default _ raw)))

(defn unsubscribe!
  "Closes the EventSource and cancels any pending reconnect."
  [handle]
  (when handle
    (reset! (:closed? handle) true)
    (when-let [t @(:timer handle)] (js/clearTimeout t))
    (reset! (:timer handle) nil)
    (when-let [es @(:source handle)] (.close es))
    (reset! (:source handle) nil))
  nil)

(defn subscribe
  "Opens an SSE subscription against an endpoint declared `:method :sse`.

   Reconnect is owned here, on a readyState split: `CONNECTING` means EventSource is already
   retrying on the server's `retry:` field, so we only report the drop; `CLOSED` means it
   gave up — which per the HTML spec is what a non-200 status or a wrong Content-Type
   produces, i.e. our 401 and our 503 — so we re-open on a backoff, re-reading the token
   from `auth-token-provider` at open time, every time."
  [api-name endpoint-name {:keys [path-params params on-open on-message on-error] :as _opts}]
  (let [api-options (get-in @api-specs [api-name :_options])
        endpoint-spec (get-in @api-specs [api-name endpoint-name])
        _ (when (nil? endpoint-spec)
            (throw (ex-info (str "Endpoint not found: " (name endpoint-name)
                                 " in API: " (name api-name))
                            {:api-name api-name :endpoint-name endpoint-name})))
        _ (when (not= :sse (:method endpoint-spec))
            (throw (ex-info (str (name api-name) "/" (name endpoint-name)
                                 " is not an SSE endpoint — use execute, not subscribe")
                            {:api-name api-name :endpoint-name endpoint-name})))
        handle {:source (atom nil) :timer (atom nil) :attempt (atom 0) :closed? (atom false)}]
    (letfn [(open! []
              (when-not @(:closed? handle)
                (let [token (when-let [provider @auth-token-provider] (provider))
                      url (sse/stream-url (:base-url api-options) (:uri endpoint-spec)
                                          path-params params token)
                      es (js/EventSource. url)]
                  (reset! (:source handle) es)
                  (set! (.-onopen es)
                        (fn [_]
                          (reset! (:attempt handle) 0)
                          (put-sse! api-name endpoint-name sse/mark-open)
                          (when on-open (on-open))))
                  (set! (.-onmessage es)
                        (fn [e]
                          (let [data (parse-message (.-data e))]
                            (put-sse! api-name endpoint-name #(sse/apply-message % data))
                            (when on-message (on-message data)))))
                  (.addEventListener es "changed"
                                     (fn [e]
                                       (let [data (parse-message (.-data e))]
                                         (put-sse! api-name endpoint-name #(sse/apply-message % data))
                                         (when on-message (on-message data)))))
                  ;; A terminal `reconnect` frame is the server closing on purpose — its
                  ;; deadline or the dropped latch. Re-opening IS the resume: the server's
                  ;; first frame on the new connection is the current state, so whatever was
                  ;; missed is replaced rather than replayed.
                  ;; `token-stale` is the one reason a plain re-open cannot recover from: the
                  ;; new connection re-reads the SAME token from `auth-token-provider`, gets
                  ;; the same 401 at `wrap-stream-auth`, closes, backs off and tries again —
                  ;; an infinite hot loop against a credential that will never become valid.
                  ;; Reload first; every other reason keeps the immediate re-open.
                  (.addEventListener es "reconnect"
                                     (fn [e]
                                       (let [reason (:reason (parse-message (.-data e)))]
                                         (if (retry/reload-before-reopen? reason)
                                           (a/go (a/<! (retry/reload-token!)) (reopen! reason))
                                           (reopen! (or reason "reconnect"))))))
                  (set! (.-onerror es)
                        (fn [_]
                          (put-sse! api-name endpoint-name #(sse/mark-error % "connection lost"))
                          (when on-error (on-error "connection lost"))
                          (when (= 2 (.-readyState es)) (reopen! "closed")))))))
            (reopen! [_reason]
              ;; A pending timer means a re-open is already scheduled. Without this guard a
              ;; terminal frame followed by an error — or two errors — would overwrite the
              ;; timer handle and leave the first one to open a second EventSource.
              (when (and (not @(:closed? handle)) (nil? @(:timer handle)))
                (when-let [es @(:source handle)] (.close es))
                (reset! (:source handle) nil)
                (let [delay (sse/backoff-ms @(:attempt handle))]
                  (swap! (:attempt handle) inc)
                  (reset! (:timer handle)
                          (js/setTimeout (fn [] (reset! (:timer handle) nil) (open!)) delay)))))]
      (open!)
      handle)))

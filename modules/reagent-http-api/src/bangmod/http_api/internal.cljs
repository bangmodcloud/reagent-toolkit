(ns bangmod.http-api.internal
  (:require [ajax.core :as ajax]
            [bangmod.http-api.retry :as retry]
            [bangmod.http-api.auth :as auth]
            [bangmod.http-api.sse :as sse]
            [reagent.core :as r]
            [clojure.core.async :as a]
            [reagent.ratom :as ratom]))

;; defonce: a hot reload must not wipe registered APIs or the data components are
;; already subscribed to.
(defonce api-specs (atom {}))
(defonce a-data (r/atom {}))
(defonce a-reactions (r/atom {}))

;; Optional provider fn returning the current token (or nil). When it returns one, the
;; injector below puts it on every request.
(defonce auth-token-provider (atom nil))

;; `(fn [request token] -> request)` — how the token gets onto a request map. Default:
;; `Authorization: Bearer`, explicit header wins. Only ever called with a non-nil token.
(defonce auth-token-injector (atom auth/bearer-injector))

(defn set-auth-token-provider!
  "Registers a 0-arg fn that returns the current access token (or nil)."
  [f]
  (reset! auth-token-provider f))

(defn set-auth-token-injector!
  "Registers `(fn [request token] -> request)`; nil restores the Bearer default."
  [f]
  (reset! auth-token-injector (or f auth/bearer-injector)))

(defn- inject-token
  "`request` with the current token put on it by the registered injector — or unchanged
   when the provider has none. The one place both `execute` and a stream authenticate, and
   the provider is read on every call, so every attempt carries the token current then."
  [request]
  (if-let [token (when-let [provider @auth-token-provider] (provider))]
    (@auth-token-injector request token)
    request))

(defn- build-request-map
  "Build an ajax-compatible request map from an endpoint spec and runtime options.

   endpoint-spec keys:
     :method           - HTTP method (:get, :post, :put, :patch, :delete)
     :uri              - URI path, may contain :param placeholders
     :request-format   - :json, :url, :raw, :transit (default: none for GET)
     :response-format  - :json, :text, :raw, :transit (default: :json)
     :timeout          - request timeout in ms (default: 10000)
     :with-credentials - true to send cookies on cross-origin requests

   runtime opts keys:
     :path-params  - map of path parameter replacements (values percent-encoded)
     :params       - query params (GET) or body params (POST/PUT/PATCH)
     :body         - a prebuilt request body sent as-is (a js/FormData for a multipart
                     upload). Wins over :params and the endpoint's :request-format: no
                     format is applied and no Content-Type is set, so the browser writes
                     the multipart boundary itself.
     :headers      - additional headers (e.g. {:authorization \"Bearer ...\"})"
  [api-options endpoint-spec opts]
  (let [{:keys [base-url]} api-options
        {:keys [method uri request-format response-format timeout with-credentials]} endpoint-spec
        {:keys [path-params params headers body]} opts
        full-uri (str (or base-url "")
                      (sse/replace-path-params uri path-params))
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
    (-> (cond-> {:uri             full-uri
                 :method          (or method :get)
                 :timeout         (or timeout 10000)
                 :response-format resp-format}
          (and req-format (nil? body)) (assoc :format req-format)
          (and params (nil? body))     (assoc :params params)
          body                         (assoc :body body)
          (seq headers)    (assoc :headers headers)
          with-credentials (assoc :with-credentials true))
        ;; Last, over the finished map, so an injector can put the token anywhere — a
        ;; header, a query param, a cookie flag — not just where the default does.
        inject-token)))

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
  ;; :_options is where the API-level options live in the same map — an endpoint by that
  ;; name would silently overwrite them.
  (when (contains? endpoints-spec :_options)
    (throw (ex-info ":_options is a reserved name and cannot be used as an endpoint"
                    {:api-name api-name})))
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
                   ;; The stored slot keeps the last GOOD :data across failures — a UI bound
                   ;; to the reaction must not go blank because one refresh failed. The
                   ;; failure itself lands under :error; the caller's channel still gets the
                   ;; raw result untouched.
                   (swap! a-data update-in [api-name endpoint-name]
                          (fn [prev]
                            (if (:success? result)
                              result
                              (assoc (or prev {}) :success? false :error (:data result)))))
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

;; --- subscriptions (`:method :sse`) -----------------------------------------
;;
;; A separate lifecycle from `execute`, which is single-shot end to end: one request, one
;; :handler callback, one `assoc-in`, one value on a `chan 1`. A subscription is one
;; connection and many messages, so it gets its own pair rather than a fourth value in
;; `execute`'s :method slot.
;;
;; The transport is `fetch` reading the body as a stream, so a stream authenticates
;; exactly like a request (`inject-token`, request headers, never the URL) and sees the
;; response status, which tells a 401 apart from a network drop.

(defn- put-sse! [api-name endpoint-name f]
  (swap! a-data update-in [api-name endpoint-name] f))

(defn- parse-message [raw]
  (try
    (js->clj (js/JSON.parse raw) :keywordize-keys true)
    (catch :default _ raw)))

(defn- report!
  "Calls a consumer callback so that a throw inside it cannot break the stream. Inside the
   read loop's promise chain an exception would reject it and read as a dropped connection;
   rethrown on a task of its own it reaches the console and the stream carries on."
  [f & args]
  (when f
    (try (apply f args)
         (catch :default e (js/setTimeout #(throw e) 0)))))

(defn- stream-request
  "The request a stream opens with, rebuilt on every (re)open so it carries the token
   current then. It is shaped like an `execute` request map and goes through the same
   injector, so the token lands wherever it would on a request — by default the
   `Authorization` header; the fetch reads :uri, :params, :headers and :with-credentials
   back off the result."
  [api-options endpoint-spec path-params params last-event-id]
  (inject-token
    (cond-> {:uri     (str (or (:base-url api-options) "")
                           (sse/replace-path-params (:uri endpoint-spec) path-params))
             :method  :get
             :headers {:accept "text/event-stream"}}
      (seq params)                      (assoc :params params)
      ;; Spec: sent on a reconnect once the stream has named an event id — and not when
      ;; the last `id:` was empty, which is the server resetting it.
      (seq last-event-id)               (assoc-in [:headers :last-event-id] last-event-id)
      (:with-credentials endpoint-spec) (assoc :with-credentials true))))

(defn- fetch-init [request signal]
  (let [headers (js/Headers.)]
    (doseq [[k v] (:headers request)]
      (.set headers (name k) (str v)))
    #js {:method      "GET"
         :headers     headers
         :cache       "no-store"
         :credentials (if (:with-credentials request) "include" "same-origin")
         :signal      signal}))

;; The handle `subscribe` returns. A record so the lifecycle atoms stay keyword-addressable,
;; derefable so a component holds ONE value that both renders the stream's latest frame and
;; closes the stream on unmount — the same shape `execute` hands back for a plain request.
;; `source` is the current connection's AbortController; `last-event-id` and `retry-ms`
;; outlive a connection, as the event-stream spec has them do.
(defrecord Subscription [source timer attempt closed? last-event-id retry-ms reaction]
  IDeref
  (-deref [_] @reaction))

(defn unsubscribe!
  "Aborts the stream's connection and cancels any pending reconnect. Anything that is not a
   `Subscription` — the reaction `execute` returns for a plain request, nil — is a no-op, so
   a component can `unsubscribe!` whatever `execute` gave it without knowing the method."
  [handle]
  (when (instance? Subscription handle)
    (reset! (:closed? handle) true)
    (when-let [t @(:timer handle)] (js/clearTimeout t))
    (reset! (:timer handle) nil)
    (when-let [ctrl @(:source handle)]
      (reset! (:source handle) nil)
      (.abort ctrl)))
  nil)

(defn subscribe
  "Opens an SSE subscription against an endpoint declared `:method :sse`.

   The stream is a `fetch` whose body is read as it arrives and parsed by `sse/feed`. Each
   (re)open rebuilds the request, so the token is re-read from `auth-token-provider` and put
   on by the registered injector every time. What happens next is decided by
   `sse/response-action`: 200 + text/event-stream opens; a 401 `token-stale` reloads the
   token and re-opens; any other 401 ends the session (the unauthorized handler, no
   re-open); anything else — another status, a network error, the stream ending — re-opens
   on the backoff, never faster than the server's `retry:`."
  [api-name endpoint-name {:keys [path-params params on-open on-message on-error events] :as _opts}]
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
        ;; Unnamed frames arrive as "message"; a frame the server sends with an `event:`
        ;; name is delivered only if that name is listed. Which names a server uses is its
        ;; own contract — hence the :events option (default ["changed"]).
        frame-types (set (cons "message" (or events ["changed"])))
        handle (->Subscription (atom nil) (atom nil) (atom 0) (atom false) (atom nil) (atom nil)
                               (get-in @a-reactions [api-name endpoint-name]))]
    (letfn [(put! [f] (put-sse! api-name endpoint-name f))
            (fail! [message]
              (put! #(sse/mark-error % message))
              (report! on-error message))
            (drop! []
              ;; Cleared before the abort, so the aborted connection's callbacks — all of
              ;; which check `live?` — see it is no longer current.
              (when-let [ctrl @(:source handle)]
                (reset! (:source handle) nil)
                (.abort ctrl)))
            (reload-then-reopen! []
              (drop!)
              (a/go (a/<! (retry/reload-token!)) (reopen!)))
            (reopen! []
              ;; A pending timer means a re-open is already scheduled. Without this guard a
              ;; terminal frame followed by an error — or two errors — would overwrite the
              ;; timer handle and leave the first one to open a second connection.
              (when (and (not @(:closed? handle)) (nil? @(:timer handle)))
                (drop!)
                (let [delay (sse/reconnect-delay @(:attempt handle) @(:retry-ms handle))]
                  (swap! (:attempt handle) inc)
                  (reset! (:timer handle)
                          (js/setTimeout (fn [] (reset! (:timer handle) nil) (open!)) delay)))))
            (open! []
              (when-not @(:closed? handle)
                (let [ctrl (js/AbortController.)
                      ;; Every callback of this connection checks this first: once the
                      ;; connection is aborted — `unsubscribe!`, or a re-open replacing it —
                      ;; nothing it does afterwards counts, in particular its AbortError is
                      ;; not a connection error.
                      live? #(and (identical? ctrl @(:source handle))
                                  (not (.. ctrl -signal -aborted)))
                      lost! (fn [_]
                              (when (live?)
                                (fail! "connection lost")
                                (reopen!)))
                      request (stream-request api-options endpoint-spec path-params params
                                              @(:last-event-id handle))
                      url (sse/stream-url nil (:uri request) nil (:params request))]
                  (letfn [(dispatch! [evs]
                            (doseq [{:keys [type data]} evs
                                    :while (live?)]
                              (cond
                                ;; A `reconnect` control frame is the server closing on
                                ;; purpose — its own connection deadline or shedding policy.
                                ;; Re-opening IS the resume: the server's first frame on the
                                ;; new connection is the current state, so whatever was
                                ;; missed is replaced rather than replayed.
                                ;; `token-stale` is the one reason a plain re-open cannot
                                ;; recover from: it would re-read the SAME token and be
                                ;; refused again. Reload first; every other reason keeps the
                                ;; plain re-open.
                                (= "reconnect" type)
                                (if (retry/reload-before-reopen? (:reason (parse-message data)))
                                  (reload-then-reopen!)
                                  (reopen!))

                                (contains? frame-types type)
                                (let [data (parse-message data)]
                                  (put! #(sse/apply-message % data))
                                  (report! on-message data)))))
                          (pump [reader decoder parser]
                            ;; One promise chain per read, not one chain for the whole
                            ;; stream: returning the next read from this one would nest a
                            ;; pending promise per chunk for as long as the stream lives.
                            (-> (.read reader)
                                (.then (fn [chunk]
                                         (when (live?)
                                           (if (.-done chunk)
                                             ;; The server ended the stream without a
                                             ;; `reconnect` frame — treated as a drop.
                                             (lost! nil)
                                             (let [[parser evs] (sse/feed parser
                                                                          (.decode decoder (.-value chunk)
                                                                                   #js {:stream true}))]
                                               (reset! (:last-event-id handle) (:last-id parser))
                                               (when-let [r (:retry parser)]
                                                 (reset! (:retry-ms handle) r))
                                               (dispatch! evs)
                                               (when (live?)
                                                 (pump reader decoder parser)))))
                                         nil))
                                (.catch lost!)))
                          (on-response [resp body]
                            (when (live?)
                              (let [status (.-status resp)
                                    reason (when (map? body) (:reason body))]
                                (case (sse/response-action status (.. resp -headers (get "content-type")) reason)
                                  :open
                                  (do (reset! (:attempt handle) 0)
                                      (put! sse/mark-open)
                                      (report! on-open)
                                      (when (live?)
                                        (pump (.getReader (.-body resp)) (js/TextDecoder.)
                                              (sse/parser @(:last-event-id handle)))))

                                  ;; The same recovery as `execute`'s stale-token retry.
                                  :reload-token
                                  (reload-then-reopen!)

                                  ;; The session is over: re-opening would only be refused
                                  ;; again. Reported once, the way `execute` reports it —
                                  ;; the handler gets a result shaped like a failed request.
                                  :session-over
                                  (do (drop!)
                                      (fail! "unauthorized")
                                      (report! @retry/unauthorized-handler
                                               {:success? false
                                                :data     {:status 401 :response body}}))

                                  :backoff
                                  (do (fail! (str "HTTP " status))
                                      (reopen!))))))]
                    (reset! (:source handle) ctrl)
                    (-> (js/fetch url (fetch-init request (.-signal ctrl)))
                        (.then (fn [resp]
                                 (when (live?)
                                   ;; Only a 401's body is read before deciding — it says
                                   ;; whether the token is merely stale.
                                   (if (= 401 (.-status resp))
                                     (-> (.text resp)
                                         (.then (fn [text] (on-response resp (parse-message text)))))
                                     (on-response resp nil)))))
                        (.catch lost!))))))]
      (open!)
      handle)))

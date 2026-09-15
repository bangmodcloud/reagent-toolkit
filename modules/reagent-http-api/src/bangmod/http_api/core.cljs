(ns bangmod.http-api.core
  (:require [bangmod.http-api.internal :as internal]
            [bangmod.http-api.retry :as retry]
            [bangmod.http-api.re-frame :as re-frame-integration]))

(defn defapi
  "Define an HTTP REST API.
   
   api-name       - keyword identifying this API (e.g. :leave, :user)
   options        - map of API-level options:
                      :base-url - base URL prefix for all endpoints
   endpoints-spec - map of endpoint-name -> endpoint-spec:
                      :method          - :get, :post, :put, :patch, :delete, or :sse
                                         (:sse endpoints are opened with `subscribe` or
                                          `execute`; `raw-execute` refuses them;
                                          :request-format, :response-format and :timeout
                                          do not apply)
                      :uri             - URI path (supports :param placeholders)
                      :request-format  - :json, :url, :transit, :raw
                      :response-format - :json, :text, :transit, :raw
                      :timeout         - timeout in ms (default: 10000)
                      :with-credentials - true to send cookies on cross-origin requests
   
   Example:
     (defapi :leave
       {:base-url \"https://api.example.com\"}
       {:all-leaves   {:method :get
                       :uri    \"/api/leaves\"
                       :response-format :json}
        :create-leave {:method :post
                       :uri    \"/api/leaves\"
                       :request-format :json
                       :response-format :json}})"
  [api-name options endpoints-spec]
  (internal/defapi api-name options endpoints-spec))

(defn get-data-reaction
  "Get a reagent reaction for an endpoint's stored slot — the latest value, not the last
   call. Useful for reactive UI updates. Throws if the endpoint was never declared —
   dereferencing the nil would otherwise fail far away with no name attached."
  [api-name endpoint-name]
  (or (get-in @internal/a-reactions [api-name endpoint-name])
      (throw (ex-info (str "No reaction for " (name api-name) "/" (name endpoint-name)
                           " — was it declared with defapi?")
                      {:api-name api-name :endpoint-name endpoint-name}))))

(defn raw-execute
  "Execute an HTTP API call. Returns a core.async channel with the result.
   
   api-name      - keyword identifying the API (as defined in defapi)
   endpoint-name - keyword identifying the endpoint
   opts          - optional map:
                     :path-params - map for URI :param replacement
                     :params      - query/body params
                     :headers     - additional headers (e.g. {:authorization \"Bearer ...\"})
   
   Result channel receives a map: {:success? bool, :data response-data}

   The reaction / re-frame slot for the endpoint keeps its last successful :data across
   failures — a failed call sets :success? false and puts the failure under :error there,
   so a UI bound to `get-data-reaction` / `execute` does not go blank because one refresh
   failed.
   
   Examples:
     (a/<! (raw-execute :leave :all-leaves))
     (a/<! (raw-execute :leave :create-leave {:params {:start-date \"2026-01-01\"}}))
     (a/<! (raw-execute :leave :update-leave {:path-params {:id 123}
                                              :params {:status \"approved\"}
                                              :headers {:authorization \"Bearer token\"}}))"
  ([api-name endpoint-name]
   (internal/execute api-name endpoint-name {}))
  ([api-name endpoint-name opts]
   (internal/execute api-name endpoint-name opts)))

(defn execute
  "Fire an endpoint and return something to deref for its latest value — `raw-execute` plus
   `get-data-reaction` in one step for a request, `subscribe` for an `:sse` endpoint. Either
   way `(:data @x)` is the latest body/frame, for the common case of a component that
   loads on mount and renders whatever the endpoint currently holds:

     (defn my-info []
       (r/with-let [info (http-api/execute :api :myinfo)]   ; fires once, here
         [:div (:data @info)]
         (finally (http-api/unsubscribe! info))))           ; no-op unless :sse

   Request endpoint: returns the endpoint's reaction over its shared slot — nil until the
   first response, then {:success? true :data <body>}, and after a failure the same map with
   :success? false and :error <cljs-ajax error map> merged in (the last good :data survives).
   Any other call against the same endpoint updates it too. `opts` as for `raw-execute`.

   `:sse` endpoint: opens the stream and returns the subscription handle, which derefs to
   {:sse? true :connected? bool :data <latest frame> :message-count n :error msg-or-nil}.
   `opts` as for `subscribe` — pass `:on-open`/`:on-message` when you need callbacks too.
   The handle must reach `unsubscribe!` when the component unmounts.

   Use `raw-execute` when you need one specific request's result as a value (a go block, a
   callback, an error branch)."
  ([api-name endpoint-name]
   (execute api-name endpoint-name {}))
  ([api-name endpoint-name opts]
   (if (= :sse (get-in @internal/api-specs [api-name endpoint-name :method]))
     (internal/subscribe api-name endpoint-name opts)
     (do (raw-execute api-name endpoint-name opts)
         (get-data-reaction api-name endpoint-name)))))

(defn subscribe
  "Open a live subscription to an endpoint declared `:method :sse`. Returns a handle for
   `unsubscribe!`; deref it for the stream's latest state
   ({:sse? true :connected? bool :data <latest frame> :message-count n :error msg-or-nil}).

   opts:
     :path-params - map for URI :param replacement
     :params      - extra query params
     :on-open     - 0-arg fn, called on EVERY (re)connection including the first. This is
                    where a full re-fetch belongs: the server subscribes before writing its
                    first byte, so nothing can slip between the snapshot and the stream.
     :on-message  - 1-arg fn receiving the parsed `data` of one frame
     :on-error    - 1-arg fn receiving a message
     :events      - extra named SSE event types delivered to :on-message (default
                    [\"changed\"]). Unnamed frames always arrive; a frame the server sends
                    with an `event:` name only fires a listener registered for that name.

   Example:
     (subscribe :account :changes {:on-open #(load!) :on-message (fn [_] (load!))})"
  ([api-name endpoint-name] (internal/subscribe api-name endpoint-name {}))
  ([api-name endpoint-name opts] (internal/subscribe api-name endpoint-name opts)))

(defn unsubscribe!
  "Close a subscription opened with `subscribe` (or `execute` on an `:sse` endpoint) and
   cancel any pending reconnect. A no-op on anything else — a plain-request reaction, nil —
   so a component can pass whatever `execute` returned."
  [handle]
  (internal/unsubscribe! handle))

(defn set-auth-token-provider!
  "Register a 0-arg fn returning the current access token (or nil). Whenever it returns
   one, the token injector (default: `Authorization: Bearer <token>`, unless the call set
   its own :authorization header) puts it on the request. Read fresh for every attempt,
   so a retry after a token reload carries the new token. SSE streams get it as
   `?access_token=` — EventSource cannot set headers."
  [f]
  (internal/set-auth-token-provider! f))

(defn set-auth-token-injector!
  "Register how the token from `set-auth-token-provider!` goes onto a request:
   `(fn [request token] -> request)`, over the finished cljs-ajax request map
   (:uri :method :params :headers ...), called only when the provider returned a token.
   The default is `bangmod.http-api.auth/bearer-injector`; pass nil to restore it.

     ;; token as a custom header
     (set-auth-token-injector! (fn [req token] (assoc-in req [:headers :x-api-key] token)))
     ;; token as a query param
     (set-auth-token-injector! (fn [req token] (assoc-in req [:params :access_token] token)))

   HTTP requests only — an SSE stream's URL always carries `?access_token=`."
  [f]
  (internal/set-auth-token-injector! f))

(defn set-token-stale-handler!
  "Register a 0-arg fn returning a channel, called when the server refuses a request's token
   as `token-stale` (a 401 carrying `reason: \"token-stale\"`). The request is retried once
   after it completes, rebuilt so the reloaded token is used. Concurrent stale requests park
   on ONE reload. Without a handler registered, such a 401 is returned unchanged."
  [f]
  (retry/set-token-stale-handler! f))

(defn init
  "Initialize HTTP API module. Sets up re-frame integration to sync
   response data into re-frame db at [:_http-api :data]."
  []
  (re-frame-integration/integrate {:data-atom internal/a-data}))

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
                                         (:sse endpoints are opened with `subscribe`, and
                                          `execute` refuses them; :request-format,
                                          :response-format and :timeout do not apply)
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

(defn execute
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
   so a UI bound to `get-data-reaction` does not go blank because one refresh failed.
   
   Examples:
     (execute :leave :all-leaves)
     (execute :leave :create-leave {:params {:start-date \"2026-01-01\"}})
     (execute :leave :update-leave {:path-params {:id 123}
                                    :params {:status \"approved\"}
                                    :headers {:authorization \"Bearer token\"}})"
  ([api-name endpoint-name]
   (internal/execute api-name endpoint-name {}))
  ([api-name endpoint-name opts]
   (internal/execute api-name endpoint-name opts)))

(defn subscribe
  "Open a live subscription to an endpoint declared `:method :sse`. Returns a handle for
   `unsubscribe!`.

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
  "Close a subscription opened with `subscribe` and cancel any pending reconnect."
  [handle]
  (internal/unsubscribe! handle))

(defn set-auth-token-provider!
  "Register a 0-arg fn returning the current bearer token (or nil). Every
   request without an explicit :authorization header will get one injected."
  [f]
  (internal/set-auth-token-provider! f))

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

(defn get-data-reaction
  "Get a reagent reaction for an endpoint's response data.
   Useful for reactive UI updates. Throws if the endpoint was never declared —
   dereferencing the nil would otherwise fail far away with no name attached."
  [api-name endpoint-name]
  (or (get-in @internal/a-reactions [api-name endpoint-name])
      (throw (ex-info (str "No reaction for " (name api-name) "/" (name endpoint-name)
                           " — was it declared with defapi?")
                      {:api-name api-name :endpoint-name endpoint-name}))))

# reagent-http-api

`io.github.bangmodcloud/reagent-http-api` — namespace `bangmod.http-api.*`

A declarative wrapper around [cljs-ajax](https://github.com/JulianBirch/cljs-ajax) for REST
calls, plus a live-update path (`:method :sse`) and optional re-frame integration. Describe
each API once as a map of endpoints; `execute`/`subscribe` handle the request/stream, bearer
token injection, and (for SSE) reconnects.

## Install

See the [root README](../README.md#installation) for `deps.edn` / git-dependency snippets.

## Quick start

```clojure
(ns myapp.api.account
  (:require [bangmod.http-api.core :refer [defapi]]))

(defapi :account
  {:base-url "https://api.example.com"}
  {:get {:method :get :uri "/api/query/account-projection/me" :response-format :json}})
```

```clojure
(ns myapp.feature.account.event
  (:require [cljs.core.async :as a]
            [re-frame.core :as rf]
            [bangmod.http-api.core :as http-api]))

(defn load-account! []
  (a/go
    (let [res (a/<! (http-api/execute :account :get))]
      (if (:success? res)
        (rf/dispatch [:account/set (:data res)])
        (rf/dispatch [:account/set-error (:data res)])))))
```

`execute` returns a `core.async` channel delivering exactly one map:
`{:success? true :data <parsed response body>}` on success, or
`{:success? false :data <cljs-ajax error map>}` on failure (that shape — `:status`,
`:response`, ... — is [cljs-ajax's](https://github.com/JulianBirch/cljs-ajax), not this
library's).

Attach a bearer token to every request automatically, once at boot:

```clojure
(http-api/set-auth-token-provider! (fn [] @auth/access-token))
```

## GET and SSE on the same URI

`:sse` can point at the same `:uri` as a `:get` — that's the intended way to add a live-update
path to an existing endpoint without a parallel one:

```clojure
{:get     {:method :get :uri "/api/query/account-projection/me" :response-format :json}
 :changes {:method :sse :uri "/api/query/account-projection/me"}}
```

`:get` returns the current value once, on demand; `:changes` opens a stream at the identical
URI and delivers that same shape again on every change. Two entries exist because `defapi`
keys both the request cycle and the reaction by *endpoint name*, not by URI — one URI, two
access patterns, not two endpoints to keep in sync. The server side only needs the same route
to also serve `Accept: text/event-stream`; nothing about `:get` has to change.

## API reference

`bangmod.http-api.core`:

| Function | Description |
| --- | --- |
| `(defapi api-name options endpoints-spec)` | Declares one named REST/SSE API. `options` is `{:base-url "..."}`. `endpoints-spec` is `endpoint-name -> spec` — see below. |
| `(execute api-name endpoint-name opts?)` | Fires one request, returns a channel with `{:success? bool :data ...}`. `opts`: `:path-params` (fills `:param` in the URI), `:params` (query/body), `:headers` (overrides the auto-injected token for that call). The reaction/re-frame slot keeps the last successful `:data` across failures — a failed call sets `:success? false` and puts the failure under `:error` there. |
| `(subscribe api-name endpoint-name opts)` | Opens a live subscription against an `:sse` endpoint, returns an opaque handle. `opts`: `:path-params`, `:params`, `:on-open` (0-arg, every reconnect including the first — see Gotchas), `:on-message` (1-arg, parsed frame data), `:on-error` (1-arg, message string), `:events` (extra named SSE event types delivered to `:on-message`, default `["changed"]` — unnamed frames always arrive). |
| `(unsubscribe! handle)` | Closes the connection, cancels any pending reconnect. Safe on an already-closed handle. |
| `(set-auth-token-provider! f)` | Registers a 0-arg fn returning the bearer token (or `nil`), injected into every request lacking an explicit `:authorization` header, rebuilt fresh on every retry. |
| `(set-token-stale-handler! f)` | Registers a 0-arg fn returning a channel, called when a 401 carries `{:reason "token-stale"}`. Retried exactly once after the handler's channel closes; concurrent stale requests share one reload. No handler registered ⇒ the 401 passes through unchanged. |
| `(init)` | Wires re-frame integration: every response/SSE update mirrors into `[:_http-api :data]` in the app-db. Optional — `execute`/`subscribe`/`get-data-reaction` work without it. |
| `(get-data-reaction api-name endpoint-name)` | Reagent reaction over an endpoint's latest value: `@(http-api/get-data-reaction :account :get)`. Throws if the endpoint was never declared. |

`endpoints-spec` per-endpoint keys:

- `:method` — `:get`, `:post`, `:put`, `:patch`, `:delete`, or `:sse` (opened with
  `subscribe`, never `execute`; `:request-format`/`:response-format`/`:timeout` don't apply).
- `:uri` — path, may contain `:param` placeholders (`"/api/leaves/:id"`); substituted
  values are percent-encoded.
- `:with-credentials` — `true` to send cookies on cross-origin requests.
- `:request-format` — `:json`, `:url`, `:transit`, `:raw`.
- `:response-format` — `:json`, `:text`, `:transit`, `:raw`.
- `:timeout` — ms, default `10000`.

## Real-world example

Declaring an API mixing GET, POST and SSE:

```clojure
(ns myapp.api.account
  "Owner-facing account calls. Reads hit `/api/query/{projection}`, writes hit
   `/api/commands/{aggregate}/{command}` with the target id in the body."
  (:require [bangmod.http-api.core :refer [defapi]]
            [myapp.config :as config]))

(defn init []
  (defapi :account
    {:base-url config/API_BASE_URL}
    {:get        {:method :get  :uri "/api/query/account-projection/me"
                  :response-format :json}
     :ledger     {:method :get  :uri "/api/query/account-projection/me/ledger"
                  :response-format :json}
     :changes    {:method :sse  :uri "/api/query/account-projection/me"} ; same URI as :get
     :auto-renew {:method :post :uri "/api/commands/account/auto-renew"
                  :request-format :json :response-format :json}}))
```

`execute`, in a go block, success/failure handled explicitly:

```clojure
(ns myapp.feature.account.event
  (:require [cljs.core.async :as a]
            [re-frame.core :as rf]
            [bangmod.http-api.core :as http-api]
            [myapp.api.response :as response]))

(defn load-account!
  ([] (load-account! nil))
  ([on-done]
   (a/go
     (let [res (a/<! (http-api/execute :account :get))]
       (if (:success? res)
         (let [account (response/payload res)]
           (rf/dispatch [:account/set account])
           (when on-done (on-done account)))
         (do (rf/dispatch [:account/set-error (response/error-message res)])
             (when on-done (on-done nil))))))))
```

`response/payload`/`response/error-message` are small app-side helpers around `:data` (not
part of this library) — `payload` is `(:data res)` on success, `error-message` picks a
message out of the cljs-ajax error map on failure.

`subscribe`/`unsubscribe!` in a component's lifecycle — the pattern that matters most for SSE:

```clojure
(ns myapp.feature.account.view
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [bangmod.http-api.core :as http-api]
            [myapp.feature.account.event :refer [load-account! load-ledger!]]))

(defn account-page []
  (let [changes-sub (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (load-account!)
        (load-plans!)                    ; a sibling load, same pattern as load-account!
        (reset! changes-sub
                (http-api/subscribe :account :changes
                                     {:on-open (fn [] (load-ledger!))
                                      :on-message (fn [acct]
                                                    (rf/dispatch [:account/set acct])
                                                    (load-ledger!))})))
      :component-will-unmount
      (fn []
        (http-api/unsubscribe! @changes-sub)
        (reset! changes-sub nil))
      :reagent-render
      (fn []
        (let [acct @(rf/subscribe [:account/data])]
          [:div "..."]))})))
```

## Gotchas

- **`:on-open` fires on every reconnect, not just the first — treat it as "do a full
  re-fetch now."** The server subscribes before writing its first byte, so there's no gap
  between the `execute` snapshot and the stream. Loading dependent data (`load-ledger!`
  above) from `:on-open` rather than once on mount is what keeps it correct across a
  reconnect.
- **Always pair `subscribe` with `unsubscribe!` in `component-will-unmount`** — a subscription
  that outlives its component leaks a connection and a pending reconnect timer.
- **A stale token retries at most once**, and concurrent stale requests share that one
  reload. With no `set-token-stale-handler!` registered, the 401 just reaches your callback
  like any other failure.
- **`execute` on an `:sse` endpoint (and `subscribe` on anything else) throws immediately**,
  naming the mismatch, rather than failing somewhere inside the transport.
- **`:headers` on a call overrides the auto-injected `:authorization`**, not merges under it.

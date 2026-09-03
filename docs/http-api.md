# reagent-http-api

`io.github.bangmodcloud/reagent-http-api` — namespace `bangmod.http-api.*`

A declarative wrapper around [cljs-ajax](https://github.com/JulianBirch/cljs-ajax) for
REST calls, plus a live-update path (`:method :sse`) and optional re-frame integration. You
describe each API once as a map of endpoints; `execute` and `subscribe` do the request/stream
handling, bearer-token injection, and (for SSE) reconnects.

## Install

Add the artifact — see the [root README](../README.md#installation) for the full
`deps.edn` / git-dependency snippets.

## Quick start

```clojure
(ns myapp.api.account
  (:require [bangmod.http-api.core :refer [defapi]]))

(defapi :account
  {:base-url "https://api.example.com"}
  {:get {:method :get
         :uri "/api/query/account-projection/me"
         :response-format :json}})
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

`execute` returns a `core.async` channel that delivers exactly one map:
`{:success? true :data <parsed response body>}` on success, or
`{:success? false :data <cljs-ajax error map>}` on failure (that map's shape — `:status`,
`:response`, ... — is [cljs-ajax's](https://github.com/JulianBirch/cljs-ajax), not this
library's).

To attach a bearer token to every request automatically, register a provider once at boot:

```clojure
(http-api/set-auth-token-provider! (fn [] @auth/access-token))
```

## API reference

From `bangmod.http-api.core`:

- **`(defapi api-name options endpoints-spec)`** — declares one named REST API.
  - `api-name` — keyword identifying this API (e.g. `:account`).
  - `options` — `{:base-url "..."}`, prefixed onto every endpoint's `:uri`.
  - `endpoints-spec` — map of `endpoint-name -> endpoint-spec`:
    - `:method` — `:get`, `:post`, `:put`, `:patch`, `:delete`, or `:sse`. An `:sse` endpoint
      is opened with `subscribe`, never `execute` — `execute` refuses it; `:request-format`,
      `:response-format` and `:timeout` don't apply to it.
    - `:uri` — path, may contain `:param` placeholders (`"/api/leaves/:id"`).
    - `:request-format` — `:json`, `:url`, `:transit`, `:raw`.
    - `:response-format` — `:json`, `:text`, `:transit`, `:raw`.
    - `:timeout` — ms, default `10000`.

- **`(execute api-name endpoint-name opts?)`** — fires one request, returns a `core.async`
  channel with `{:success? bool :data ...}`. `opts`:
  - `:path-params` — map filling `:param` placeholders in the URI.
  - `:params` — query params (GET) or body params (POST/PUT/PATCH).
  - `:headers` — extra headers, e.g. `{:authorization "Bearer ..."}` (overrides the
    auto-injected token for that one call).

- **`(subscribe api-name endpoint-name opts)`** — opens a live subscription against an
  `:sse` endpoint. Returns an opaque handle for `unsubscribe!`. `opts`:
  - `:path-params`, `:params` — same as `execute`.
  - `:on-open` — 0-arg fn, called on **every** (re)connection, including the first. See
    Gotchas for why this is where a full re-fetch belongs.
  - `:on-message` — 1-arg fn receiving the parsed `data` of one frame.
  - `:on-error` — 1-arg fn receiving an error message string.

- **`(unsubscribe! handle)`** — closes the connection and cancels any pending reconnect.
  Safe to call on an already-closed handle.

- **`(set-auth-token-provider! f)`** — registers a 0-arg fn returning the current bearer
  token (or `nil`). Every request without an explicit `:authorization` header gets one
  injected from it, rebuilt fresh on every retry.

- **`(set-token-stale-handler! f)`** — registers a 0-arg fn returning a channel, called when
  the server refuses a request's token as stale (a 401 whose body carries
  `{:reason "token-stale"}`). The request is retried exactly once, after the handler's
  channel closes. Concurrent stale requests all park on the same reload rather than each
  triggering their own. With no handler registered, a stale-token 401 is returned to the
  caller unchanged, same as any other 401.

- **`(init)`** — wires re-frame integration: every response and SSE state update is mirrored
  into `[:_http-api :data]` in the re-frame `app-db`. Call once at boot if you want that;
  `execute`/`subscribe`/`get-data-reaction` all work without it.

- **`(get-data-reaction api-name endpoint-name)`** — a reagent reaction over an endpoint's
  latest value, for reactive UI without going through re-frame at all:
  `@(http-api/get-data-reaction :account :get)`.

## The GET/SSE upgrade path

The one design point worth calling out: **`:sse` can point at the same `:uri` as a `:get`**.
That's not a coincidence — it's the intended way to add a live-update path to an existing
endpoint without a parallel one:

```clojure
{:get     {:method :get :uri "/api/query/account-projection/me" :response-format :json}
 :changes {:method :sse :uri "/api/query/account-projection/me"}}
```

`:get` returns the current value once, on demand. `:changes` opens a stream at the identical
URI and delivers that same shape again every time it changes. Two entries exist because
`defapi` keys both the request/response cycle and the reaction by *endpoint name*, not by
URI — one URI, two access patterns, not two endpoints to keep in sync. On the server side this
only requires the same route to also serve `Accept: text/event-stream`; nothing about the
`:get` endpoint has to change.

## Real-world example

Declaring an API with a mix of GET, POST and SSE endpoints:

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
     ;; The SAME URI as `:get` — see "The GET/SSE upgrade path" above.
     :changes    {:method :sse  :uri "/api/query/account-projection/me"}
     :auto-renew {:method :post :uri "/api/commands/account/auto-renew"
                  :request-format :json :response-format :json}}))
```

A plain `execute` call, in a `go` block, with success/failure handled explicitly:

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

`response/payload` and `response/error-message` above are small app-side helpers around
`:data` (`payload` is `(:data res)` on success; `error-message` picks a message out of the
cljs-ajax error map on failure) — not part of this library, but a thin enough wrapper that
most apps end up writing something similar rather than reading `:data` inline everywhere.

`subscribe`/`unsubscribe!` in a component's lifecycle — the pattern that matters most for the
SSE half of this library:

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

- **`:on-open` fires on every reconnect, not just the first connection — treat it as "do a
  full re-fetch now."** The server subscribes before writing its first byte, so there is no
  gap between "the snapshot I fetched with `execute`" and "the stream picking up." Loading
  dependent data (`load-ledger!` above) from `:on-open` rather than once outside the
  subscription is what keeps it correct across a reconnect, not just on mount.
- **Always pair `subscribe` with `unsubscribe!` in `component-will-unmount`.** A subscription
  that outlives its component leaks an open connection and a pending reconnect timer.
- **A stale token does not loop.** A 401 with `{:reason "token-stale"}` triggers at most one
  token reload and one retry, and concurrent stale requests share that single reload instead
  of each starting their own. If `set-token-stale-handler!` was never called, the 401 just
  reaches your `execute`/`subscribe` callback like any other failure.
- **`execute` on an `:sse` endpoint (and `subscribe` on anything else) throws immediately**,
  naming the endpoint and the mismatch, rather than failing somewhere inside the HTTP/SSE
  transport where it would be far less obvious what went wrong.
- **`:headers` on a call overrides the auto-injected `:authorization`**, not merges under it —
  pass your own bearer header there only if you deliberately want a different token for that
  one call.

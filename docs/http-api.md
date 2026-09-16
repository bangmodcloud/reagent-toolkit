# reagent-http-api

`io.github.bangmodcloud/reagent-http-api` — namespace `bangmod.http-api.*`

A declarative wrapper around [cljs-ajax](https://github.com/JulianBirch/cljs-ajax) for REST
calls, plus a live-update path (`:method :sse`) and optional re-frame integration. Describe
each API once as a map of endpoints; `execute`/`raw-execute`/`subscribe` handle the
request/stream, bearer token injection, and (for SSE) reconnects.

## Install

See the [root README](../README.md#installation) for `deps.edn` / git-dependency snippets.

## Quick start

Declare the API once:

```clojure
(ns myapp.api.account
  (:require [bangmod.http-api.core :refer [defapi]]))

(defapi :account
  {:base-url "https://api.example.com"}
  {:get     {:method :get :uri "/api/query/account-projection/me" :response-format :json}
   :changes {:method :sse :uri "/api/query/account-projection/me"}})
```

### In a reagent component

`execute` fires the request and returns the endpoint's reaction. Call it where a form-2
component sets up — it runs once on mount — and deref in the render fn:

```clojure
(ns myapp.feature.account.view
  (:require [bangmod.http-api.core :as http-api]))

(defn account-card []
  (let [account (http-api/execute :account :get)]   ; request fires once, here
    (fn []
      (if-let [acct (:data @account)]
        [:div (:name acct)]
        [:div "Loading..."]))))
```

The reaction holds `nil` until the first response, then `{:success? true :data <parsed
body>}`; after a failed call it keeps the last good `:data`, flips `:success?` to `false` and
adds `:error <cljs-ajax error map>`. It is the endpoint's shared slot, so any other call
against `:account/:get` updates it too — and re-rendering never re-fires it, because the
call sits outside the render fn.

On an `:sse` endpoint `execute` opens the stream instead and returns the subscription handle,
which derefs the same way — `(:data @x)` is the latest frame. A stream has to be closed when
the component goes, so the shape that fits both cases is `r/with-let` with a `finally`:

```clojure
(defn account-live []
  (r/with-let [live (http-api/execute :account :changes)]   ; opens once, here
    [:div (:name (:data @live))
     (when-not (:connected? @live) [:span "reconnecting…"])]
    (finally (http-api/unsubscribe! live))))                ; no-op for a plain request
```

The SSE handle derefs to `{:sse? true :connected? bool :data <latest frame> :message-count n
:error <message or nil>}`; `unsubscribe!` on anything that isn't a subscription is a no-op,
so the `finally` line is the same whichever method the endpoint has.

### Auth

Attach a token to every request automatically, once at boot:

```clojure
(http-api/set-auth-token-provider! (fn [] @auth/access-token))
```

By default it goes out as `Authorization: Bearer <token>` (a call that sets its own
`:authorization` header keeps it). If your API wants the token somewhere else, register an
injector — a function over the finished request map, called whenever the provider returned
a token:

```clojure
(http-api/set-auth-token-injector!
  (fn [request token] (assoc-in request [:headers :x-api-key] token)))
```

Two kinds of 401 get a hook, so the auth feature owns them and no loader has to:

```clojure
;; 401 {:reason "token-stale"} — reload the token, the request retries once on its own
(http-api/set-token-stale-handler! (fn [] (auth/reload-token!)))   ; returns a channel

;; any other 401 — the session is over: forget the token, go to login
(http-api/set-unauthorized-handler!
  (fn [_result]
    (reset! auth/access-token nil)
    (router/navigate! :login)))
```

The library only tells the two apart; what a lapsed session *means* stays in your handler.
The caller still receives the 401 either way, so its own error branch renders as it would
have. Neither hook applies to SSE — a stream a 401 closes re-opens on its own backoff.

## API reference

`bangmod.http-api.core`:

| Function | Description |
| --- | --- |
| `(defapi api-name options endpoints-spec)` | Declares one named REST/SSE API. `options` is `{:base-url "..."}`. `endpoints-spec` is `endpoint-name -> spec` — see below. |
| `(execute api-name endpoint-name opts?)` | Request endpoint: fires it, returns the endpoint's reaction (`raw-execute` + `get-data-reaction`), `opts` as for `raw-execute`. `:sse` endpoint: opens the stream, returns the subscription handle, `opts` as for `subscribe`. Either way `(:data @x)` is the latest body/frame — deref it in a component. |
| `(raw-execute api-name endpoint-name opts?)` | Fires one request, returns a channel with `{:success? bool :data ...}`. `opts`: `:path-params` (fills `:param` in the URI), `:params` (query/body), `:body` (a prebuilt body sent as-is — a `js/FormData` for multipart; wins over `:params` and `:request-format`), `:headers` (overrides the auto-injected token for that call). The reaction/re-frame slot keeps the last successful `:data` across failures — a failed call sets `:success? false` and puts the failure under `:error` there. |
| `(subscribe api-name endpoint-name opts)` | Opens a live subscription against an `:sse` endpoint, returns a handle that derefs to `{:sse? true :connected? bool :data <latest frame> :message-count n :error msg-or-nil}`. `opts`: `:path-params`, `:params`, `:on-open` (0-arg, every reconnect including the first — see Gotchas), `:on-message` (1-arg, parsed frame data), `:on-error` (1-arg, message string), `:events` (extra named SSE event types delivered to `:on-message`, default `["changed"]` — unnamed frames always arrive). |
| `(unsubscribe! handle)` | Closes the connection, cancels any pending reconnect. Safe on an already-closed handle; a no-op on anything that isn't a subscription (a plain-request reaction, `nil`), so a component can pass whatever `execute` returned. |
| `(set-auth-token-provider! f)` | Registers a 0-arg fn returning the access token (or `nil`); whenever it returns one, the injector puts it on the request. Read fresh on every attempt, so a retry after a token reload carries the new token. SSE streams get it as `?access_token=`. |
| `(set-auth-token-injector! f)` | Registers `(fn [request token] -> request)` — how the token goes onto the cljs-ajax request map (`:uri :method :params :headers ...`). Default `bangmod.http-api.auth/bearer-injector`: `Authorization: Bearer <token>` unless the call set `:authorization` itself. `nil` restores the default. HTTP requests only. |
| `(set-token-stale-handler! f)` | Registers a 0-arg fn returning a channel, called when a 401 carries `{:reason "token-stale"}`. Retried exactly once after the handler's channel closes; concurrent stale requests share one reload. No handler registered ⇒ the 401 passes through unchanged. |
| `(set-unauthorized-handler! f)` | Registers a 1-arg fn called with the failed result when a request comes back 401 for any reason other than `token-stale` — the session is over, not merely out of date. Runs once per such response, beside delivering the result to the caller. HTTP requests only. No handler registered ⇒ the 401 passes through unchanged. |
| `(init)` | Wires re-frame integration: every response/SSE update mirrors into `[:_http-api :data]` in the app-db. Optional — `execute`/`raw-execute`/`subscribe`/`get-data-reaction` work without it. |
| `(get-data-reaction api-name endpoint-name)` | Reagent reaction over an endpoint's stored slot, without firing anything: `@(http-api/get-data-reaction :account :get)`. `nil` before the first response. Throws if the endpoint was never declared. |

`endpoints-spec` per-endpoint keys:

- `:method` — `:get`, `:post`, `:put`, `:patch`, `:delete`, or `:sse` (opened with
  `subscribe` or `execute`, never `raw-execute`; `:request-format`/`:response-format`/`:timeout` don't apply).
- `:uri` — path, may contain `:param` placeholders (`"/api/leaves/:id"`); substituted
  values are percent-encoded.
- `:with-credentials` — `true` to send cookies on cross-origin requests.
- `:request-format` — `:json`, `:url`, `:transit`, `:raw`.
- `:response-format` — `:json`, `:text`, `:transit`, `:raw`.
- `:timeout` — ms, default `10000`.

## `raw-execute`: the request as a value

When you need *this* request's result — a go block, an event handler, an error branch, a
form submission — `raw-execute` returns a `core.async` channel delivering exactly one map:
`{:success? true :data <parsed response body>}` on success, or
`{:success? false :data <cljs-ajax error map>}` on failure (that shape — `:status`,
`:response`, ... — is [cljs-ajax's](https://github.com/JulianBirch/cljs-ajax), not this
library's):

```clojure
(ns myapp.feature.account.event
  (:require [cljs.core.async :as a]
            [re-frame.core :as rf]
            [bangmod.http-api.core :as http-api]))

(defn load-account! []
  (a/go
    (let [res (a/<! (http-api/raw-execute :account :get))]
      (if (:success? res)
        (rf/dispatch [:account/set (:data res)])
        (rf/dispatch [:account/set-error (:data res)])))))
```

`execute` is exactly `raw-execute` followed by `get-data-reaction`; both update the same
slot, so a `raw-execute` from an event handler also refreshes every component bound to the
endpoint's reaction.

### Uploading a file

`:params` goes through the endpoint's `:request-format`; a multipart upload must not. Pass
the `js/FormData` as `:body` instead — it is sent as-is, no format is applied and no
`Content-Type` is set, so the browser writes the multipart boundary itself:

```clojure
(defapi :document {:base-url "/api"}
  {:upload {:method :post :uri "/documents" :response-format :json}})

(defn upload! [file]
  (let [form-data (doto (js/FormData.) (.append "file" file))]
    (http-api/raw-execute :document :upload {:body form-data})))
```

`:body` wins over `:params` and `:request-format` when both are given; `:path-params`,
`:headers` and the auto-injected token still apply.

## `subscribe` / `unsubscribe!`: the stream with callbacks

`execute` on an `:sse` endpoint is `subscribe` with no callbacks. Call `subscribe` yourself
when you want to *react* to the stream — re-fetch something on every message, dispatch into
re-frame, log drops — rather than only render its latest frame:

```clojure
(http-api/subscribe :account :changes
  {:on-open    (fn [] (load-ledger!))               ; every (re)connection, incl. the first
   :on-message (fn [acct]                           ; one parsed frame
                 (rf/dispatch [:account/set acct])
                 (load-ledger!))
   :on-error   (fn [msg] (js/console.warn "stream:" msg))
   :events     ["changed"]})                        ; named SSE events to treat as messages
```

`opts`: `:path-params` / `:params` as for a request (the token rides along as
`?access_token=`), plus

- `:on-open` — 0-arg, on **every** connection including the first. This is where a full
  re-fetch of dependent data belongs: the server subscribes before writing its first byte,
  so nothing can slip between that fetch and the stream, and a reconnect replays the same
  fetch so nothing missed while disconnected stays missed.
- `:on-message` — 1-arg, the frame's `data` parsed as JSON (keywordized), or the raw string
  if it isn't JSON.
- `:on-error` — 1-arg, a message string; the connection dropped. Reconnect is not your job.
- `:events` — the SSE `event:` names delivered to `:on-message`, default `["changed"]`.
  Unnamed frames always arrive; a frame the server sends under any other name is ignored,
  so this must match what the server emits.

It returns the subscription handle. Deref it for the stream's state —
`{:sse? true :connected? bool :data <latest frame> :message-count n :error <msg or nil>}` —
and pass it to `unsubscribe!` when the component goes:

```clojure
(defn account-page []
  (r/with-let [live (http-api/subscribe :account :changes
                      {:on-open (fn [] (load-ledger!))
                       :on-message (fn [acct] (rf/dispatch [:account/set acct]) (load-ledger!))})]
    [:div (:name (:data @live))
     (when-not (:connected? @live) [:span "reconnecting…"])]
    (finally (http-api/unsubscribe! live))))
```

(`r/create-class` with `:component-did-mount` / `:component-will-unmount` works the same
way — subscribe in the one, `unsubscribe!` in the other.) The same slot backs
`get-data-reaction` and, after `init`, `[:_http-api :data api endpoint]` in the app-db, so
a component elsewhere can render the stream without holding the handle. `:message-count`
exists because two consecutive frames can be equal (a snapshot resent after a reconnect) and
a reaction over an equal value does not re-fire.

`(unsubscribe! handle)` closes the `EventSource` and cancels any pending reconnect. It is
safe on an already-closed handle and a no-op on anything that isn't a subscription — the
reaction `execute` returns for a request, `nil` — so cleanup code can pass whatever it was
handed.

### Reconnecting

Owned by the library, not your code. When the browser's `EventSource` gives up (a non-200
status, a wrong `Content-Type` — a 401, a 503) the subscription re-opens on a backoff of
1 s doubling to a 30 s cap, re-reading the token from `set-auth-token-provider!` each time;
while `EventSource` is still retrying on its own (the server's `retry:` field) nothing is
done but reporting the drop to `:on-error`. `:on-open` fires again on success, which is why
the re-fetch belongs there.

The server can also end a connection on purpose by sending a `reconnect` event whose data
is `{"reason": "..."}` — a connection deadline, load shedding. The client re-opens on the
same backoff (1 s after a healthy connection); the server's first frame on the new connection is the current state, so what
was missed is replaced, not replayed. One reason is special: `"token-stale"` goes through
`set-token-stale-handler!`'s reload first, because re-opening with the same refused token
would loop.

## Gotchas

- **`:on-open` fires on every reconnect, not just the first — treat it as "do a full
  re-fetch now."** The server subscribes before writing its first byte, so there's no gap
  between the `execute` snapshot and the stream. Loading dependent data from `:on-open`
  rather than once on mount is what keeps it correct across a reconnect.
- **Always pair `subscribe` (or `execute` on an `:sse` endpoint) with `unsubscribe!`** in
  `component-will-unmount` / `with-let`'s `finally` — a subscription that outlives its
  component leaks a connection and a pending reconnect timer.
- **A stale token retries at most once**, and concurrent stale requests share that one
  reload. With no `set-token-stale-handler!` registered, the 401 just reaches your callback
  like any other failure.
- **`set-unauthorized-handler!` does not replace the caller's error branch** — the 401 is
  delivered to the channel as well, so a loader that renders its own failure still does. A
  second `token-stale` after the reload is delivered the same way and does *not* fire the
  handler: the library does not decide that "stale twice" means the session is over.
- **`:body` skips `:request-format` entirely** — no `Content-Type` is set. For a JSON body
  keep using `:params`; `:body` is for what the browser must encode itself (`js/FormData`).
- **`raw-execute` on an `:sse` endpoint (and `subscribe` on anything else) throws
  immediately**, naming the mismatch, rather than failing somewhere inside the transport.
- **`:headers` on a call overrides the auto-injected `:authorization`**, not merges under it.

# reagent-http-api

[![Clojars Project](https://img.shields.io/clojars/v/io.github.bangmodcloud/reagent-http-api.svg?color=blue)](https://clojars.org/io.github.bangmodcloud/reagent-http-api)

A declarative, full-lifecycle HTTP and Server-Sent Events (SSE) client for ClojureScript, designed for [Reagent](https://reagent-project.github.io/) and [re-frame](https://day8.github.io/re-frame/).

---

## Why reagent-http-api?

Front-end applications often struggle with scattered endpoint URLs, repetitive Bearer token injection, messy token expiration flows, and fragmented codebases where REST and real-time streaming use completely different abstractions.

`reagent-http-api` brings order to your data layer:

* 📋 **Declarative Endpoints:** Define your APIs once with `defapi`. Base URLs, formats, timeouts, and URL param bindings are configured centrally.
* ⚡ **Unified REST & SSE:** Query a snapshot with a standard `GET`, and subscribe to live changes with `SSE`—**using the exact same URI**.
* 🔐 **Automatic Auth & Single-Flight Refresh:** Automatically injects Bearer tokens. When a token expires, all concurrent requests park safely while a single refresh runs.
* 🔄 **Reagent & re-frame Native:** Consume data reactively via Reagent reactions (`get-data-reaction`) or sync responses directly into re-frame's `app-db`.

---

## Installation

Add to your `deps.edn`:

```clojure
io.github.bangmodcloud/reagent-http-api {:mvn/version "0.1.0"}
```

---

## Quick Start

### 1. Declare your API endpoints

```clojure
(ns myapp.api.user
  (:require [bangmod.http-api.core :refer [defapi]]))

(defapi :user
  {:base-url "https://api.example.com"}
  {:me      {:method :get
             :uri "/api/v1/users/me"
             :response-format :json}
   :update  {:method :patch
             :uri "/api/v1/users/:id"
             :request-format :json
             :response-format :json}
   :stream  {:method :sse
             :uri "/api/v1/users/me"}})
```

### 2. Make REST Calls (`execute`)

`execute` returns a `core.async` channel that delivers a result map: `{:success? bool :data ...}`.

```clojure
(ns myapp.events
  (:require [cljs.core.async :as a]
            [re-frame.core :as rf]
            [bangmod.http-api.core :as http-api]))

(defn fetch-me! []
  (a/go
    (let [{:keys [success? data]} (a/<! (http-api/execute :user :me))]
      (if success?
        (rf/dispatch [:user/set data])
        (rf/dispatch [:user/set-error (:status data)])))))

(defn update-profile! [user-id new-name]
  (a/go
    (let [res (a/<! (http-api/execute :user :update
                                      {:path-params {:id user-id}
                                       :params {:name new-name}}))]
      (when (:success? res)
        (println "Profile updated!")))))
```

### 3. Subscribe to Real-Time Updates (`subscribe`)

For live SSE streaming, use `subscribe`. It automatically handles parsing and connection drops:

```clojure
(def stream-handle
  (http-api/subscribe :user :stream
    {:on-message (fn [payload]
                   (rf/dispatch [:user/set payload]))
     :on-error   (fn [err]
                   (js/console.error "SSE Error:" err))}))

;; When tearing down:
(http-api/unsubscribe! stream-handle)
```

---

## Killer Feature: The GET / SSE Dual-Mode URI

In modern apps, a common requirement is:
1. Fetch the initial data state immediately on page load (`GET`).
2. Keep that data up to date via live stream (`SSE`).

Usually, backends expose this over the same route (e.g. `Accept: application/json` vs `Accept: text/event-stream`). `reagent-http-api` makes this seamless by letting both access patterns point to the same URI:

```clojure
(defapi :notifications
  {:base-url "https://api.example.com"}
  {:get     {:method :get :uri "/api/notifications" :response-format :json}
   :changes {:method :sse :uri "/api/notifications"}})
```

Because `defapi` keys by *endpoint name* rather than URI, your app can cleanly fetch on demand with `(execute :notifications :get)` and stream with `(subscribe :notifications :changes)` without duplicating route logic.

---

## Authentication & Token Refresh

### Automatic Bearer Token Injection
Configure a token provider once at application startup. Every request automatically receives `Authorization: Bearer <token>`:

```clojure
(http-api/set-auth-token-provider! (fn [] @auth-token-atom))
```

### Automatic 401 Refresh (Single-Flight)
When an API responds with `401 Unauthorized` carrying `{:reason "token-stale"}`, `reagent-http-api` pauses requests and invokes your refresh handler:

```clojure
(http-api/set-token-stale-handler!
  (fn []
    ;; Return a core.async channel that closes when the refresh finishes
    (auth/refresh-access-token!)))
```

> [!TIP]
> **Single-Flight Concurrency:** If 5 requests fail with stale tokens at the same time, only **one** refresh operation is triggered. All 5 requests wait for that single refresh to finish, and are then automatically retried with the new token.

---

## Reagent & re-frame Integration

### Direct Reagent Reaction
If you want reactive UI without re-frame:

```clojure
(defn user-profile []
  (let [user-data @(http-api/get-data-reaction :user :me)]
    [:div "Hello, " (:name user-data)]))
```

### re-frame Sync
Call `(http-api/init)` once at boot. Every successful response and SSE update will be mirrored into your re-frame `app-db` under `[:_http-api :data]`.

---

## API Reference

### Configuration & Declarations

| Function | Signature | Description |
| :--- | :--- | :--- |
| `defapi` | `[api-name options endpoints]` | Declares a named REST/SSE API. |
| `set-auth-token-provider!` | `[f]` | Registers a 0-arg function returning current token string. |
| `set-token-stale-handler!` | `[f]` | Registers a 0-arg function returning a channel for token refresh. |
| `init` | `[]` | Connects HTTP/SSE state automatically into re-frame `app-db`. |

#### `defapi` Endpoint Options
* `:method` — `:get`, `:post`, `:put`, `:patch`, `:delete`, or `:sse`.
* `:uri` — Relative endpoint path. Supports `:id` style path params.
* `:request-format` — `:json`, `:transit`, `:url`, or `:raw`.
* `:response-format` — `:json`, `:transit`, `:text`, or `:raw`.
* `:timeout` — Request timeout in milliseconds (default: `10000`).

### Operations

| Function | Signature | Description |
| :--- | :--- | :--- |
| `execute` | `[api-id endpoint-id opts?]` | Makes a REST request. Returns `core.async` channel with `{:success? bool :data ...}`. |
| `subscribe` | `[api-id endpoint-id opts]` | Opens an SSE connection. Returns an opaque handle. |
| `unsubscribe!` | `[handle]` | Closes the SSE connection and cancels pending reconnects. |
| `get-data-reaction` | `[api-id endpoint-id]` | Returns a Reagent reaction of the latest response/event data. |

---

## Gotchas & Best Practices

> [!IMPORTANT]
> **Always Unsubscribe on Unmount**
> When using `subscribe` in a component, save the handle and call `unsubscribe!` in `component-will-unmount` to prevent memory leaks and dangling reconnect timers:
> ```clojure
> :component-will-unmount (fn [] (http-api/unsubscribe! @sub-handle))
> ```

> [!NOTE]
> **Reconnection Semantics (`:on-open`)**
> The `:on-open` callback fires on **every** connection (including reconnects after network dropouts). Use `:on-open` to trigger a re-fetch of any snapshot data to guarantee UI consistency.

> [!CAUTION]
> **Endpoint Type Guard**
> Calling `execute` on an endpoint defined with `:method :sse` (or `subscribe` on a non-SSE endpoint) will throw an explicit error immediately.

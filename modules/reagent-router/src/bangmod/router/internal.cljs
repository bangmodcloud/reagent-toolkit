(ns bangmod.router.internal
  (:require
   [bidi.bidi :as bidi]
   [cemerick.url :as cemerick]
   [bangmod.router.db :as db]
   [bangmod.router.table :as table]
   [pushy.core :as pushy]))

(declare navigate!)

(defmulti routed-component identity)

;; Every route keyword installed into `routed-component`, in registration order.
;; `routed-component` is a defmulti, so a second `defmethod` for the same keyword silently
;; replaces the first — a shadowed route renders the wrong component with no error anywhere.
;; This atom makes that detectable (see `register-route-key!` for the collision,
;; `bangmod.router.core/registration-report` for the orphan).
(defonce registered-route-keys (atom []))

(defn- register-route-key! [route-key]
  (when (some #{route-key} @registered-route-keys)
    (let [msg (str "Duplicate route registration for " route-key
                   " — the later one silently replaces the earlier. "
                   "Registered so far: " (pr-str @registered-route-keys))]
      (if ^boolean goog.DEBUG
        (throw (ex-info msg {:route-key route-key
                             :registered @registered-route-keys}))
        ;; A white screen in production is worse than a shadowed route.
        (js/console.error msg))))
  (swap! registered-route-keys conj route-key))

(defn compile-route
  "Compiles one [pattern matched] route pair into a plain bidi table, installing a
   `routed-component` method for every [handler-keyword component] leaf. The structural
   walk lives in `bangmod.router.table` (pure, unit-tested); only the side effects live
   here."
  [route]
  (table/compile-pair
   route
   (fn [route-key component]
     (register-route-key! route-key)
     (defmethod routed-component route-key [_] [component]))))

(defn register-routes [routes]
  (swap! db/a-app-routes conj (compile-route routes)))

(defn set-matched-route! [match]
  (reset! db/a-matched-route match))

(defn parse
  [url]
  (let [query-params (->> (:query (cemerick/url url))
                          (map (fn [[k v]] [(keyword k) v]))
                          (into {}))
        matched-route (->> @db/a-app-routes
                           (map (fn [route]
                                  (bidi/match-route route url)))
                           (filter (complement nil?))
                           first (merge {:query-params query-params}))]
    matched-route))

;; `pushy/pushy` builds an Html5History against js/window at construction, so guard it for
;; non-DOM contexts (node test runner). In a browser window always exists and this is
;; unchanged; `start!` is the only caller and is never invoked outside the browser.
(defonce history
  (when (exists? js/window)
    (pushy/pushy set-matched-route! parse)))

(defn start!
  [{:keys [default-component] :as _options}]
  (defmethod routed-component :default [_]
    (if (some? default-component)
      [default-component]
      [:div "No component found for this route."]))
  (db/re-frame-integration)
  (pushy/start! history))

(defn url-for
  [& args]
  ;; A map carrying :query contributes the query string and is stripped before the rest of
  ;; the args reach bidi/path-for as its handler + path-param kwargs.
  (let [query-map? (fn [x] (and (map? x) (contains? x :query)))
        query-params (some-> (filter query-map? args) first :query)
        path-args (remove query-map? args)
        path (->> @db/a-app-routes
                  (map (fn [route]
                         (apply bidi/path-for (into [route] path-args))))
                  (filter (complement nil?))
                  first)
        query (when query-params (str "?" (cemerick/map->query query-params)))]
    (str path query)))

(defn navigate!
  [handler]
  (let [token (if (keyword? handler)
                (url-for handler)
                handler)]
    (pushy/set-token! history token)))

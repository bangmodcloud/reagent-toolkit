(ns bangmod.router.internal
  (:require
   [bidi.bidi :as bidi]
   [cemerick.url :as cemerick]
   [reagent.core :as r]
   [bangmod.router.db :as db]
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

(defn compile-route [route]
  (if (map? route)
    (into
     {}
     (for [[k v] route]
       (let [compiled-route (compile-route v)]
         [k compiled-route])))
    (if (coll? route)
      (if (-> route first keyword?)
        (let [component (second route)
              route-key (first route)]
          (register-route-key! route-key)
          (defmethod routed-component route-key [] [component])
          route-key)
        (->> route
             (partition 2)
             (map (fn [[route-key route-value]]
                    [route-key (compile-route route-value)]))
             flatten)))))

(defn register-routes [routes]
  (swap! db/a-app-routes
         (fn [current-routes new-routes]
           (let [final-routes (conj current-routes (into [] (compile-route new-routes)))]
             final-routes))
         routes))

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
  [{:keys [default-component] :as options}]
  (defmethod routed-component :default [] (let []
                                            (if (-> default-component nil? not)
                                              [default-component]
                                              [:div "No component found for this route."])))
  (db/re-frame-integration)
  (pushy/start! history))

(defn url-for
  [& args]
  (let [query-params (-> (filter #(:query %) args) first :query)
        path (->> @db/a-app-routes
                  (map (fn [route]
                         (apply bidi/path-for (into [route] args))))
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
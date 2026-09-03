(ns bangmod.router.core
  (:require [bangmod.router.internal :as router-internal]
            [bangmod.router.db :as db]
            [reagent.ratom :as ratom]))

(defn start!
  [options]
  (router-internal/start! options))

(defn register-routes
  [routes]
  (router-internal/register-routes routes))

(defn url-for
  [& args]
  (apply router-internal/url-for args))

(defn navigate!
  [handler]
  (router-internal/navigate! handler))

(defn- table-route-keys
  "Handler keywords reachable in a compiled bidi table.

   Only map VALUES are followed: a path parameter (`[\"/projects/\" :id]`) is a
   keyword in a map KEY, and collecting it would report a phantom route."
  [node]
  (cond
    (keyword? node) [node]
    (map? node) (mapcat table-route-keys (vals node))
    (coll? node) (mapcat table-route-keys node)
    :else []))

(defn registration-report
  "{:routed [...] :registered [...] :duplicates [...] :orphan-routes [...] :orphan-registrations [...]}

   `routed` are the handlers the route table can match; `registered` are the ones with
   a `routed-component` implementation. A duplicate silently shadows a component; an
   orphan is an ABSENCE — a route that renders blank, or a component nothing reaches —
   which the registration-time collision check cannot see."
  []
  (let [routed (vec (distinct (mapcat table-route-keys @db/a-app-routes)))
        registered @router-internal/registered-route-keys
        registered-set (set registered)
        routed-set (set routed)]
    {:routed routed
     :registered registered
     :duplicates (->> registered frequencies (keep (fn [[k n]] (when (< 1 n) k))) vec)
     :orphan-routes (vec (remove registered-set routed))
     :orphan-registrations (vec (remove routed-set (distinct registered)))}))

(def atom-params (ratom/make-reaction (fn [] (get-in @db/a-matched-route [:route-params] {}))))
(def atom-query-params (ratom/make-reaction (fn [] (get-in @db/a-matched-route [:query-params] {}))))
(def atom-matched-route (ratom/make-reaction (fn [] (get-in @db/a-matched-route [:handler] :default))))
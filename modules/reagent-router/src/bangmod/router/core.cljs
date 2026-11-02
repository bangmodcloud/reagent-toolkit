(ns bangmod.router.core
  (:require [bangmod.router.internal :as router-internal]
            [bangmod.router.db :as db]
            [bangmod.router.match :as match]
            [bangmod.router.table :as table]
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

(defn match-path
  "The route the registered table gives `path` (a path with an optional query string, as the
   browser's location would hold it): `{:handler kw :route-params {kw string} :query-params
   {kw string}}`, or nil when no route matches. Both param maps are `{}` when there are none.
   Reads only the tables `register-routes` installed; needs no browser, no `start!`, and
   changes no state.

   It is the match navigation makes (the same code pushy calls), so when two registered
   tables match one path, the table registered first wins here as it does on screen.

     (match-path \"/projects/42?tab=logs\")
     ;; => {:handler :project-detail :route-params {:id \"42\"} :query-params {:tab \"logs\"}}
     (match-path \"/nowhere\")
     ;; => nil"
  [path]
  (match/match-path @db/a-app-routes path))

(defn registration-report
  "{:routed [...] :registered [...] :duplicates [...] :orphan-routes [...] :orphan-registrations [...]}

   `routed` are the handlers the route table can match; `registered` are the ones with
   a `routed-component` implementation. A duplicate silently shadows a component; an
   orphan is an ABSENCE — a route that renders blank, or a component nothing reaches —
   which the registration-time collision check cannot see."
  []
  (let [routed (vec (distinct (mapcat table/route-keys @db/a-app-routes)))
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

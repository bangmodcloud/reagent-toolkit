(ns bangmod.router.db
  (:require [reagent.core :as r]
            [re-frame.core :as rf]))

(def a-matched-route (r/atom {:handler :__default}))
(def a-app-routes (r/atom []))

(defn re-frame-integration
  []
  (rf/reg-event-fx
    :_router_set-matched-route
    (fn [{:keys [db]} [_ matched-route]]
      {:db (-> db
               (assoc-in [:_router :matched-route] matched-route))}))
  (rf/reg-event-fx
    :_router_set-app-routes
    (fn [{:keys [db]} [_ app-routes]]
      {:db (-> db
               (assoc-in [:_router :app-routes] app-routes))}))
  (add-watch a-app-routes :app-routes-reframe
             (fn [_ _ _ new-app-routes]
               (rf/dispatch [:_router_set-app-routes new-app-routes])))
  (add-watch a-matched-route :matched-route-reframe
             (fn [_ _ _ new-matched-route]
               (rf/dispatch [:_router_set-matched-route new-matched-route])))
  (rf/dispatch-sync [:_router_set-app-routes @a-app-routes])
  (rf/dispatch-sync [:_router_set-matched-route @a-matched-route])
  )
(ns bangmod.http-api.re-frame
  (:require [re-frame.core :as rf]))


(defn integrate
  [{:keys [data-atom]}]
  (rf/reg-event-fx
    ::_http-api-update-state
    (fn [{:keys [db]} [_ state]]
      {:db (-> db
               (assoc-in [:_http-api :data] state))}))
  (add-watch data-atom :http-api-data-re-frame
             (fn [_ _ _ new-data-state]
               (rf/dispatch [::_http-api-update-state new-data-state])))
  ;; Seed app-db with whatever is already there, so [:_http-api :data] exists from init
  ;; rather than from the first request.
  (rf/dispatch-sync [::_http-api-update-state @data-atom]))

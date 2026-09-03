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
               (rf/dispatch [::_http-api-update-state new-data-state]))))

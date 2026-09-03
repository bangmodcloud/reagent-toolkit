(ns bangmod.router.views
  (:require [bangmod.router.core :as router]
            [bangmod.router.internal :as router-internal]))

(defn matched-route-panel []
  (let []
    (router-internal/routed-component  @router/atom-matched-route)))

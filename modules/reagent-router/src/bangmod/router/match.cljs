(ns bangmod.router.match
  "The pure half of URL matching: a URL against a seq of compiled route tables, with the
   tables passed in. `bangmod.router.internal/parse` (pushy's matcher) and
   `bangmod.router.core/match-path` both delegate here, so a URL resolves the same way for
   navigation and for a caller asking directly. Free of pushy and reagent, so it loads under
   `:node-test`."
  (:require [bidi.bidi :as bidi]
            [cemerick.url :as cemerick]))

(defn parse
  "Matches `url` (a path with an optional query string) against `tables`, the compiled
   route tables in registration order. The first table that matches wins. Returns bidi's
   match merged over `{:query-params {kw \"v\"}}`. With no match, `:handler` is absent but
   `:query-params` is still present. This is the shape navigation stores as the matched
   route."
  [tables url]
  (let [query-params (->> (:query (cemerick/url url))
                          (map (fn [[k v]] [(keyword k) v]))
                          (into {}))
        matched-route (->> tables
                           (map (fn [route]
                                  (bidi/match-route route url)))
                           (filter (complement nil?))
                           first (merge {:query-params query-params}))]
    matched-route))

(defn match-path
  "`parse`, narrowed to the public contract: `{:handler kw :route-params {kw string}
   :query-params {kw string}}`, both maps empty when there are none, no other keys, or nil
   when no table matches `path`."
  [tables path]
  (let [{:keys [handler route-params query-params]} (parse tables path)]
    (when (some? handler)
      {:handler      handler
       :route-params (or route-params {})
       :query-params (or query-params {})})))

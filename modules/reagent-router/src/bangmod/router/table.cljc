(ns bangmod.router.table
  "The pure half of route compilation: the bidi-table walk, minus the defmethod/registry
   side effects, so it can be unit-tested under `:node-test` (`bangmod.router.internal`
   cannot load there — pushy and reagent touch `js/window` at load time).

   Grammar accepted — bidi's, with one change: a leaf is `[handler-keyword component]`
   instead of a bare handler.

     RoutePair := [Pattern Matched]
     Matched   := [handler-kw component] | {Pattern Matched ...} | [RoutePair ...]

   Patterns (strings, `[\"/projects/\" :id]` vectors, ...) are never walked, so a path
   parameter keyword is never mistaken for a handler.")

(declare compile-pair)

(defn compile-matched
  "Compiles the `matched` side of a route pair: calls `(register-leaf! handler-kw component)`
   for every leaf and returns the structure with each leaf replaced by its handler keyword —
   i.e. a plain bidi table. Throws on anything outside the grammar, with the offending form
   in the message — a malformed table must not compile into one that silently matches
   nothing."
  [matched register-leaf!]
  (cond
    (map? matched)
    (into {} (for [[pattern sub] matched]
               [pattern (compile-matched sub register-leaf!)]))

    (and (vector? matched) (keyword? (first matched)))
    (if (= 2 (count matched))
      (let [[route-key component] matched]
        (register-leaf! route-key component)
        route-key)
      (throw (ex-info (str "A route leaf must be [handler-keyword component], got: "
                           (pr-str matched))
                      {:leaf matched})))

    (vector? matched)
    (mapv #(compile-pair % register-leaf!) matched)

    :else
    (throw (ex-info (str "Unsupported route table shape: " (pr-str matched)
                         " — a leaf must be [handler-keyword component]; a table must be"
                         " a map or a vector of [pattern matched] pairs")
                    {:matched matched}))))

(defn compile-pair
  "Compiles one `[pattern matched]` route pair."
  [pair register-leaf!]
  (when-not (and (vector? pair) (= 2 (count pair)) (not (keyword? (first pair))))
    (throw (ex-info (str "A route must be a [pattern matched] pair, got: " (pr-str pair))
                    {:route pair})))
  (let [[pattern matched] pair]
    [pattern (compile-matched matched register-leaf!)]))

(defn matched-keys
  "Handler keywords reachable in a compiled `matched` value. Only map VALUES and pair
   SECONDS are followed — a pattern is never walked, so `[\"/projects/\" :id]` cannot
   produce a phantom `:id` route."
  [matched]
  (cond
    (keyword? matched) [matched]
    (map? matched) (mapcat matched-keys (vals matched))
    (vector? matched) (mapcat (fn [[_ sub]] (matched-keys sub)) matched)
    :else []))

(defn route-keys
  "Handler keywords reachable in one compiled route pair."
  [compiled-pair]
  (matched-keys (second compiled-pair)))

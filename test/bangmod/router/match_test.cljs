(ns bangmod.router.match-test
  "`bangmod.router.core/match-path` is `(match/match-path @db/a-app-routes path)`, and
   `register-routes` appends `(compile-pair route ...)` to that vector. `register` below does
   the same to a local vector, so these tests cover the public fn's matching without
   reagent (which this node build cannot load)."
  (:require [cljs.test :refer [deftest is testing]]
            [bidi.bidi :as bidi]
            [bangmod.router.match :as match]
            [bangmod.router.table :as table]))

(defn- register
  "What `register-routes` does to the table vector, minus the component registry."
  [tables route]
  (conj tables (table/compile-pair route (fn [_ _]))))

(def ^:private projects
  (register [] ["" {"/"                  [:home 'home]
                    "/projects"          [:projects 'projects]
                    ["/projects/" :id]   {""        [:project-detail 'detail]
                                          "/cycles" [:project-cycles 'cycles]}
                    ["/" :org "/accept-invite"] [:accept-invite 'invite]}]))

(deftest plain-route-test
  (is (= {:handler :projects :route-params {} :query-params {}}
         (match/match-path projects "/projects")))
  (is (= {:handler :home :route-params {} :query-params {}}
         (match/match-path projects "/"))))

(deftest path-params-test
  (is (= {:handler :project-detail :route-params {:id "pr-1"} :query-params {}}
         (match/match-path projects "/projects/pr-1")))
  (is (= {:handler :project-cycles :route-params {:id "pr-1"} :query-params {}}
         (match/match-path projects "/projects/pr-1/cycles")))
  (testing "the docs/router.md example"
    (let [tables (register [] ["/projects" {""                  [:projects 'list]
                                            ["/" :id "/cycles"] [:project-cycles 'cycles]}])]
      (is (= {:handler :project-cycles :route-params {:id "pr-1"} :query-params {:tab "open"}}
             (match/match-path tables "/projects/pr-1/cycles?tab=open")))
      (is (= {:handler :projects :route-params {} :query-params {}}
             (match/match-path tables "/projects")))
      (is (nil? (match/match-path tables "/nowhere"))))))

(deftest query-params-test
  (is (= {:handler :accept-invite :route-params {:org "acme"} :query-params {:link "a.b"}}
         (match/match-path projects "/acme/accept-invite?link=a.b")))
  (is (= {:handler :projects :route-params {} :query-params {:tab "logs" :page "2"}}
         (match/match-path projects "/projects?tab=logs&page=2"))))

(deftest no-match-is-nil-test
  (is (nil? (match/match-path projects "/nowhere")))
  (testing "a query string on an unmatched path still gives nil, not a half-filled map"
    (is (nil? (match/match-path projects "/nowhere?tab=logs"))))
  (is (nil? (match/match-path [] "/projects")) "no tables registered")
  (testing "parse (pushy's matcher) keeps its shape: no :handler, :query-params present"
    (is (= {:query-params {:tab "logs"}} (match/parse projects "/nowhere?tab=logs")))))

(deftest only-contract-keys-test
  (testing "keys bidi adds beyond :handler/:route-params (here a tag) do not leak"
    (let [tagged [["" {"/x" (bidi/tag :x :some-tag)}]]]
      (is (= :some-tag (:tag (match/parse tagged "/x"))))
      (is (= {:handler :x :route-params {} :query-params {}}
             (match/match-path tagged "/x"))))))

(deftest tables-registered-in-turn-test
  (let [first-table  (register [] ["" {"/a"      [:a 'a]
                                       "/shared" [:shared-first 'first]}])
        both         (register first-table ["" {"/b"      [:b 'b]
                                                "/shared" [:shared-second 'second]}])]
    (testing "a path only the later table has matches once it is registered"
      (is (nil? (match/match-path first-table "/b")))
      (is (= :b (:handler (match/match-path both "/b")))))
    (testing "the earlier table still matches its own paths"
      (is (= :a (:handler (match/match-path both "/a")))))
    (testing "a path both tables match goes to the first registered, as navigation does"
      (is (= :shared-first (:handler (match/match-path both "/shared"))))
      (is (= (:handler (match/parse both "/shared"))
             (:handler (match/match-path both "/shared")))))))

(deftest agrees-with-parse-test
  (testing "match-path is parse narrowed: same handler and params on every matching path"
    (doseq [path ["/" "/projects" "/projects/pr-1" "/projects/pr-1/cycles?x=1"
                  "/acme/accept-invite?link=a.b"]]
      (let [parsed (match/parse projects path)]
        (is (= {:handler      (:handler parsed)
                :route-params (or (:route-params parsed) {})
                :query-params (:query-params parsed)}
               (match/match-path projects path))
            path)))))

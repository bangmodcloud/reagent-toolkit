(ns bangmod.router.table-test
  (:require [cljs.test :refer [deftest is testing]]
            [bangmod.router.table :as table]))

(defn- compile-collecting
  "Compiles `route`, returning {:compiled <bidi table> :leaves [[kw component] ...]}."
  [route]
  (let [seen (atom [])]
    {:compiled (table/compile-pair route (fn [k c] (swap! seen conj [k c])))
     :leaves @seen}))

(deftest map-table-test
  (let [{:keys [compiled leaves]} (compile-collecting ["" {"/account" [:account 'account-page]}])]
    (is (= ["" {"/account" :account}] compiled))
    (is (= [[:account 'account-page]] leaves))))

(deftest param-pattern-as-map-key-test
  (let [{:keys [compiled]} (compile-collecting
                            ["" {"/" [:home 'home-view]
                                 ["/projects/" :id] [:project-detail 'project-view]
                                 "/settings" {"/profile" [:settings-profile 'profile-view]}}])]
    (is (= ["" {"/" :home
                ["/projects/" :id] :project-detail
                "/settings" {"/profile" :settings-profile}}]
           compiled))))

(deftest vector-of-pairs-table-test
  ;; The shape 0.1.0 silently corrupted: partition-2 dropped the third entry and flatten
  ;; leaked the component fn into the table.
  (let [{:keys [compiled leaves]} (compile-collecting
                                   ["" [["/" [:home 'home-view]]
                                        [["/projects/" :id] [:project-detail 'project-view]]
                                        ["/settings" {"/profile" [:settings-profile 'profile-view]
                                                      "/billing" [:settings-billing 'billing-view]}]]])]
    (is (= ["" [["/" :home]
                [["/projects/" :id] :project-detail]
                ["/settings" {"/profile" :settings-profile
                              "/billing" :settings-billing}]]]
           compiled))
    (is (= [:home :project-detail :settings-profile :settings-billing]
           (mapv first leaves)))))

(deftest route-keys-test
  (let [{:keys [compiled]} (compile-collecting
                            ["" [["/" [:home 'home-view]]
                                 [["/projects/" :id] [:project-detail 'project-view]]
                                 ["/settings" {"/profile" [:settings-profile 'profile-view]}]]])]
    (testing "finds every handler, and never reports a path-param keyword as a route"
      (is (= [:home :project-detail :settings-profile]
             (vec (table/route-keys compiled)))))))

(deftest malformed-table-test
  (testing "a bare bidi handler leaf (no component) throws instead of compiling to nil"
    (is (thrown? ExceptionInfo
                 (compile-collecting ["" {"/account" :account}]))))
  (testing "a leaf missing its component throws"
    (is (thrown? ExceptionInfo
                 (compile-collecting ["" {"/account" [:account]}]))))
  (testing "a non-pair route throws"
    (is (thrown? ExceptionInfo
                 (compile-collecting [:account 'account-page])))))

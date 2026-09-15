(ns bangmod.http-api.auth-test
  (:require [cljs.test :refer [deftest is testing]]
            [bangmod.http-api.auth :as auth]))

(deftest bearer-injector-test
  (testing "adds the header, keeping whatever headers were there"
    (is (= {:uri "/x" :headers {:x-a "1" :authorization "Bearer t"}}
           (auth/bearer-injector {:uri "/x" :headers {:x-a "1"}} "t"))))
  (testing "works on a request with no headers at all"
    (is (= {:headers {:authorization "Bearer t"}} (auth/bearer-injector {} "t"))))
  (testing "an explicit :authorization header is left alone"
    (is (= {:headers {:authorization "Basic abc"}}
           (auth/bearer-injector {:headers {:authorization "Basic abc"}} "t")))))

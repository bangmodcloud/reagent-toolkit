(ns build
  "Build + release for every module in this repo.

   All modules share one version (see `unified versioning` in the README): a release
   publishes reagent-form, reagent-http-api and reagent-router at the same number, so
   there is never a compatibility matrix to remember.

     clj -T:build jar-all
     clj -T:build install-all
     clj -T:build deploy-all :version '\"0.1.0\"'
     clj -T:build jar :module '\"reagent-router\"'"
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def ^:private group "io.github.bangmod")
(def ^:private repo-url "https://github.com/bangmodcloud/reagent-toolkit")

(def ^:private default-version
  (or (System/getenv "RELEASE_VERSION") "0.1.0-SNAPSHOT"))

(def modules
  [{:id "reagent-form"
    :description "Form state, validation and field arrays for Reagent."}
   {:id "reagent-http-api"
    :description "Declarative HTTP/SSE API client for ClojureScript, with re-frame integration."}
   {:id "reagent-router"
    :description "bidi + pushy routing for Reagent/re-frame single-page apps."}])

(defn- module-by-id [id]
  (or (some #(when (= id (:id %)) %) modules)
      (throw (ex-info (str "Unknown module: " id
                           " (have " (pr-str (mapv :id modules)) ")")
                      {:module id}))))

(defn- ctx
  "Everything the jar/deploy steps need for one module at one version."
  [{:keys [module version]}]
  (let [{:keys [id description]} (module-by-id module)
        version (or version default-version)
        root    (str "modules/" id)]
    {:id         id
     :lib        (symbol group id)
     :version    version
     :description description
     :root       root
     :src-dirs   [(str root "/src")]
     :class-dir  (str "target/" id "/classes")
     :jar-file   (format "target/%s-%s.jar" id version)
     :basis      (b/create-basis {:project (str root "/deps.edn")})}))

(defn- pom-data [{:keys [description]}]
  [[:description description]
   [:url repo-url]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]])

(defn clean
  "Remove all build output."
  [_]
  (b/delete {:path "target"})
  (println "cleaned target/"))

(defn jar
  "Build one module's jar. :module \"form\" [:version \"0.1.0\"]"
  [opts]
  (let [{:keys [lib version class-dir jar-file src-dirs basis] :as c} (ctx opts)]
    (b/delete {:path class-dir})
    (b/write-pom {:class-dir class-dir
                  :lib       lib
                  :version   version
                  :basis     basis
                  :src-dirs  src-dirs
                  :scm       {:url repo-url
                              :connection (str "scm:git:" repo-url ".git")
                              :developerConnection (str "scm:git:" repo-url ".git")
                              :tag (str "v" version)}
                  :pom-data  (pom-data c)})
    ;; A ClojureScript library ships sources, not classes — the consumer's
    ;; compiler reads them straight out of the jar.
    (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (println "built" jar-file)
    (assoc opts :built c)))

(defn install
  "Build and install one module into ~/.m2. :module \"form\""
  [opts]
  (let [{:keys [built]} (jar opts)
        {:keys [lib version class-dir jar-file basis]} built]
    (b/install {:basis basis :lib lib :version version
                :jar-file jar-file :class-dir class-dir})
    (println "installed" lib version)
    opts))

(defn deploy
  "Build and deploy one module to Clojars. :module \"form\" :version \"0.1.0\"

   Needs CLOJARS_USERNAME and CLOJARS_PASSWORD (a deploy token) in the environment."
  [opts]
  (let [{:keys [built]} (jar opts)
        {:keys [lib version class-dir jar-file]} built]
    (dd/deploy {:installer :remote
                :artifact  (b/resolve-path jar-file)
                :pom-file  (b/pom-path {:lib lib :class-dir class-dir})
                :sign-releases? false})
    (println "deployed" lib version)
    opts))

(defn- for-each [f opts]
  (doseq [{:keys [id]} modules]
    (f (assoc opts :module id)))
  opts)

(defn jar-all     "Build every module's jar."          [opts] (for-each jar opts))
(defn install-all "Install every module into ~/.m2."   [opts] (for-each install opts))
(defn deploy-all  "Deploy every module to Clojars."    [opts] (for-each deploy opts))

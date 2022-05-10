(ns kunagi.build.releasing
  (:require
   [clojure.java.io :as io]
   [kunagi.build.core :as kb]
   [kunagi.build.git :as git]))

(defn assert-kunagi-project-path-ready-for-release [project-path]
  (kb/assert! (-> project-path io/as-file .exists)
              "Project exists:" project-path)
  (git/assert-clean project-path)
  (git/push project-path))

(def projects-path "/p")

(defn project-path [sym]
  (str projects-path "/" (name sym)))

(def releases-path "/kunagi-releasing")

(defn release-path [sym]
  (str releases-path "/" (name sym)))

(defn assert-kunagi-project-ready-for-release [sym]
  (let [project-path (project-path sym)]
    (assert-kunagi-project-path-ready-for-release project-path)))

(defn update-kunagi-project-release-repo [sym]
  (let [release-path (release-path sym)]
    (kb/print-task (str "update release repo: " release-path))
    (kb/assert! (-> release-path io/as-file .exists)
                "Release git exists:" release-path)
    (git/pull-ff release-path)))

(defn run-tests [project-path]
  (kb/print-task (str "run tests: " project-path))
  (kb/process {:command-args ["bin/test"]
               :dir project-path}))

(defn build-kunagi-project-release [path]
  (run-tests path)
  ;; (assert-deps-edn-has-no-local-deps!)
  ;; (git-tag-with-version!)
  ;; (bump-version--bugfix!)
  )

(defn release-kunagi-project [sym]
  (assert-kunagi-project-ready-for-release sym)
  (when (update-kunagi-project-release-repo sym)
    (build-kunagi-project-release (releases-path sym))))

(comment
  (release-kunagi-project 'kunagi-build))

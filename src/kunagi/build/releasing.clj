(ns kunagi.build.releasing
  (:require
   [clojure.java.io :as io]
   [kunagi.build.core :as kb]
   [kunagi.build.git :as git]))

(defn assert-kunagi-project-path-ready-for-release [project-path]
  (kb/assert! (-> project-path io/as-file .exists)
              "Project exists:" project-path)
  (git/assert-clean project-path)
  (git/push project-path)
  )

(def projects-path "/p")

(defn project-path [sym]
  (str projects-path "/" (name sym)))

(defn assert-kunagi-project-ready-for-release [sym]
  (let [project-path (project-path sym)]
    (assert-kunagi-project-path-ready-for-release project-path)))

(defn release-kunagi-project [sym]
  (assert-kunagi-project-ready-for-release sym)
  )

(comment
  (release-kunagi-project 'kunagi-build)
  )

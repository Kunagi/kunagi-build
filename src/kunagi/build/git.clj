(ns kunagi.build.git
  (:require
   [kunagi.build.core :as kb]))

(defn assert-clean [project-path]
  (let [{:keys [out]}
        (kb/process {:command-args ["git" "status" "-s"]
                     :dir project-path
                     :out :capture})]
    (when out
      (kb/fail! (str "git directory dirty: " project-path)
                out))
    (kb/print-done "git clean: " project-path)
    ))

(defn push [project-path]
  (kb/process {:command-args ["git" "push"]
               :dir project-path})
  (kb/print-done "git pushed:" project-path)
  )

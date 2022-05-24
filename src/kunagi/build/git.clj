(ns kunagi.build.git
  (:require

   [kunagi.build.core :as kb]
   [clojure.string :as str]))

(defn assert-clean [project-path]
  (let [{:keys [out]}
        (kb/process {:command-args ["git" "status" "-s"]
                     :dir project-path
                     :out :capture})]
    (when out
      (kb/fail! (str "git directory dirty: " project-path)
                out))
    (kb/print-done "git clean: " project-path)))

(defn push [project-path]
  (kb/process {:command-args ["git" "push"]
               :dir project-path})
  (kb/print-done "git pushed:" project-path))

(defn reset [project-path]
  (kb/process {:command-args ["git" "reset" "--hard"]
               :dir project-path})
  (kb/print-done "git reset:" project-path))

(defn pull-ff [project-path]
  (let [{:keys [out]}
        (kb/process {:command-args ["git" "pull" "--ff-only"]
                     :dir project-path
                     :out :capture})
        out (str/trim out)]
    (if (= out "Already up to date.")
      (do
        (kb/print-done "git pull - no changes:" project-path)
        false)
      (do
        (kb/print-done "git pull - with changes:" project-path)
        true))))

(defn sha [local-repo-path]
  (let [result (kb/process {:command-args ["git" "rev-parse" "HEAD"]
                            :dir local-repo-path
                            :out :capture})
        git-sha (str/trim (:out result))]
    (when (str/blank? git-sha) (kb/fail! "Missing Git SHA" result))
    git-sha))

(defn tag-of-head [local-repo-path]
  (let [result (kb/process {:command-args ["git" "tag" "-l" "--contains" "HEAD"]
                            :dir local-repo-path
                            :out :capture})
        tag (str/trim (:out result))]
    tag))

(defn commit [local-repo-path files commit-comment]
  (kb/process {:command-args (into ["git" "commit"
                                    "-m" commit-comment]
                                   files)
               :dir local-repo-path})
  (kb/print-done "git commit" files))

(defn commit-and-push [local-repo-path files commit-comment]
  (commit local-repo-path files commit-comment)
  (push local-repo-path))

(ns kunagi.build.releasing
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [kunagi.build.core :as kb]
   [kunagi.build.git :as git]
   [kunagi.build.deps :as deps]))

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
    (kb/print-task "update release repo")
    (kb/assert! (-> release-path io/as-file .exists)
                "Release git exists:" release-path)
    (git/reset release-path)
    (git/assert-clean release-path)
    (git/pull-ff release-path)))

(defn run-tests [project-path]
  (kb/print-task "run tests")
  (kb/process {:command-args ["bin/test"]
               :dir project-path}))

(def version-edn-file-path "version.edn")
(def latest-version-edn-file-path "latest-version.edn")

(defn version [project-path]
  (let [file (io/as-file (str project-path "/" version-edn-file-path))]
    (if-not (-> file .exists)
      {:major 0 :minor 0 :bugfix 0}
      (-> file slurp edn/read-string))))

(defn version->str [version]
  (str (or (-> version :major) 0)
       "."
       (or (-> version :minor) 0)
       "."
       (or (-> version :bugfix) 0)))

(defn git-tag-with-version [project-path opts]
  (let [version (version project-path)
        git-version-tag (str "v" (version->str version))
        ts (-> (kb/process {:command-args ["date" "-Iminutes"]
                            :out :capture})
               :out)
        ;; ts (-> (java.util.Date.) .toString)
        ;; ts (-> java.time.format.DateTimeFormatter/ISO_LOCAL_DATE_TIME
        ;;        (.format (java.time.LocalDateTime/now)))
        ;; ts (-> ts
        ;;        (.substring 0 (-> ts (.indexOf "."))))
        ]
    (kb/print-task "tag with version")
    (kb/process {:command-args ["git" "tag" git-version-tag]
                 :dir project-path})
    (kb/print-done "Git tag created:" git-version-tag)
    (kb/process {:command-args ["git" "push" "origin" git-version-tag]
                 :dir project-path})
    (kb/print-done "Git tag pushed to origin")
    (let [git-sha (git/sha project-path)]
      (kb/print-done "Git SHA determined:" git-sha)
      (spit (str project-path "/" latest-version-edn-file-path)
            (str (pr-str {:version version
                          :git/tag git-version-tag
                          :git/sha git-sha
                          :timestamp ts})
                 "\n"))
      (kb/print-done "Written" latest-version-edn-file-path)

      (when-let [version-txt-path (-> opts :version-txt-path)]
        (spit (str project-path "/" version-txt-path) (version->str version))
        (kb/print-done version-txt-path "written"))

      (when-let [version-time-path (-> opts :version-time-path)]
        (spit (str project-path "/" version-time-path) (str (System/currentTimeMillis)))
        (kb/print-done version-time-path "written")))))

(defn bump-version--bugfix [project-path opts]
  (kb/print-task "bump-version: bugfix")
  (let [version (-> (version project-path)
                    (update :bugfix inc))]
    (spit (str project-path "/" version-edn-file-path)
          (str (-> version pr-str)
               "\n"))
    (kb/process {:command-args (->> ["git" "add"
                                     version-edn-file-path latest-version-edn-file-path
                                     (-> opts :version-txt-path)
                                     (-> opts :version-time-path)]
                                    (remove nil?))
                 :dir project-path})
    (kb/process {:command-args ["git" "commit" "-m" (str "[version bump] to " (version->str version))]
                 :dir project-path})
    (kb/process {:command-args ["git" "push"]
                 :dir project-path})
    (kb/print-done "Bumped to" (version->str version))))

(defn build-kunagi-project-release [path opts]
  (kb/print-task "build release")
  (run-tests path)
  (kb/print-task "assert no local deps")
  (deps/assert-no-local-deps path)
  (git-tag-with-version path opts)
  (bump-version--bugfix path opts))

(defn update-kunagi-project-after-release [project-path]
  (kb/print-task "update dev project")
  (git/pull-ff project-path))

(defn upgrade-kunagi-project-dep [project-path dep-sym current-sha]
  (let [latest-version (-> (str (release-path dep-sym) "/" latest-version-edn-file-path)
                           slurp
                           edn/read-string)
        latest-sha (-> latest-version :git/sha)
        latest-tag (-> latest-version :git/tag)]
    (if (= current-sha latest-sha)
      (do
        (kb/print-done (str "dependency " (name dep-sym) " up to date: " latest-tag " " latest-sha))
        false)
      (do
        (deps/set-dep project-path dep-sym {:git/sha latest-sha
                                            :git/tag latest-tag})
        (kb/print-done (str "dependency " (name dep-sym) " updated: " latest-tag " " latest-sha))
        true))))

(defn upgrade-kunagi-project-deps [project-path]
  (let [deps (deps/deps project-path)]
    (reduce (fn [acc [sym coord]]
              (if (-> sym namespace (= "io.github.kunagi"))
                (or (upgrade-kunagi-project-dep project-path sym (-> coord :git/sha))
                    acc)
                acc))
            false deps)))

(defn release-kunagi-project [opts]
  (let [sym (-> opts :project)]
    (assert sym)
    (kb/print-ubertask (name sym))
    ;; (java.lang.Thread/sleep 10000)
    (assert-kunagi-project-ready-for-release sym)
    (git/pull-ff (project-path sym))
    (let [files-changed? (update-kunagi-project-release-repo sym)
          deps-upgraded? (upgrade-kunagi-project-deps (release-path sym))]
      (when deps-upgraded?
        (git/commit-and-push (release-path sym) ["deps.edn"] "[deps]"))

      (when (or files-changed?
                deps-upgraded?)
        (build-kunagi-project-release (release-path sym)
                                      opts)
        (update-kunagi-project-after-release (project-path sym))))))

(comment
  (release-kunagi-project 'kunagi-build))

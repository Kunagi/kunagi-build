(ns kunagi.build.api
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.tools.build.api :as b]
   [clojure.data.json :as json]
   [clojure.term.colors :as c]
   [puget.printer :as puget]
   [borkdude.rewrite-edn :as rw-edn]
   [kunagi.build.core :as kb]
   ;;

   [kunagi.build.releasing :as releasing]))

(def print-done kb/print-done)
(def print-task kb/print-task)
(def print-debug kb/print-debug)
(def print-error kb/print-error)
(def fail! kb/fail!)
(def assert! kb/assert!)
(def process kb/process)

;; * version

(def version-edn-file-path "version.edn")
(def latest-version-edn-file-path "latest-version.edn")

(defn version []
  (let [file (io/as-file version-edn-file-path)]
    (if-not (-> file .exists)
      {:major 0 :minor 0 :bugfix 0}
      (-> file slurp edn/read-string))))

(defn version->str [version]
  (str (or (-> version :major) 0)
       "."
       (or (-> version :minor) 0)
       "."
       (or (-> version :bugfix) 0)))

(comment
  (version)
  (-> (version) version->str))

(defn bump-version--bugfix! []
  (print-task "bump-version: bugfix")
  (let [version (-> (version)
                    (update :bugfix inc))]
    (spit version-edn-file-path
          (str (-> version pr-str)
               "\n"))
    (process {:command-args ["git" "add" version-edn-file-path latest-version-edn-file-path]})
    (process {:command-args ["git" "commit" "-m" (str "[version bump] to " (version->str version))]})
    (process {:command-args ["git" "push"]})
    (print-done "Bumped to" (version->str version))))

(comment
  (bump-version--bugfix!))

;; * testing

(defn run-tests []
  (print-task "testing")
  (process {:command-args ["bin/kaocha"]}))

;; * git

(defn assert-git-clean []
  (print-task "assert-git-clean")
  (let [{:keys [out]}
        (process {:command-args ["git" "status" "-s"]
                  :out :capture})]
    (when out
      (fail! "git directory dirty" out))))

(defn git-sha [local-repo-path]
  (let [result (process {:command-args ["git" "rev-parse" "HEAD"]
                         :dir local-repo-path
                         :out :capture})
        git-sha (str/trim (:out result))]
    (when (str/blank? git-sha) (fail! "Missing Git SHA" result))
    git-sha))

(defn git-tag-of-head [local-repo-path]
  (let [result (process {:command-args ["git" "tag" "-l" "--contains" "HEAD"]
                         :dir local-repo-path
                         :out :capture})
        tag (str/trim (:out result))]
    tag))

(defn git-tag-with-version! []
  (let [version (version)
        git-version-tag (str "v" (version->str version))]
    (process {:command-args ["git" "tag" git-version-tag]})
    (print-done "Git tag created:" git-version-tag)
    (process {:command-args ["git" "push" "origin" git-version-tag]})
    (print-done "Git tag pushed to origin")
    (let [git-sha (git-sha nil)]
      (print-done "Git SHA determined:" git-sha)
      (spit latest-version-edn-file-path
            (str (pr-str {:version version
                          :git/tag git-version-tag
                          :git/sha git-sha})
                 "\n"))
      (print-done "Written" latest-version-edn-file-path))))

;; * EDN (with rewrite)

(defn read-edn-file-for-rewrite [path]
  (let [file (io/as-file path)]
    (when (-> file .exists)
      (-> file
          slurp
          rw-edn/parse-string))))

(comment
  (def _deps-file (read-edn-file-for-rewrite "deps.edn"))
  (def _deps (rw-edn/get _deps-file :deps))
  (rw-edn/keys _deps))

;; * deps

(defn deps-edn-deps
  [path-to-deps-edn]
  (when-let [node (read-edn-file-for-rewrite path-to-deps-edn)]
    (when-let [deps-node (rw-edn/get node :deps)]
      (->> (rw-edn/keys deps-node)
           (reduce (fn [m k]
                     (assoc m
                            (rw-edn/sexpr k)
                            (rw-edn/sexpr (rw-edn/get deps-node (rw-edn/sexpr k)))))
                   nil)))))

(defn deps-edn-deps-with-local-root
  [path-to-deps-edn]
  (->> path-to-deps-edn
       deps-edn-deps
       (reduce (fn [acc [k v]]
                 (if (get v :local/root)
                   (conj acc k)
                   acc))
               nil)
       seq))

(comment
  (deps-edn-deps "deps.edn")
  (deps-edn-deps-with-local-root "deps.edn"))

(defn assert-deps-edn-has-no-local-deps!
  ([]
   (assert-deps-edn-has-no-local-deps! "deps.edn"))
  ([path-to-deps-edn]
   (print-task "assert-deps-edn-has-no-local-deps")
   (when-let [deps (deps-edn-deps-with-local-root path-to-deps-edn)]
     (fail! (str/join ", " deps)))))

(defn switch-to-local-deps!
  ([dep-symbols]
   (switch-to-local-deps! "deps.edn" dep-symbols))
  ([path-to-deps-edn dep-symbols]
   (print-task (str "switch-to-local-deps: " path-to-deps-edn))
   (let [node (read-edn-file-for-rewrite path-to-deps-edn)
         deps-node (rw-edn/get node :deps)]
     (doseq [sym dep-symbols]
       (let [coord-node (rw-edn/get deps-node sym)
             coord (rw-edn/sexpr coord-node)
             local-root-value (get coord :local/root)]
         (if local-root-value
           (print-done sym "already" local-root-value)
           (let [local-root-value (str "/p/" (name sym))
                 updated-node (rw-edn/assoc-in node [:deps sym] {:local/root local-root-value})]
             (spit path-to-deps-edn
                   (str updated-node))
             (print-done sym "switched to" local-root-value))))))))

(defn latest-version [local-repo-path]
  (let [latest-version-file (io/as-file (str local-repo-path "/" latest-version-edn-file-path))
        _ (when-not (-> latest-version-file .exists)
            (fail! (str "Missing " latest-version-file)))
        latest-version (read-string (slurp latest-version-file))
        git-sha (get latest-version :git/sha)
        _ (when-not git-sha
            (fail! (str "Missing :git/sha in " latest-version-file)))]
    latest-version))

(defn switch-to-release-deps!
  ([]
   (switch-to-release-deps! "deps.edn"))
  ([path-to-deps-edn]
   (print-task (str "switch-to-release-deps: " path-to-deps-edn))
   (let [node (read-edn-file-for-rewrite path-to-deps-edn)
         deps-with-local-root (deps-edn-deps-with-local-root path-to-deps-edn)]
     (doseq [sym deps-with-local-root]
       (let [dep-path-node (rw-edn/get-in node [:deps sym :local/root])
             dep-path (rw-edn/sexpr dep-path-node)
             latest-version (latest-version dep-path)
             git-tag (get latest-version :git/tag)
             git-sha (get latest-version :git/sha)
             updated-node (rw-edn/assoc-in node [:deps sym] {:git/tag git-tag
                                                             :git/sha git-sha})]
         (spit path-to-deps-edn
               (str updated-node))
         (print-done sym "switched to" sym git-tag))))))

(defn set-deps-edn-dep-to-sha [sym git-sha git-tag]
  (print-task (str "set-deps-edn-dep-to-sha: " sym git-tag))
  (let [node (read-edn-file-for-rewrite "deps.edn")
        current-sha (rw-edn/get-in node [:deps sym :git/sha])]
    (if (= git-sha current-sha)
      (print-done sym "already on" git-tag)
      (let [updated-node (rw-edn/assoc-in node [:deps sym]
                                          {:git/tag git-tag :git/sha git-sha})]
        (spit "deps.edn"
              (str updated-node))
        (print-done sym "switched to" git-tag)))))

;; * JSON

(defn read-json-file [path]
  (let [file (io/as-file path)]
    (when (-> file .exists)
      (when-let [s (slurp path)]
        (json/read-str s :key-fn str)))))

(defn write-json-file [path content]
  (let [s (json/write-str content)]
    (spit path s)
    (let [formated (-> {:command-args ["jq" "." path]
                        :out :capture}
                       process
                       :out)]
      (spit path formated))))

;; ** npm

(defn package-json-add-dependency! [package-name package-version]
  (let [content (read-json-file "package.json")
        existing-version (get-in content ["dependencies" package-name])]
    (when (not= package-version existing-version)
      (write-json-file "package.json"
                       (assoc-in content ["dependencies" package-name] package-version))
      (print-done "Updated dependency:" package-name package-version
                  (str "(was " existing-version ")")))))

(defn npm-reinstall! []
  (print-task "npm-reinstall")
  (b/delete {:path "node_modules"})
  (process {:command-args ["npm" "install"]}))

;; * releasing

(defn prepare-local-dependency! [sym]
  (print-task (str "prepare-local-dependency" sym))
  (let [local-path (str "/p/" (name sym))]

    ;; assert git clean
    (let [{:keys [out]}
          (process {:command-args ["git" "status" "-s"]
                    :out :capture
                    :dir local-path})]
      (when out
        (fail! "git directory dirty" out)))
    (print-done "git is clean")

    ;; git pull
    (process {:command-args ["git" "pull"]
              :dir local-path})
    (print-done "local git repo updated")

    ;; release if has changes
    (let [latest-version (latest-version local-path)
          latest-sha (get latest-version :git/sha)
          local-sha (git-sha local-path)]
      (when (not= latest-sha local-sha)
        (process {:command-args ["bin/release"]
                  :dir local-path})
        (print-done "released")))

    ;; upgrade deps.edn
    ))

(defn release! [{:keys []}]
  (run-tests)
  (assert-git-clean)
  (assert-deps-edn-has-no-local-deps!)
  (git-tag-with-version!)
  (bump-version--bugfix!))

;; (defn trigger-release! [{:keys []}]
;;   (assert-git-clean)
;;   (let [git-tag-of-head (git-tag-of-head ".")
;;         latest-version (-> "version.edn"
;;                            slurp
;;                            edn/read-string
;;                            version->str)]
;;     (if (= git-tag-of-head (str "v" latest-version))
;;       (print-done "already released" git-tag-of-head)
;;       (do
;;         (process {:command-args ["git" "push"]}))))

;;   (assert-deps-edn-has-no-local-deps!)
;;   (run-tests)
;;   (git-tag-with-version!)

;;   (bump-version--bugfix!)
;;   )




;; * releasing

(defn release-2 [{:keys [project]}]
  (releasing/release-kunagi-project project))

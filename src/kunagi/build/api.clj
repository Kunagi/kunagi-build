(ns kunagi.build.api
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.tools.build.api :as b]
   [clojure.data.json :as json]
   [clojure.term.colors :as c]
   [puget.printer :as puget]
   [borkdude.rewrite-edn :as rw-edn]
   ;;

   [clojure.string :as str]))

;; * printing to console

(defn print-done [& ss]
  (print (c/green (c/bold "✓ ")))
  (doseq [s ss]
    (print s)
    (print " "))
  (println))

(defn print-task [task-name]
  (println)
  (println (c/on-blue (c/white (c/bold (str " " task-name " "))))))

(defn print-debug [data]
  (puget/cprint data))

(defn print-error
  ([message]
   (print-error message nil nil))
  ([message data]
   (print-error message data nil))
  ([message data exception]
   (println)
   (print " " (c/on-red (c/white (str " ERROR "))))
   (print " ")
   (println (c/bold message))
   (when data
     ;; (print "    ")
     (if (string? data)
       (println (c/red data))
       (puget/cprint data)))
   (when exception
     (-> exception .printStackTrace))
   (println)))

(defn fail!
  ([message]
   (fail! message nil nil))
  ([message data]
   (fail! message data nil))
  ([message data exception]
   (print-error message data exception)
   (System/exit 1)))

;; * processes

(defn process [process-params]
  (try (let [ret (b/process process-params)]
         (when (-> ret :exit (not= 0))
           (fail! (str "Process exited with error code "
                       (-> ret :exit))
                  process-params
                  nil))
         ret)
       (catch Exception ex
         (fail! "Starting process failed"
                process-params
                ex))))

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

(defn git-tag-with-version! []
  (let [version (version)
        git-version-tag (str "v" (version->str version))]
    (process {:command-args ["git" "tag" git-version-tag]})
    (print-done "Git tag created:" git-version-tag)
    (process {:command-args ["git" "push" "origin" git-version-tag]})
    (print-done "Git tag pushed to origin")
    (let [git-sha (:out (process {:command-args ["git" "rev-parse" "HEAD"]
                                  :output :capture}))]
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
                     (assoc m k (rw-edn/sexpr (rw-edn/get deps-node k))))
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
  (deps-edn-deps-with-local-root "deps.edn"))

(defn assert-deps-edn-has-no-local-deps!
  ([]
   (assert-deps-edn-has-no-local-deps! "deps.edn"))
  ([path-to-deps-edn]
   (print-task "assert-deps-edn-has-no-local-deps")
   (when-let [deps (deps-edn-deps-with-local-root path-to-deps-edn)]
     (fail! (str/join ", " deps)))))

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

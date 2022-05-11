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
   [kunagi.build.deps :as deps]
   ;;

   [kunagi.build.releasing :as releasing]))

(def print-done kb/print-done)
(def print-task kb/print-task)
(def print-debug kb/print-debug)
(def print-error kb/print-error)
(def fail! kb/fail!)
(def assert! kb/assert!)
(def process kb/process)


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

(defn release [opts]
  (kb/print-debug {:opts opts})
  (releasing/release-kunagi-project opts))

(def release-2 release)

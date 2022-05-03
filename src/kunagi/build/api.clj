(ns kunagi.build.api
  (:require
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [clojure.term.colors :as c]
   [puget.printer :as puget]))

;; * printing to console

(defn print-done [& ss]
  (print (c/green (c/bold "  ✓ ")))
  (doseq [s ss]
    (print s)
    (print " "))
  (println))

(defn print-task [task-name]
  (println)
  (println (c/on-blue (c/white (c/bold (str " " task-name " "))))))

(defn print-debug [data]
  (puget/cprint data))

;; * JSON

(defn read-json-file [path]
  (let [file (io/as-file path)]
    (when (-> file .exists)
      (when-let [s (slurp path)]
        (json/read-str s :key-fn str)))))

(defn write-json-file [path content]
  (let [s (json/write-str content)]
    (spit path s)))

;; ** package.json

(defn package-json-add-dependency! [package-name package-version]
  (let [content (read-json-file "package.json")
        existing-version (get-in content ["dependencies" package-name])]
    (when (not= package-version existing-version)
      (write-json-file "package.json"
                       (assoc-in content ["dependencies" package-name] package-version))
      (print-done "Updated dependency:" package-name package-version)
      )))

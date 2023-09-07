(ns kunagi.build.core
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.tools.build.api :as b]
   [clojure.data.json :as json]
   [clojure.term.colors :as c]
   [puget.printer :as puget]
   [borkdude.rewrite-edn :as rw-edn]
   ;;
   ))

;; * printing to console

(defn print-done [& ss]
  (print "[")
  (print (c/green (c/bold "✓")))
  (print "] ")
  (doseq [s ss]
    (print s)
    (print " "))
  (println))

(defn print-task [task-name]
  (println)
  (println (c/on-blue (c/white (c/bold (str " " task-name " "))))))

(defn print-ubertask [task-name]
  (println)
  (println "   " (c/on-green (c/white (c/bold (str " **********     " task-name "     ********** ")))) "   ")
  (println))

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

(defn assert! [assertion & ss]
  (if assertion
    (apply print-done ss)
    (fail! (str "Assertion failed: " (str/join " " ss)))))

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

;; * EDN

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

(ns kunagi.build.deps
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [borkdude.rewrite-edn :as rw-edn]
   [kunagi.build.core :as kb]))

(defn deps
  [project-path]
  (when-let [node (kb/read-edn-file-for-rewrite (str project-path "/deps.edn"))]
    (when-let [deps-node (rw-edn/get node :deps)]
      (->> (rw-edn/keys deps-node)
           (reduce (fn [m k]
                     (assoc m
                            (rw-edn/sexpr k)
                            (rw-edn/sexpr (rw-edn/get deps-node (rw-edn/sexpr k)))))
                   nil)))))

(defn deps-with-local-root
  [project-path]
  (->> project-path
       deps
       (reduce (fn [acc [k v]]
                 (if (get v :local/root)
                   (conj acc k)
                   acc))
               nil)
       seq))

(defn assert-no-local-deps
  [project-path]
  (kb/print-task (str "assert no local deps: " project-path))
   (when-let [deps (deps-with-local-root project-path)]
     (kb/fail! (str/join ", " deps))))

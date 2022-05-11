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
  (when-let [deps (deps-with-local-root project-path)]
    (kb/fail! (str/join ", " deps))))

(defn set-dep [project-path sym new-coord]
  (let [path-to-deps-edn (str project-path "/deps.edn")
        node (kb/read-edn-file-for-rewrite path-to-deps-edn)
        updated-node (rw-edn/assoc-in node [:deps sym] new-coord)]
    (spit path-to-deps-edn
          (str updated-node))
    (kb/print-done "dependency" sym "changed to" new-coord)))


;; (defn switch-to-local-deps!
;;   ([dep-symbols]
;;    (switch-to-local-deps! "deps.edn" dep-symbols))
;;   ([path-to-deps-edn dep-symbols]
;;    (print-task (str "switch-to-local-deps: " path-to-deps-edn))
;;    (let [node (kb/read-edn-file-for-rewrite path-to-deps-edn)
;;          deps-node (rw-edn/get node :deps)]
;;      (doseq [sym dep-symbols]
;;        (let [coord-node (rw-edn/get deps-node sym)
;;              coord (rw-edn/sexpr coord-node)
;;              local-root-value (get coord :local/root)]
;;          (if local-root-value
;;            (print-done sym "already" local-root-value)
;;            (let [local-root-value (str "/p/" (name sym))
;;                  updated-node (rw-edn/assoc-in node [:deps sym] {:local/root local-root-value})]
;;              (spit path-to-deps-edn
;;                    (str updated-node))
;;              (print-done sym "switched to" local-root-value))))))))


;; (defn switch-to-release-deps!
;;   ([]
;;    (switch-to-release-deps! "deps.edn"))
;;   ([path-to-deps-edn]
;;    (kb/print-task (str "switch-to-release-deps: " path-to-deps-edn))
;;    (let [node (kb/read-edn-file-for-rewrite path-to-deps-edn)
;;          deps-with-local-root (deps-edn-deps-with-local-root path-to-deps-edn)]
;;      (doseq [sym deps-with-local-root]
;;        (let [dep-path-node (rw-edn/get-in node [:deps sym :local/root])
;;              dep-path (rw-edn/sexpr dep-path-node)
;;              latest-version (latest-version dep-path)
;;              git-tag (get latest-version :git/tag)
;;              git-sha (get latest-version :git/sha)
;;              updated-node (rw-edn/assoc-in node [:deps sym] {:git/tag git-tag
;;                                                              :git/sha git-sha})]
;;          (spit path-to-deps-edn
;;                (str updated-node))
;;          (print-done sym "switched to" sym git-tag))))))

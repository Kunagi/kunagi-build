(ns build
  (:refer-clojure :exclude [update])
  (:require
   [clojure.tools.build.api :as b]

   [kunagi.build.api :as kb :refer [print-task print-done print-debug]]
   ))

(defn release [{:keys []}]
  (kb/print-debug "test")
  (kb/release {:project 'kunagi-build})
  )

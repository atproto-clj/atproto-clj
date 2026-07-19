(ns statusphere.test-runner
  "Minimal clojure.test runner — keeps the example free of test-tooling deps."
  (:require [clojure.test :as test]))

(def test-namespaces
  '[statusphere.db-test
    statusphere.auth-test
    statusphere.views-test
    statusphere.ingester-test
    statusphere.routes-test])

(defn -main [& _]
  (apply require test-namespaces)
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

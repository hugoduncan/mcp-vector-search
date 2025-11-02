(ns all-private
  "Namespace with only private elements.")

(defn ^:private private-fn-1
  "First private function."
  [x]
  (inc x))

(defn ^:private private-fn-2
  "Second private function."
  [x]
  (dec x))

(def ^:private private-var "private value")

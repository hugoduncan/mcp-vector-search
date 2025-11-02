(ns sample
  "Sample namespace for testing code analysis.")

(defn public-fn
  "A public function."
  [x]
  (inc x))

(defn ^:private private-fn
  "A private function."
  [x]
  (dec x))

(defmacro sample-macro
  "A sample macro."
  [& body]
  `(do ~@body))

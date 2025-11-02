(ns empty-docstring
  "Namespace for testing empty docstring handling.")

(defn fn-with-empty-doc
  ""
  [x]
  (inc x))

(defn fn-with-whitespace-doc
  "   "
  [x]
  (dec x))

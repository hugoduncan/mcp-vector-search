(ns error-file
  "This file has syntax errors")

(defn valid-fn
  "This function is valid"
  [x]
  (inc x))

;; Syntax error: unmatched opening paren
(defn broken-fn [x
  (+ x 1))

;; Syntax error: missing closing brace
(defn another-broken {x y}
  (+ x y)

((clojure-mode
  . (;; modify to allow '+' in a defininf def macro name
     (cider-clojure-cli-aliases . "dev:test")
     ;; (cider-clojure-cli-aliases . "dev:test:dado-local")
     ;; (cider-jack-in-nrepl-middlewares
     ;;  . ("cider.nrepl/cider-middleware"
     ;;     "dado.nrepl-middleware.interface/dado-middleware"))
     (fill-column . 80)
     (whitespace-line-column . 80)
     (eval . (cljstyle-mode 1))
     (eval . (aggressive-indent-mode -1))
     (projectile-project-type . clojure-cli))))

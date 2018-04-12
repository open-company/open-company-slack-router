(ns dev
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as ctnrepl]
            [oc.slack-router.config :as c]
            [oc.slack-router.app :as app]
            [oc.slack-router.components :as components]))

(def system nil)

(defn init
  ([] (init c/slack-router-server-port))
  ([port]
     (alter-var-root #'system (constantly (components/slack-router-system
                                           {:handler-fn app/app
                                            :port port})))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (component/stop s)))))

(defn go

  ([] (go c/slack-router-server-port))
  
  ([port]
  (init port)
  (start)
  (app/echo-config port)
  (println (str "Now serving slack router from the REPL.\n"
                "When you're ready to stop the system, just type: (stop)\n"))
  port))

(defn reset []
  (stop)
  (ctnrepl/refresh :after 'user/go))
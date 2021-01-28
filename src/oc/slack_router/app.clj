(ns oc.slack-router.app
  "Namespace for the web application which serves the REST API."
  (:gen-class)
  (:require
    [oc.lib.sentry.core :as sentry]
    [taoensso.timbre :as timbre]
    [ring.logger.timbre :refer (wrap-with-logger)]
    [liberator.dev :refer (wrap-trace)]
    [ring.middleware.params :refer (wrap-params)]
    [ring.middleware.reload :refer (wrap-reload)]
    [ring.middleware.cors :refer (wrap-cors)]
    [compojure.core :as compojure :refer (GET)]
    [com.stuartsierra.component :as component]
    [oc.lib.api.common :as api-common]
    [oc.slack-router.components :as components]
    [oc.slack-router.api.slack :as slack-api]
    [oc.slack-router.config :as c]))

;; ----- Request Routing -----

(defn routes [sys]
  (compojure/routes
    (GET "/ping" [] {:body "OpenCompany Slack Router Service: OK" :status 200}) ; Up-time monitor
    (GET "/---error-test---" [] (/ 1 0))
    (GET "/---500-test---" [] {:body "Testing bad things." :status 500})
    (slack-api/routes sys)))

;; ----- System Startup -----

(defn echo-config [port]
  (println (str "\n"
    "Running on port: " port "\n"
    "Slack client ID: " c/slack-client-id "\n"
    "Auth Service: " c/auth-server-url "\n"
    "Storage Service: " c/storage-server-url "\n"
    "UI Server: " c/ui-server-url "\n"
    "SNS Topic ARN: " c/aws-sns-slack-topic-arn "\n"
    "Trace: " c/liberator-trace "\n"
    "Hot-reload: " c/hot-reload "\n"
    "Log level: " (name c/log-level) "\n"
    "Sentry: " c/dsn "\n"
    "  env: " c/sentry-env "\n"
    "  debug: " (not c/prod?) "\n"
    (when-not (clojure.string/blank? c/sentry-release)
      (str "  release: " c/sentry-release "\n"))
    (when-not (clojure.string/blank? c/sentry-deploy)
      (str "  deploy: " c/sentry-deploy "\n"))
    "\n"
    (when c/intro? "Ready to serve...\n"))))

;; Ring app definition
(defn app [sys]
  (cond-> (routes sys)
    c/prod?           api-common/wrap-500 ; important that this is first
    c/dsn             (sentry/wrap c/sentry-config) ; important that this is second
    c/prod?           wrap-with-logger
    true              wrap-params
    c/liberator-trace (wrap-trace :header :ui)
    true              (wrap-cors #".*")
    c/hot-reload      wrap-reload))

(defn start
  "Start a development server"
  [port]

  ;; Set log level
  (timbre/merge-config! {:level (keyword c/log-level)})

  ;; Start the system
  (-> {:sentry c/sentry-config
       :handler-fn app
       :port port}
      components/slack-router-system
      component/start)

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"))
    "OpenCompany Slack Router Service\n"))
  (echo-config port))

(defn -main []
  (start c/slack-router-server-port))
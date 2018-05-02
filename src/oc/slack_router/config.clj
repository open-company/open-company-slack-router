(ns oc.slack-router.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

;; ----- System -----

(defonce processors (.availableProcessors (Runtime/getRuntime)))
(defonce core-async-limit (+ 42 (* 2 processors)))

(defonce prod? (= "production" (env :env)))
(defonce intro? (not prod?))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-slack-router) "https://224ba9bc653c4d6ba4894b5faf938fe4@sentry.io/1199370"))

;; ----- Logging (see https://github.com/ptaoussanis/timbre) -----

(defonce log-level (or (env :log-level) :info))

;; ----- HTTP server -----

(defonce hot-reload (bool (or (env :hot-reload) false)))
(defonce auth-server-port (Integer/parseInt (or (env :auth-server-port) "3003")))
(defonce slack-router-server-port (Integer/parseInt (or (env :port) "3009")))
(defonce storage-server-port (Integer/parseInt (or (env :storage-server-port) "3001")))
;; ----- Liberator -----
;; see header response, or http://localhost:3009/x-liberator/requests/ for trace results
(defonce liberator-trace (bool (or (env :liberator-trace) false)))
(defonce pretty? (not prod?)) ; JSON response as pretty?

;; ----- URLs -----
(defonce auth-server-url (or (env :auth-server-url) (str "http://localhost:" auth-server-port)))
(defonce storage-server-url (or (env :storage-server-url) (str "http://localhost:" storage-server-port)))
(defonce slack-router-server-url (or (env :slack-router-server-url) (str "http://localhost:" slack-router-server-port)))
(defonce ui-server-url (or (env :ui-server-url) "http://localhost:3559"))

;; ----- AWS -----
(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

;; ----- AWS SNS -----
(defonce aws-sns-slack-topic-arn (env :aws-sns-slack-topic-arn))
  
;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))

;; ----- Slack -----

(defonce slack-verification-token (env :open-company-slack-verification-token))
(defonce slack-client-id (env :open-company-slack-client-id))
(defonce slack-client-secret (env :open-company-slack-client-secret))

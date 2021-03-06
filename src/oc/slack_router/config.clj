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

;; ----- Logging (see https://github.com/ptaoussanis/timbre) -----

(defonce log-level (if-let [ll (env :log-level)] (keyword ll) :info))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-slack-router) "https://224ba9bc653c4d6ba4894b5faf938fe4@sentry.io/1199370"))
(defonce sentry-release (or (env :release) ""))
(defonce sentry-deploy (or (env :deploy) ""))
(defonce sentry-debug  (boolean (or (bool (env :sentry-debug)) (#{:debug :trace} log-level))))
(defonce sentry-env (or (env :environment) "local"))
(defonce sentry-config {:dsn dsn
                        :release sentry-release
                        :environment sentry-env
                        :deploy sentry-deploy
                        :debug sentry-debug})

;; ----- HTTP server -----

(defonce hot-reload (bool (or (env :hot-reload) false)))
(defonce slack-router-server-port (Integer/parseInt (or (env :port) "3009")))

;; ----- Liberator -----

;; see header response, or http://localhost:3009/x-liberator/requests/ for trace results
(defonce liberator-trace (bool (or (env :liberator-trace) false)))
(defonce pretty? (not prod?)) ; JSON response as pretty?

;; ----- URLs -----

(defonce auth-server-port (Integer/parseInt (or (env :auth-server-port) "3003")))
(defonce auth-server-url (or (env :auth-server-url) (str "http://localhost:" auth-server-port)))
(defonce storage-server-port (Integer/parseInt (or (env :storage-server-port) "3001")))
(defonce storage-server-url (or (env :storage-server-url) (str "http://localhost:" storage-server-port)))
(defonce ui-server-url (or (env :ui-server-url) "http://localhost:3559"))

;; ----- AWS -----

(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue))

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
(defonce slack-event-context-token (env :open-company-slack-event-context-token))

;; ----- Filestack -----

(defonce filestack-api-key (env :filestack-api-key))
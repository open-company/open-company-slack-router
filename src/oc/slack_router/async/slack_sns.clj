(ns oc.slack-router.async.slack-sns
  "
  Async publish of slack events to AWS SNS.
  "
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]
            [amazonica.aws.sns :as sns]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.time :as oc-time]
            [oc.slack-router.config :as config]))

;; ----- core.async -----

(defonce slack-chan (async/chan 10000)) ; buffered channel

(defonce slack-go (atom true))

;; ----- Data schema -----

(def SlackEventTrigger
  "
  "
  {:event-at lib-schema/ISO8601})

;; ----- Event handling -----

(defn- handle-slack-message
  [trigger]
  (timbre/debug "Slack event request of:" trigger "to topic:" config/aws-sns-slack-topic-arn)
  (schema/validate SlackEventTrigger trigger)
  (timbre/info "Sending request to topic:" config/aws-sns-slack-topic-arn)
  (sns/publish
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
     :topic-arn config/aws-sns-slack-topic-arn
     :subject "Slack event."
     :message (json/generate-string trigger {:pretty true}))
  (timbre/info "Request sent to topic:" config/aws-sns-slack-topic-arn))

;; ----- Event loop -----

(defn- slack-sns-loop []
  (reset! slack-go true)
  (timbre/info "Starting slack sns...")
  (async/go (while @slack-go
    (timbre/debug "Slack SNS waiting...")
    (let [message (<! slack-chan)]
      (timbre/debug "Processing message on slack sns channel...")
      (if (:stop message)
        (do (reset! slack-go false) (timbre/info "Slack SNS stopped."))
        (async/thread
          (try
            (handle-slack-message message)
          (catch Exception e
            (timbre/error e)))))))))

;; ----- Event triggering -----

(defn send-trigger! [trigger]
  (if (clojure.string/blank? config/aws-sns-slack-topic-arn)
    (timbre/debug "Skipping an event for:" trigger)
    (do
      (timbre/debug "Triggering an event for:" trigger)
      (>!! slack-chan trigger))))

;; ----- Component start/stop -----

(defn start
  "Start the core.async event loop."
  []
  (when-not (clojure.string/blank? config/aws-sns-slack-topic-arn)
    (slack-sns-loop)))

(defn stop
  "Stop the the core.async event loop."
  []
  (when @slack-go
    (timbre/info "Stopping slack sns...")
    (>!! slack-chan {:stop true})))
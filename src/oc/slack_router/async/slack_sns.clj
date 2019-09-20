(ns oc.slack-router.async.slack-sns
  "
  Async publish of slack events to AWS SNS.
  "
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]
            [amazonica.aws.sns :as sns]
            [oc.lib.slack :as lib-slack]
            [oc.slack-router.config :as config]
            [oc.slack-router.async.usage :as usage]))

;; ----- core.async -----

(defonce slack-chan (async/chan 10000)) ; buffered channel

(defonce slack-go (atom true))

;; ----- Event handling -----

(defn- handle-slack-message
  [trigger]
  (timbre/debug "Slack event request of:" trigger "to topic:" config/aws-sns-slack-topic-arn)
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
  (timbre/info "Starting Slack SNS...")
  (async/go (while @slack-go
    (timbre/debug "Slack SNS waiting...")
    (let [message (<! slack-chan)]
      (timbre/debug "Processing message on Slack SNS channel...")
      (if (:stop message)
        (do (reset! slack-go false) (timbre/info "Slack SNS stopped."))
        (async/thread
          (try
            (handle-slack-message message)
          (catch Exception e
            (timbre/error e)))))))))

;; ----- Event triggering -----

(defn- has-marker-char? [text]
  (and text (re-find (re-pattern (str "^" lib-slack/marker-char)) text)))

(defn- from-us? [event]
  (or (has-marker-char? (get event "text"))
      (and (get event "blocks")
           (= (get event "subtype") "bot_message"))))

(defn send-trigger! [trigger]
  (when-not (from-us? (get trigger "event"))
  
    ;; Is this a DM message to the bot?
    (if (= \D (first (-> trigger (get "event")
                                 (get "channel"))))

      ;; Yes, it's a DM to the bot
      (>!! usage/usage-chan trigger)

      ;; Not a DM to the bot, so broadcast this to SNS listeners if configured to do so
      (if (clojure.string/blank? config/aws-sns-slack-topic-arn)
        (timbre/debug "Skipping an event for:" trigger)
        (do
          (timbre/debug "Triggering an event for:" trigger)
          (>!! slack-chan trigger))))))

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
    (timbre/info "Stopping Slack SNS...")
    (>!! slack-chan {:stop true})))
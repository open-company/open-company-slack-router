(ns oc.slack-router.async.usage
  "
  Send a usage instructions request from a user to the bot.

  Having the bot respond to DM's from a user with usage instructions is required by Slack.
  "
  (:require [clojure.core.async :as async :refer [<! >!!]]
            [clojure.walk :refer (keywordize-keys)]
            [taoensso.timbre :as timbre]
            [amazonica.aws.sqs :as sqs]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.slack-router.config :as config]))

;; ----- Schema -----

(def UsageTrigger
  {:type (schema/enum :usage)
   (lib-schema/o-k :avoid-repetition) (schema/maybe schema/Bool)
   :receiver {
      :type (schema/enum :channel)
      :slack-org-id lib-schema/NonBlankStr
      :id schema/Str}})

;; ----- Usage Request Trigger -----

(defn- ->trigger
  ([slack-org-id channel-id] (->trigger slack-org-id channel-id false))
  ([slack-org-id channel-id avoid-repetition?]
   {:type :usage
    :avoid-repetition avoid-repetition?
    :receiver {
      :type :channel
      :slack-org-id slack-org-id
      :id channel-id}}))

(defn- send-trigger! [trigger]
  (schema/validate UsageTrigger trigger) ; sanity check
  (let [queue config/aws-sqs-bot-queue]
    (timbre/info "Usage request to queue:" queue "for:" (-> trigger :receiver :id))
    (sqs/send-message
      {:access-key config/aws-access-key-id
       :secret-key config/aws-secret-access-key}
       config/aws-sqs-bot-queue
       trigger)
    (timbre/info "Request sent to:" queue "for:" (-> trigger :receiver :id))))

;; ----- core.async -----

(defonce usage-chan (async/chan 10000)) ; buffered channel

(defonce usage-go (atom true))

;; ----- Event handling -----

(defn- handle-message
  [slack-org-id channel-id]
  (timbre/info "Sending usage request to the Bot service...")
  (send-trigger! (->trigger slack-org-id channel-id)))

;; ----- Event loops (incoming from Slack) -----

(defn- usage-loop 
  "core.async consumer to handle incoming Slack messages."
  []
  (reset! usage-go true)
  (timbre/info "Starting usage reply...")
  (async/go (while @usage-go
    (timbre/debug "Slack usage reply waiting...")
    (let [message (<! usage-chan)]
      (try
        (timbre/debug "Processing message on usage channel...")
        (if (:stop message)
          (do (reset! usage-go false) (timbre/info "Slack usage reply stopped."))
          (async/thread
            (let [body (keywordize-keys message)
                  event (:event body)
                  slack-org-id (:team_id body)
                  is-im-message? (and (= (:type event) "message")
                                      (= (:channel_type event) "im"))
                  channel-id (if is-im-message?
                               (:user event)
                               (:channel event))]
              (when (or (= (:type event) "app_home_opened")
                        is-im-message?)
                (handle-message slack-org-id channel-id)))))
        (catch Exception e
          (timbre/error e)))))))

(defn send-usage!
  "Queue a new trigger to send usage message to a Slack channel"
  ([usage-trigger]
   (>!! usage-chan usage-trigger))

  ([slack-org-id channel-id]
   (send-usage! (->trigger slack-org-id channel-id))))

(defn send-usage-no-repetition!
  "Queue a new trigger to send usage message to a Slack channel only if the last usage wasn't sent lately"
  [slack-org-id channel-id]
  (send-usage! (->trigger slack-org-id channel-id true)))

;; ----- Component start/stop -----

(defn start
  "Start the core.async channel consumers for sending bot usage instructions to Slack."
  []
  (usage-loop))

(defn stop
  "Stop the core.async channel consumers for sending bot usage instructions to Slack."
  []
  (when @usage-go
    (timbre/info "Stopping Slack usage reply...")
    (>!! usage-chan {:stop true})))
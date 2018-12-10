(ns oc.slack-router.api.slack
  "Liberator API for Slack callback to slack router service."
  (:require [if-let.core :refer (if-let*)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS POST)]
            [liberator.core :refer (defresource by-method)]
            [cheshire.core :as json]
            [oc.lib.api.common :as api-common]
            [oc.lib.auth :as auth]
            [oc.slack-router.slack-unfurl :as slack-unfurl]
            [oc.slack-router.async.slack-sns :as slack-sns]
            [oc.slack-router.config :as config]))

(defn render-slack-unfurl [token body]
  (let [event (get body "event")
        links (get event "links")
        message_ts (get event "message_ts")
        channel (get event "channel")]
    (doseq [link links]
      ;; Post back to slack with added info
      (slack-unfurl/unfurl token channel link message_ts))
    {:status 200 :body (json/generate-string {})}))

(defn- slack-action-handler
  "
  https://api.slack.com/actions
  
  Handle an action event from Slack.

  The idea here is to do very minimal processing and get a 200 back to Slack as fast as possible as this is a 'fire hose'
  of requests. So minimal logging and minimal handling of the request.
  
  Message events look like:
  
  { 
    'message' {
      'type' 'message',
      'user' 'U06SBTXJR',
      'text' 'test it',
      'client_msg_id' 'f027da72-2800-47ac-93b5-b0208652540e',
      'ts' '1538877805.000100'
    },
    'token' 'aLbD1VFXN31DEgpFIvxu32JV',
    'trigger_id' '450676967892.6895731204.3b1d077d82901bb21e3d18e62d20d594',
    'message_ts' '1538877805.000100',
    'user' {
      'id' 'U06SBTXJR',
      'name' 'sean'
    },
    'action_ts' '1538878700.800208',
    'callback_id' 'post',
    'type' 'message_action',
    'response_url' 'https://hooks.slack.com/app/T06SBMH60/452213600886/6BquVZR07zzRqblaB35yYxgC',
    'channel' {
      'id' 'C10A1P4H2',
      'name' 'bot-testing'
    },
    'team' {
      'id' 'T06SBMH60',
      'domain' 'opencompanyhq'
    }
  }
  "
  [request]
  (if-let* [payload (:payload request)
            callback-id (get payload "callback_id")
            type (get payload "type")]
    (if (or (= type "interactive_message")
            (and (= callback-id "post") (= type "message_action"))
            (and (= callback-id "add_post") (= type "dialog_submission")))
      (slack-sns/send-trigger! payload)
      (timbre/warn "Unknown Slack action:" type callback-id))
    (timbre/error "No proper payload in Slack action."))
  {:status 200})

(defn- slack-event-handler
  "
  Handle a message event from Slack.
  
  The idea here is to do very minimal processing and get a 200 back to Slack as fast as possible as this is a 'fire hose'
  of requests. So minimal logging and minimal handling of the request.
  
  Message events look like:

  {
    'token' 'IxT9ZaxvjqRdKxYtWdTw21Xv',
    'team_id' 'T06SBMH60', 
    'api_app_id' 'A0CHN2UDB',
    'event' {
      'type' 'message',
      'user' 'U06SBTXJR',
      'text' 'Call me back here',
      'thread_ts' '1494262410.072574', 
      'parent_user_id' 'U06SBTXJR', 
      'ts' '1494281750.011785', 
      'channel' 'C10A1P4H2', 
      'event_ts' '1494281750.011785'
    }, 
    'type' 'event_callback', 
    'authed_users' ['U06SBTXJR'], 
    'event_id' 'Ev5B8YSYQ6', 
    'event_time' 1494281750
  }
  "
  [request]
  (let [body (:body request)
        type (get body "type")]

    (cond
     ;; This is a check of the web hook by Slack, echo back the challenge
     (= type "url_verification")
     (let [challenge (get body "challenge")]
       (timbre/info "Slack challenge:" challenge)
       {:type type :challenge challenge}) ; Slack, we're good

     (= type "event_callback")
     (let [event (get body "event")
           event-type (get event "type")]

       (cond
        (= event-type "link_shared")
        ;; Handle the unfurl request
        ;; https://api.slack.com/docs/message-link-unfurling
        (let [slack-users (get body "authed_users")
              slack-team-id (get body "team_id")]
          (doseq [slack-user slack-users]
            (let [user-token (auth/user-token {:slack-user-id slack-user :slack-team-id slack-team-id}
                              config/auth-server-url config/passphrase "Slack Router")]
              (when user-token
                (render-slack-unfurl user-token body)))))
        :default
        (slack-sns/send-trigger! body)))
     :default
     {:status 200})))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource slack-event [params]
  (api-common/anonymous-resource config/passphrase)

  :allowed-methods [:options :get :post]

  :authorized? true

  ;; Media type client accepts
  :available-media-types ["application/json"]
  :handle-not-acceptable (fn [_] (api-common/only-accept 406 "application/json"))

  ;; Slack authorization
  :allowed? (by-method {
    :options (fn [ctx] (api-common/allow-anonymous ctx))
    :get (fn [ctx] (api-common/allow-anonymous ctx))
    :post (fn [ctx]
      (dosync
       (let [body (json/parse-string (slurp (get-in ctx [:request :body])))
             token (get body "token")
             challenge (get body "challenge")]
         ;; Token check
         (if-not (= token config/slack-verification-token)
           ;; Eghads! It might be a Slack impersonator!
           (do
             (timbre/warn "Slack verification token mismatch, request provided:" token)
             [false, {:reason "Slack verification token mismatch."}])
           [true, {:body body :challenge challenge}]))))})

  ;; Responses
  :post! slack-event-handler
  
  :handle-created (fn [ctx]
    (timbre/debug "OK" (or (:type ctx) "") (or (:challenge ctx) ""))
    (when (= (:type ctx) "url_verification")
      (json/generate-string (:challenge ctx)))))

(defresource slack-action [params]
  (api-common/anonymous-resource config/passphrase)

  :allowed-methods [:options :post]

  :authorized? true

  ;; Slack authorization
  :allowed? (by-method {
    :options (fn [ctx] (api-common/allow-anonymous ctx))
    :post (fn [ctx]
      (dosync
       (let [params (get-in ctx [:request :params])
             payload-str (get params "payload")
             payload (json/parse-string payload-str)
             token (or (get payload "token")
                       (get params "token"))] ;; Token is in the params for url verification
         ;; Token check
         (if-not (= token config/slack-verification-token)
           ;; Eghads! It might be a Slack impersonator!
           (do
             (timbre/warn "Slack verification token mismatch, request provided:" token)
             [false, {:reason "Slack verification token mismatch."}])
           [true, {:payload payload}]))))})

  ;; Media type client accepts
  :available-media-types ["application/json"]
  :handle-not-acceptable (fn [_] (api-common/only-accept 406 "application/json"))

  ;; Responses
  :post! slack-action-handler)

;; ----- Routes -----

(defn routes [sys]
  (compojure/routes
   (OPTIONS "/slack-event" {params :params} (slack-event params))
   (POST "/slack-event" [:as request] (slack-event request))
   (GET "/slack-event" {params :params} (slack-event params))
   (OPTIONS "/slack-action" [:as request] (slack-action request))
   (POST "/slack-action" [:as request] (slack-action request))))
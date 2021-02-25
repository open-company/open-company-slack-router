(ns oc.slack-router.api.slack
  "Liberator API for Slack callback to slack router service."
  (:require [if-let.core :refer (if-let*)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS POST)]
            [liberator.core :refer (defresource by-method)]
            [clojure.string :as string]
            [cheshire.core :as json]
            [oc.lib.api.common :as api-common]
            [oc.lib.auth :as auth]
            [oc.lib.slack :as slack-lib]
            [oc.slack-router.slack-unfurl :as slack-unfurl]
            [oc.slack-router.async.slack-sns :as slack-sns]
            [oc.slack-router.config :as config]))

(defn render-slack-unfurl [token body]
  (let [event (:event body)
        links (:links event)
        message_ts (:message_ts event)
        channel (:channel event)]
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
            callback-id (:callback_id payload)
            type (:type payload)]
    (if (or (= type "interactive_message")
            (and (= callback-id "post") (= type "message_action"))
            (and (= callback-id "add_post") (= type "dialog_submission")))
      (slack-sns/send-trigger! payload)
      (timbre/warn "Unknown Slack action:" type callback-id))
    (timbre/error "No proper payload in Slack action."))
  {:status 200})

(defn- handle-unfurl-event [body slack-user]
  (timbre/info "Attempt to get a magic token for Slack user" slack-user)
  (try
    (if-let* [slack-user-id (:user_id slack-user)
              slack-team-id (:team_id slack-user)
              user-token (auth/user-token
                          {:slack-user-id slack-user-id
                           :slack-team-id slack-team-id}
                          config/auth-server-url
                          config/passphrase
                          "Slack Router")
             unfurl-outcome (render-slack-unfurl user-token body)]
      [true unfurl-outcome]
      (let [emessage (format "Missing jwt token for user: %s" slack-user)]
        (timbre/info emessage)
        [false emessage]))
    (catch Exception e
      (timbre/info "Exception during Slack unfurl attempt")
      (timbre/error e)
      [false e])))

(defn- check-unfurl-users [body slack-users]
  (let [unfurl-errors (atom [])
        found? (atom false)]
    (mapv (fn [su]
           (when-not @found?
             (let [[unfurl-outcome unfurl-error] (handle-unfurl-event body su)]
               (swap! unfurl-errors concat [[unfurl-outcome unfurl-error]])
               (when unfurl-outcome
                 (reset! found? true)))))
     slack-users)
    @unfurl-errors))

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
    'authorizations' [{ # Always contains 1 user!
      'enterprise_id' 'E12345',
      'team_id' 'T12345',
      'user_id' 'U12345',
      'is_bot': false
    }],
    'event_context' 'EC12345'
    'event_id' 'Ev5B8YSYQ6', 
    'event_time' 1494281750
  }
  "
  [request]
  (let [body (:body request)
        type (:type body)]

    (cond
     ;; This is a check of the web hook by Slack, echo back the challenge
     (= type "url_verification")
     (let [challenge (:challenge body)]
       (timbre/info "Slack challenge:" challenge)
       {:type type :challenge challenge}) ; Slack, we're good

     (= type "event_callback")
     (let [event (:event body)
           event-type (:type event)]
       (cond
        (= event-type "link_shared")
        ;; Handle the unfurl request
        ;; https://api.slack.com/docs/message-link-unfurling
        (let [authorizations (:authorizations body)
              slack-users (:authed_users body)
              check-users (vec (concat slack-users authorizations))
              event-links (:links event)]
          (timbre/infof "Slack link_shared event, trying to unfurl %d links for domains: %s" (count event-links) (string/join ", " (distinct (map :domain event-links))))
          (timbre/debugf "Links: %s" event-links)
          (let [unfurl-results (check-unfurl-users body check-users)]
            (if (some first unfurl-results)
              ;; At least one unfurl succeeded already, moving on
              []
              (let [event-context (:event_context body)
                    event-context-data (slack-lib/get-event-parties config/slack-event-context-token event-context)
                    authorizations-users (:authorizations event-context-data)
                    other-unfurl-results (check-unfurl-users body (vec authorizations-users))
                    final-results (vec (concat unfurl-results other-unfurl-results))]
                (if-not (some first final-results)
                  (let [errors (mapv (comp str second) final-results)]
                    (throw (ex-info (str "Slack link_shared errors:" (count final-results)) {:errors (json/generate-string errors)})))
                  [])))))
        :else
        (slack-sns/send-trigger! body)))
     :else
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
       (let [body (-> ctx :request :body slurp (json/parse-string true))
             token (:token body "token")
             challenge (:challenge body)]
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
             payload-str (:payload params)
             payload (json/parse-string payload-str true)
             token (or (:token payload)
                       (:token params))] ;; Token is in the params for url verification
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
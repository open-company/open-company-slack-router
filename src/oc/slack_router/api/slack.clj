(ns oc.slack-router.api.slack
  "Liberator API for Slack callback to slack router service."
  (:require [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS POST)]
            [ring.util.response :as response]
            [liberator.core :refer (defresource by-method)]
            [cheshire.core :as json]
            [oc.lib.api.common :as api-common]
            [oc.lib.jwt :as lib-jwt]
            [oc.slack-router.slack-unfurl :as slack-unfurl]
            [oc.slack-router.config :as config]))

(defn render-slack-unfurl [body]
  (let [event (get body "event")
        links (get event "links")]
    (timbre/debug body event)
    (timbre/debug links)
    (doseq [link links]
      ;; Post back to slack with added info
      (slack-unfurl/unfurl link)
      )
    {:status 200 :body (json/generate-string {})}))

(defn- slack-event-handler
  "
  Handle a message event from Slack.
  Idea here is to do very minimal processing and get a 200 back to Slack as fast as possible as this is a 'fire hose'
  of requests. So minimal logging and minimal handling of the request.
  Message events look like:
  {'token' 'IxT9ZaxvjqRdKxYtWdTw21Xv',
   'team_id' 'T06SBMH60', 
   'api_app_id' 'A0CHN2UDB',
   'event' {'type' 'message',
            'user' 'U06SBTXJR',
            'text' 'Call me back here',
            'thread_ts' '1494262410.072574', 
            'parent_user_id' 'U06SBTXJR', 
            'ts' '1494281750.011785', 
            'channel' 'C10A1P4H2', 
            'event_ts' '1494281750.011785'}, 
    'type' 'event_callback', 
    'authed_users' ['U06SBTXJR'], 
    'event_id' 'Ev5B8YSYQ6', 
    'event_time' 1494281750}
  "
  [request]
  (let [body (:body request)
        type (get body "type")]
    (timbre/debug body type)
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
        (render-slack-unfurl body)))
     
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
  ;; Authorization
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
           [true, {:body body :challenge challenge}]))))
    })
  ;; Responses
  :post! (fn [ctx] (slack-event-handler ctx))
  
  :handle-created (fn [ctx]
    (timbre/debug "OK" (:type ctx) (:challenge ctx))
    (when (= (:type ctx) "url_verification")
      (json/generate-string (:challenge ctx)))))

;; ----- Routes -----

(defn routes [sys]
  (compojure/routes
   (OPTIONS "/slack-event" {params :params} (slack-event params))
   (POST "/slack-event" [:as request] (slack-event request))
   (GET "/slack-event" {params :params} (slack-event params))))
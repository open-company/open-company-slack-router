(ns oc.slack-router.api.slack
  "Liberator API for Slack callback to slack router service."
  (:require [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [ring.util.response :as response]
            [liberator.core :refer (defresource)]
            [cheshire.core :as json]
            [oc.lib.api.common :as api-common]
            [oc.lib.slack :as slack-lib]
            [oc.lib.jwt :as lib-jwt]
            [oc.slack-router.config :as config]))

(defn render-slack-unfurl [ctx]
  (json/generate-string {}))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg
;; Handle the unfurl request https://api.slack.com/docs/message-link-unfurling
(defresource slack-unfurl [params]
  (api-common/anonymous-resource config/passphrase)

  :allowed-methods [:options :get]

  :authorized? true
  :allowed? (fn [ctx] (api-common/allow-anonymous ctx))

  ;; Media type client accepts
  :available-media-types ["application/json"]
  :handle-not-acceptable (fn [_] (api-common/only-accept 406 "application/json"))

  ;; Responses
  :handle-ok (fn [ctx] (render-slack-unfurl ctx)))

;; ----- Routes -----

(defn routes [sys]
  (compojure/routes
   (OPTIONS "/slack/unfurl" {params :params} (slack-unfurl params))
   (GET "/slack/unfurl" {params :params} (slack-unfurl params))))
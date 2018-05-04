(ns oc.slack-router.auth
  "Uses a magic token to get a valid user token from the auth service"
  (:require [org.httpkit.client :as http]
            [oc.lib.jwt :as jwt]
            [oc.slack-router.config :as config]))

(defn- magic-token
  [slack-user slack-team-id]
  (jwt/generate {:slack-user-id slack-user
                 :slack-team-id slack-team-id
                 :super-user true
                 :name "Slack Router"
                 :auth-source :services
                 } config/passphrase))

(def request-token-url
  (str config/auth-server-url "/users/refresh/"))

(defn get-options
  [token]
  {:headers {"Content-Type" "application/vnd.open-company.auth.v1+json"
             "Authorization" (str "Bearer " token)}})

(defn user-token [slack-user slack-team-id]
  (:body @(http/get request-token-url (get-options (magic-token slack-user slack-team-id)))))
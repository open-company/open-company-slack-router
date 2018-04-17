(ns oc.slack-router.slack-unfurl
  "Used to find information from the storage service and add that info to the
  slack url."
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [oc.lib.jwt :as jwt]
            [oc.lib.slack :as slack-lib]
            [taoensso.timbre :as timbre]
            [oc.slack-router.config :as config]))

(defn generate-auth-token []
  (jwt/generate {:email "serives@carrot.io"
                 :user-id "51c6-43de-928a",
                 :name "Slack Router"
                 :refresh-url "http://localhost:3003/users/refresh"
                 :last-name "Router"
                 :admin []
                 :avatar-url "/img/ML/happy_face_green.svg"
                 :first-name "Slack"
                 :teams []
                 :auth-source :email
                 } config/passphrase))

(def get-post-options
  {:headers {"Content-Type" "application/vnd.open-company.entry.v1+json"
             "Authorization" (str "Bearer " (generate-auth-token))}})

(defn storage-request-post-url
  [org section uuid]
  (str "http://localhost:3001/orgs/" org "/entries/" uuid))

(defn storage-request-secure-uuid-url
  [org section uuid]
  (str "http://localhost:3001/orgs/" org "/entries/" uuid)) 

(defn get-post-data
  [org section uuid cb]
  (timbre/debug (storage-request-post-url org section uuid))
  (http/get (storage-request-post-url org section uuid) get-post-options
    (fn [{:keys [status headers body error]}]
      (if error
        (timbre/error "Failed, exception is " error)
        (let [parsed-body (json/parse-string body)]
          (timbre/debug "Async HTTP GET: " status)
          (cb parsed-body))))))

(defn update-slack-url
  "posts back to the Slack API" 
  [post-data]
  (timbre/debug post-data)
  (let [content (get post-data "body")]
    (timbre/debug content))
  )

(defn parse-carrot-url [url]
  (let [split-url (clojure.string/split (get url "url") #"/")
        org (nth split-url 3)
        split-count (count split-url)]
    (timbre/debug split-url org split-count)
    (cond
     
     ;;http://carrot.io/carrot/general/post/78ba-40f0-bbb5
     (= 7 split-count)
     {:org org
      :section (nth split-url 4)
      :url-type "post"
      :uuid (nth split-url 6)}
     
     ;;http://carrot.io/carrot/post/78ba-40f0-bbb5
     (= 6 split-count)
     {:org org
      :url-type "secure-uuid"
      :uuid (nth split-url 5)}
     :default nil)))

(defn unfurl
  "given a url in the form {'url' <link> 'domain' <carrot.io>}
   ask if it is a post and if so query storage service for more info."
  [link]
  ;; split url
  (let [parsed-link (parse-carrot-url link)]
    ;; is this a post?
    (when (= "secure-uuid" (:url-type parsed-link))
      ;; ask the storage service for information
      (get-post-data (:org parsed-link)
                     (:section parsed-link)
                     (:uuid parsed-link)
                     (fn [data] (update-slack-url data))))))
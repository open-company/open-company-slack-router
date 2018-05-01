(ns oc.slack-router.slack-unfurl
  "Used to find information from the storage service and add that info to the
  slack url."
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [jsoup.soup :as soup]
            [clj-time.format :as time-format]
            [oc.lib.slack :as slack-lib]
            [oc.lib.jwt :as jwt]
            [oc.slack-router.config :as config]))

(defn- index-of
  "Given a collection and a function return the index that make the function truely."
  [s f]
  (loop [idx 0 items s]
    (cond
      (empty? items) nil
      (f (first items)) idx
      :else (recur (inc idx) (rest items)))))

(def iso-format (time-format/formatters :date-time))
(def date-format (time-format/formatter "MMMM d"))

(defn- post-date [timestamp]
  (let [d (time-format/parse iso-format timestamp)]
    (time-format/unparse date-format d)))

(defn get-post-options
  [token]
  {:headers {"Content-Type" "application/vnd.open-company.entry.v1+json"
             "Authorization" (str "Bearer " token)}})

(defn storage-request-post-url
  [org section uuid]
  (str config/storage-server-url "/orgs/" org "/entries/" uuid))

(defn storage-request-secure-uuid-url
  [org section uuid]
  (str config/storage-server-url "/orgs/" org "/entries/" uuid))

(defn get-post-data
  [org section uuid token cb]
  (http/get (storage-request-post-url org section uuid) (get-post-options token)
    (fn [{:keys [status headers body error]}]
      (if error
        (timbre/error "Failed, exception is " error)
        (let [parsed-body (json/parse-string body)]
          (cb parsed-body))))))

(defn update-slack-url
  "Posts back to the Slack API to add information to one of our urls.
   https://api.slack.com/docs/message-link-unfurling
   Also see open-company-lib
  " 
  [token channel ts url post-data]
  (let [content (.text (soup/parse (get post-data "body")))
        title (.text (soup/parse (get post-data "headline")))
        board-slug (get post-data "board-slug")
        author (get (get post-data "publisher") "name")
        comments-link-idx (index-of
                           (get post-data "links")
                           #(and (= (get % "rel") "comments") (= (get % "method") "GET")))
        comments (get (get post-data "links") comments-link-idx)
        comment-count (get comments "count")
        footer (str "Posted in "
                    board-slug
                    " by "
                    author
                    "  |  "
                    (post-date (get post-data "published-at"))
                    "  |  "
                    comment-count
                    (if (= 1 comment-count)
                      " comment "
                      " comments ")
                    )
        org (get post-data "org-name")
        org-logo (get post-data "org-logo-url")
        url-text (get url "url")
        url-data (json/encode {url-text
                               {
                                :author_name org
                                :author_icon org-logo
                                :title title
                                :title_link url-text
                                :text content
                                :footer footer
                                :attachment_type "default"
                                :color "good" ;; this can be a hex color
                                }})]
    (timbre/debug url-data)
    (timbre/debug
     (slack-lib/unfurl-post-url token channel ts url-data)
     )
    )
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
  [token channel link message_ts]
  ;; split url
  (let [parsed-link (parse-carrot-url link)
        decoded-token (jwt/decode token)
        slack-token (:slack-token (:claims decoded-token))]
    ;; is this a post?
    (when (= "secure-uuid" (:url-type parsed-link))
      ;; ask the storage service for information
      (get-post-data (:org parsed-link)
                     (:section parsed-link)
                     (:uuid parsed-link)
                     token
                     (fn [data]
                       (update-slack-url
                        slack-token
                        channel
                        message_ts
                        link
                        data))))))
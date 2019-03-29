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
            [oc.lib.html :as html]
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

(defn storage-request-org-url
  [org]
  ;; /orgs/:org-slug
  (str config/storage-server-url
       "/orgs/"
       org))

(defn storage-request-section-url
  [org section]
  ;; /orgs/:org-slug/boards/:slug
  (str config/storage-server-url
       "/orgs/"
       org
       "/boards/"
       section))

(defn storage-request-post-url
  [org section uuid]
  (str config/storage-server-url
       "/orgs/"
       org
       "/boards/"
       section
       "/entries/"
       uuid))

(defn storage-request-secure-uuid-url
  [org section uuid]
  (str config/storage-server-url "/orgs/" org "/entries/" uuid))

(defn get-data
  [request-url token cb]
  (let [{:keys [status headers body error] :as resp}
          @(http/get request-url (get-post-options token))]
      (if error
        (timbre/error "Failed, exception is " error)
        (let [parsed-body (json/parse-string body)]
          (cb parsed-body)))))

(defn org-unfurl-data
  [url org-data]
  (let [org-name (get org-data "name")
        org-logo (get org-data "logo-url")
        title (str org-name " on Carrot")
        content "Carrot keeps everyone aligned around what matters most."
        section-count (count (get org-data "boards"))
        footer (str org-name
                    " | "
                    section-count
                    (if (= 1 section-count)
                      " section "
                      " sections "))
        url-text (get url "url")]
  (json/encode {url-text
                {
                 :author_name org-name
                 :author_icon org-logo
                 :title title
                 :title_link url-text
                 :text content
                 :footer footer
                 :attachment_type "default"
                 :color "good" ;; this can be a hex color
                 }})))

(defn section-unfurl-data
  [url section-data]
  (let [org-data (:org-data section-data)
        org-name (get org-data "name")
        org-logo (get org-data "logo-url")
        title (get section-data "name")
        content (str "A section of the " org-name " digest.")
        post-count (count (get section-data "entries"))
        footer (str title
                    " | "
                    post-count
                    (if (= 1 post-count)
                      " post "
                      " posts "))
        url-text (get url "url")]
  (json/encode {url-text
                {
                 :author_name org-name
                 :author_icon org-logo
                 :title title
                 :title_link url-text
                 :text content
                 :footer footer
                 :attachment_type "default"
                 :color "good" ;; this can be a hex color
                 }})))

(defn post-unfurl-data
  [url post-data]
  (let [html-body (get post-data "body")
        content (.text (soup/parse html-body))
        reduced-content (clojure.string/join " " ;; split into words
                           (filter not-empty
                             (take 20 ;; 20 words is the average sentence
                               (clojure.string/split content #" "))))
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
        thumbnail-data (html/first-body-thumbnail html-body)
        thumbnail-url (if thumbnail-data (:thumbnail thumbnail-data) "")]
    (json/encode {url-text
                  {
                   :author_name org
                   :author_icon org-logo
                   :title title
                   :title_link url-text
                   :text (if (< (count reduced-content) (count content))
                              (str reduced-content " ...")
                              content)
                   :thumb_url thumbnail-url
                   :footer footer
                   :attachment_type "default"
                   :color "good" ;; this can be a hex color
                   }})))

(defn update-slack-url
  "Posts back to the Slack API to add information to one of our urls.
   https://api.slack.com/docs/message-link-unfurling
   Also see open-company-lib for slack unfurl function.
  "
  [token channel ts url data]
  (when data
    (let [url-data (cond

                    (and (get data "headline") (get data "body"))
                    (post-unfurl-data url data)

                    (and (get data "slug") (get data "entries"))
                    (section-unfurl-data url data)

                    (and (get data "slug") (get data "boards"))
                    (org-unfurl-data url data)

                    :default
                    false
                    )]
      (timbre/info
       (when url-data (slack-lib/unfurl-post-url token channel ts url-data))))))

(defn parse-carrot-url [url]
  (let [split-url (clojure.string/split (get url "url") #"/")
        split-count (count split-url)
        org (when (> split-count 3) (nth split-url 3))]
    (cond

     ;;http://carrot.io/carrot/general
     (= 4 split-count)
     {:org org
      :url-type "org"}

     ;;http://carrot.io/carrot/general
     (= 5 split-count)
     {:org org
      :section (nth split-url 4)
      :url-type "section"}

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
        slack-token (:slack-token (:claims decoded-token))
        url-type (:url-type parsed-link)
        request-url (cond
                     (= "secure-uuid" url-type)

                     (storage-request-secure-uuid-url
                      (:org parsed-link)
                      (:section parsed-link)
                      (:uuid parsed-link))

                     (= "post" url-type)

                     (storage-request-post-url
                      (:org parsed-link)
                      (:section parsed-link)
                      (:uuid parsed-link))

                     (= "section" url-type)

                     (storage-request-section-url
                      (:org parsed-link)
                      (:section parsed-link))

                     (= "org" url-type)
                     (storage-request-org-url
                      (:org parsed-link))

                     :default
                     nil)]

    (when request-url
      (cond
       (= "org" url-type)
       (get-data request-url
                 token
                 (fn [data]
                   (update-slack-url
                    slack-token
                    channel
                    message_ts
                    link
                    data)))
       (= "section" url-type)
       (get-data (storage-request-org-url (:org parsed-link))
                 token
                 (fn [org-data]
                   (get-data request-url
                             token
                             (fn [data]
                               (when-not (= "private" (get data "access"))
                                 (update-slack-url
                                  slack-token
                                  channel
                                  message_ts
                                  link
                                  (assoc data :org-data org-data)))))))
        :default
        (get-data (storage-request-section-url (:org parsed-link)
                                               (:section parsed-link))
                  token
                  (fn [section-data]
                    (when-not (= "private" (get section-data "access"))
                      (get-data request-url
                                token
                                (fn [data]
                                  (timbre/debug data)
                                  (update-slack-url
                                   slack-token
                                   channel
                                   message_ts
                                   link
                                   (assoc data
                                     "board-slug"
                                     (:section parsed-link))))))))))))
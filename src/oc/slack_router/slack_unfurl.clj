(ns oc.slack-router.slack-unfurl
  "Used to find information from the storage service and add that info to the
  slack url."
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [clojure.string :as string]
            [jsoup.soup :as soup]
            [oc.lib.slack :as slack-lib]
            [oc.lib.auth :as auth-lib]
            [oc.lib.jwt :as jwt]
            [oc.lib.text :as text]
            [oc.lib.html :as html]
            [oc.lib.user :as user-lib]
            [oc.slack-router.resources.private-unfurl :as private-unfurl]
            [oc.slack-router.config :as config]))

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

(defn storage-request-board-url
  [org board]
  ;; /orgs/:org-slug/boards/:slug
  (str config/storage-server-url
       "/orgs/"
       org
       "/boards/"
       board))

(defn storage-request-post-url
  [org board uuid]
  (str config/storage-server-url
       "/orgs/"
       org
       "/boards/"
       board
       "/entries/"
       uuid))

(defn storage-request-secure-uuid-url
  [org _board uuid]
  (str config/storage-server-url "/orgs/" org "/entries/" uuid))

(defn get-data
  [url-type request-url token cb]
  (let [{:keys [body error]}
          @(http/get request-url (get-post-options token))]
      (if error
        (timbre/error (ex-info (format "Failed loading data for unfurl: %s" error) {:url request-url :error error :url-type url-type}))
        (let [parsed-body (json/parse-string body true)]
          (cb parsed-body)))))

(defn org-unfurl-data
  [url org-data]
  (let [org-name (:name org-data)
        org-logo (:logo-url org-data)
        title (str org-name " on Carrot")
        content "Key updates and information nobody should miss."
        board-count (count (:boards org-data))
        footer (str org-name
                    " | "
                    board-count
                    (if (= 1 board-count)
                      " topic "
                      " topics "))
        url-text (:url url)]
  (json/encode {url-text
                {
                 :author_name org-name
                 :author_icon org-logo
                 :title title
                 :title_link url-text
                 :text content
                 :footer footer
                 :attachment_type "default"
                 :color "#E8E8E8"
                 :actions [{:text "View org" :type "button" :url url-text}]
                 }})))

(defn contributions-unfurl-data
  [url org-data]
  (let [org-name (:name org-data)
        org-logo (:logo-url org-data)
        contrib-data (:contributions-data org-data)
        avatar-url (when contrib-data (user-lib/fix-avatar-url config/filestack-api-key (:avatar-url contrib-data)))
        user-name (user-lib/name-for contrib-data)
        title (if contrib-data
                (format "%s on %s" user-name org-name)
                (format "%s on Carrot" org-name))
        content (or (:blurb contrib-data) "Key updates and information nobody should miss.")
        footer (if (:title contrib-data)
                 (format "%s | %s: %s" org-name user-name (:title contrib-data))
                 (format "%s | %s" org-name user-name))
        url-text (:url url)]
    (json/encode {url-text
                  {:author_name org-name
                   :author_icon (or avatar-url org-logo)
                   :title title
                   :title_link url-text
                   :text content
                   :footer footer
                   :attachment_type "default"
                   :color "#E8E8E8"
                   :actions [{:text "View user" :type "button" :url url-text}]}})))

(defn all-posts-unfurl-data
  [url org-data]
  (let [org-name (:name org-data)
        org-logo (:logo-url org-data)
        feed-slug (:feed-slug org-data)
        url-text (:url url)
        feed-name (case feed-slug
                    "home" "Home"
                    "topics" "Explore"
                    "bookmarks" "Bookmarks"
                    "All posts")
        feed-description (case feed-slug
                           "home" "Home is where you'll find updates for the topics you're following."
                           "topics" "Explore is where you can manage the topics your are following."
                           "bookmarks" "Bookmarks is where you can find all the posts you saved."
                           "All posts is a stream of what’s new in Carrot.")]
  (json/encode {url-text
                {
                 :author_name org-name
                 :author_icon org-logo
                 :title feed-name
                 :title_link url-text
                 :text feed-description
                 :attachment_type "default"
                 :color "#E8E8E8"
                 :actions [{:text "View posts" :type "button" :url url-text}]
                 }})))

(defn board-unfurl-data
  [url board-data]
  (let [org-data (:org-data board-data)
        org-name (:name org-data)
        org-logo (:logo-url org-data)
        title (:name board-data)
        content "Key updates and information nobody should miss."
        post-count (count (:entries board-data))
        footer (str title
                    " | "
                    post-count
                    (if (= 1 post-count)
                      " post "
                      " posts "))
        url-text (:url url)]
  (json/encode {url-text
                {
                 :author_name org-name
                 :author_icon org-logo
                 :title title
                 :title_link url-text
                 :text content
                 :footer footer
                 :attachment_type "default"
                 :color "#E8E8E8"
                 :actions [{:text "View topic" :type "button" :url url-text}]
                 }})))

(defn post-unfurl-data
  [url post-data]
  (let [html-body (:body post-data)
        content (.text (soup/parse html-body))
        reduced-content (text/truncated-body content)
        headline (.text (soup/parse (:headline post-data)))
        board-name (:board-name post-data)
        publisher (:publisher post-data)
        author-name (user-lib/name-for publisher)
        author-name-label (str author-name " in " board-name)
        author-avatar (user-lib/fix-avatar-url config/filestack-api-key (:avatar-url publisher))
        url-text (:url url)
        thumbnail-data (html/first-body-thumbnail html-body false)
        thumbnail-url (if thumbnail-data (:thumbnail thumbnail-data) "")]
    (json/encode {url-text
                  {
                   :author_name author-name-label
                   :author_icon author-avatar
                   :title headline
                   :title_link url-text
                   :text reduced-content
                   :thumb_url thumbnail-url
                   :attachment_type "default"
                   :color "#E8E8E8"
                   :actions [{:text "View post" :type "button" :url url-text}]
                   }})))

(defn unfurl-content
  [url data]
  (when data
    (cond

      (and (:headline data) (:body data))
      (post-unfurl-data url data)

      (:all-posts data)
      (all-posts-unfurl-data url data)

      (and (:slug data) (:entries data))
      (board-unfurl-data url data)

      (:contributions data)
      (contributions-unfurl-data url data)

      (and (:slug data) (:boards data))
      (org-unfurl-data url data)

      :else
      false)))

(defn update-slack-url
  "Posts back to the Slack API to add information to one of our urls.
   https://api.slack.com/docs/message-link-unfurling
   Also see open-company-lib for slack unfurl function.
  "
  [token channel ts url-data]
  (let [unfurl-response (when url-data
                          (slack-lib/unfurl-post-url token channel ts url-data))]
    (if unfurl-response
      (do
        (timbre/infof "Slack unfurl did complete, output: %s" unfurl-response)
        unfurl-response)
      (timbre/errorf "Slack unfurl did NOT complete, \nurl-data:\n%s\nunfurl-response:\n%s" url-data unfurl-response))))

(defn parse-carrot-url [url]
  (let [split-url (string/split (:url url) #"/")
        split-count (count split-url)
        org (when (> split-count 3) (nth split-url 3))]
    (cond

     ;;http://carrot.io/carrot/general
     (= 4 split-count)
     {:org org
      :url-type "org"}

     ;;http://carrot.io/carrot/all-posts
     (and (= 5 split-count)
          (= (nth split-url 4) "all-posts"))
     {:org org
      :url-type "all-posts"}

    ;;http://carrot.io/carrot/all-posts
     (and (= 5 split-count)
          (or (= (nth split-url 4) "home")
              (= (nth split-url 4) "topics")
              (= (nth split-url 4) "bookmarks")))
     {:org org
      :url-type "all-posts"
      :feed-slug (nth split-url 4)}

     ;;http://carrot.io/carrot/general
     (= 5 split-count)
     {:org org
      :board (nth split-url 4)
      :url-type "board"}
     

    ;;http://carrot.io/carrot/u/1234-1234-1234
    (and (= 6 split-count)
         (= (nth split-url 4) "u"))
    {:org org
     :contributions-id (nth split-url 5)
     :url-type "contributions"}

     ;;http://carrot.io/carrot/general/post/78ba-40f0-bbb5
     (= 7 split-count)
     {:org org
      :board (nth split-url 4)
      :url-type "post"
      :uuid (nth split-url 6)}

     ;;http://carrot.io/carrot/post/78ba-40f0-bbb5
     (and (= 6 split-count)
          (= (nth split-url 4) "post"))
     {:org org
      :url-type "secure-uuid"
      :uuid (nth split-url 5)}

     :else nil)))

(defn- bot-token-for-org [slack-org-id jwtoken]
  (->> jwtoken
       jwt/decode
       :claims
       :slack-bots
       vals
       flatten
       (some #(when (= (:slack-org-id %) slack-org-id)
                (:token %)))))

(defn- ephemeral-blocks-for-link [link board-data channel slack-user-id]
  (timbre/infof "Will create ephemeral blocks with channel: %s and slack-user-id" channel slack-user-id)
  (json/encode [{:type "section"
                 :text {:type "mrkdwn",
                        :text (format "*Warning, private topic*\nThe link %s belongs to the Carrot topic *%s* which is private :lock:, showing informations about it could leak sensitive informations.\nDo you want to show the link info anyway?" link (:name board-data))}}
                {:type "divider"}
                {:type "actions"
                  :elements [{:type "button"
                              :text {:type "plain_text"
                                    :text "Yes, show link info"
                                    :emoji true}
                              :action_id "confirmed"
                              :value (str link "||" (private-unfurl/channel-id-user-id channel slack-user-id))}
                             {:type "button"
                              :text {:type "plain_text"
                                     :text "No, don't show link info"
                                     :emoji true}
                              :action_id "unfurl_denied"
                              :value (str link "||" (private-unfurl/channel-id-user-id channel slack-user-id))}]}]))

(defn send-unfurl-confirmation-message [token slack-user-id channel link board-data message-ts unfurl-data]
  (let [blocks (ephemeral-blocks-for-link (:url link) board-data channel slack-user-id)
        text "The link in this message belongs to a private Carrot topic. Proceeding might result in a leak of informations. Are yhou sure you want to show informations about this link?"]
    (timbre/debugf "Sending ephemeral confirmation message for unfurl link %s. Slack token %s\nChannel %s\nMessage ts: %s\nText %s\nBlocks %s" (:url link) token channel message-ts text blocks)
    (timbre/infof "Store! unfurl-url %s channel-id %s user-id %s unfurl-data %s" (:url link) channel slack-user-id unfurl-data)
    (private-unfurl/store! (:url link) channel slack-user-id (json/generate-string {:token token
                                                                                    :channel channel
                                                                                    :link link
                                                                                    :message-ts message-ts
                                                                                    :slack-user-id slack-user-id
                                                                                    :unfurl-content unfurl-data}))
    (timbre/debugf "Stored data into dynamodb for link %s with channel %s and user-id %s" (:url link) channel slack-user-id)
    (timbre/tracef "Stored unfurl data: %s" unfurl-data)
    (slack-lib/post-ephemeral-blocks token channel text slack-user-id blocks message-ts)))

(defn unfurl
  "given a url in the form {'url' <link> 'domain' <carrot.io>}
   ask if it is a post and if so query storage service for more info."
  [token slack-token channel link message-ts slack-user-id join-channel?]
  ;; split url
  (let [parsed-link (parse-carrot-url link)
        url-type (:url-type parsed-link)
        request-url (cond
                     (= "secure-uuid" url-type)
                     (storage-request-secure-uuid-url
                      (:org parsed-link)
                      (:board parsed-link)
                      (:uuid parsed-link))

                     (= "post" url-type)
                     (storage-request-post-url
                      (:org parsed-link)
                      (:board parsed-link)
                      (:uuid parsed-link))

                     (= "all-posts" url-type)
                     (storage-request-org-url
                      (:org parsed-link))

                     (= "board" url-type)
                     (storage-request-board-url
                      (:org parsed-link)
                      (:board parsed-link))

                     (= "org" url-type)
                     (storage-request-org-url
                      (:org parsed-link))
                     
                     (= "contributions" url-type)
                     (storage-request-org-url
                      (:org parsed-link))

                     :else
                     nil)]
    (when request-url
      (cond
       (= "org" url-type)
       (get-data url-type request-url token
                 (fn [data]
                   (update-slack-url slack-token channel message-ts (unfurl-content link data))))

       (= "all-posts" url-type)
       (get-data url-type request-url token
                 (fn [org-data]
                   (update-slack-url slack-token channel message-ts (unfurl-content link
                                     (assoc org-data :all-posts true :feed-slug (:feed-slug parsed-link))))))

       (= "board" url-type)
       (get-data url-type (storage-request-org-url (:org parsed-link)) token
                 (fn [org-data]
                   (get-data url-type request-url token
                             (fn [data]
                               (let [data-with-org (assoc data :org-data org-data)
                                     unfurl-payload (unfurl-content link data-with-org)]
                                 (if (= "private" (:access data))
                                   (if-not (= channel "COMPOSER")
                                     (do
                                       (when join-channel?
                                         (timbre/infof "Bot is not in %s channel, will try join now..." channel)
                                         (slack-lib/join-channel slack-token channel))
                                       (timbre/infof "Sending ephemeral message now to user %s for unfurl of private board link %s" slack-user-id (:url link))
                                       (send-unfurl-confirmation-message slack-token slack-user-id channel link data message-ts unfurl-payload))
                                     (timbre/infof "Skip unfurl request for link %s by user %s, message is still being composed" (:url link) slack-user-id))
                                   (update-slack-url slack-token channel message-ts unfurl-payload)))))))

       (= "contributions" url-type)
       (get-data url-type request-url token
                 (fn [org-data]
                   (let [contribs (auth-lib/active-users token config/auth-server-url (:team-id org-data))
                         contributions-data (some #(when (= (:user-id %) (:contributions-id parsed-link)) %) (-> contribs :collection :items))]
                     (update-slack-url slack-token channel message-ts (unfurl-content link
                                       (assoc org-data :contributions true
                                                       :contributions-id (:contributions-id parsed-link)
                                                       :contributions-data contributions-data))))))
        :else
        (get-data url-type (storage-request-board-url (:org parsed-link) (:board parsed-link)) token
                  (fn [board-data]
                    (get-data url-type request-url token
                      (fn [data]
                        (let [data-with-board (assoc data :board-slug (:board parsed-link))
                              unfurl-payload (unfurl-content link data-with-board)]
                          (if (= "private" (:access board-data))
                            (if-not (= channel "COMPOSER")
                              (do
                                (when join-channel?
                                  (timbre/infof "Bot is not in %s channel, will try join now..." channel)
                                  (slack-lib/join-channel slack-token channel))
                                (timbre/infof "Sending ephemeral message now to user %s for unfurl of private board link %s" slack-user-id (:url link))
                                (send-unfurl-confirmation-message slack-token slack-user-id channel link board-data message-ts unfurl-payload))
                              (timbre/infof "Skip unfurl request for link %s by user %s, message is still being composed" (:url link) slack-user-id))
                            (update-slack-url slack-token channel message-ts unfurl-payload)))))))))))

(defn unfurl-links [token team-id channel links message-ts slack-user-id is-bot-user-member]
  (let [slack-token (bot-token-for-org team-id token)
        updated-links (if (not is-bot-user-member)
                        (update links 0 merge {:join-channel? true})
                        links)]
    (doseq [link updated-links]
      (timbre/infof "Unfurl request for %s channel %s team-id %s message-ts %s slack-user-id %s" link channel team-id message-ts slack-user-id)
      (unfurl token slack-token channel link message-ts slack-user-id (:join-channel? link)))))

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
  (let [{:keys [body error]}
          @(http/get request-url (get-post-options token))]
      (if error
        (timbre/error "Failed, exception is " error)
        (let [parsed-body (json/parse-string body true)]
          (cb parsed-body)))))

(defn- vertical-line-color [post-data]
  (if (:must-see post-data)
    "#6187F8"
    "#E8E8E8"))

(defn org-unfurl-data
  [url org-data]
  (let [org-name (:name org-data)
        org-logo (:logo-url org-data)
        title (str org-name " on Carrot")
        content "Key updates and information nobody should miss."
        section-count (count (:boards org-data))
        footer (str org-name
                    " | "
                    section-count
                    (if (= 1 section-count)
                      " section "
                      " sections "))
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
                 :color (vertical-line-color nil) ;; this can be a hex color
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
                   :color (vertical-line-color nil) ;; this can be a hex color
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
                 :color (vertical-line-color nil) ;; this can be a hex color
                 :actions [{:text "View posts" :type "button" :url url-text}]
                 }})))

(defn section-unfurl-data
  [url section-data]
  (let [org-data (:org-data section-data)
        org-name (:name org-data)
        org-logo (:logo-url org-data)
        title (:name section-data)
        content "Key updates and information nobody should miss."
        post-count (count (:entries section-data))
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
                 :color (vertical-line-color nil) ;; this can be a hex color
                 :actions [{:text "View section" :type "button" :url url-text}]
                 }})))

(defn post-unfurl-data
  [url post-data]
  (let [html-body (:body post-data)
        content (.text (soup/parse html-body))
        reduced-content (text/truncated-body content)
        headline (.text (soup/parse (:headline post-data)))
        abstract (some-> (:abstract post-data) soup/parse .text)
        must-see (:must-see post-data)
        title (if must-see
                (str "[Must see] " headline)
                headline)
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
                   :title title
                   :title_link url-text
                   :text (if (string/blank? abstract) reduced-content abstract)
                   :thumb_url thumbnail-url
                   :attachment_type "default"
                   :color (vertical-line-color post-data) ;; this can be a hex color
                   :actions [{:text "View post" :type "button" :url url-text}]
                   }})))

(defn update-slack-url
  "Posts back to the Slack API to add information to one of our urls.
   https://api.slack.com/docs/message-link-unfurling
   Also see open-company-lib for slack unfurl function.
  "
  [token channel ts url data]
  (when data
    (let [url-data (cond

                    (and (:headline data) (:body data))
                    (post-unfurl-data url data)

                    (:all-posts data)
                    (all-posts-unfurl-data url data)

                    (and (:slug data) (:entries data))
                    (section-unfurl-data url data)

                    (:contributions data)
                    (contributions-unfurl-data url data)

                    (and (:slug data) (:boards data))
                    (org-unfurl-data url data)

                    :else
                    false)]
      (timbre/info
       (when url-data (slack-lib/unfurl-post-url token channel ts url-data))))))

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
      :section (nth split-url 4)
      :url-type "section"}
     

    ;;http://carrot.io/carrot/u/1234-1234-1234
    (and (= 6 split-count)
         (= (nth split-url 4) "u"))
    {:org org
     :contributions-id (nth split-url 5)
     :url-type "contributions"}

     ;;http://carrot.io/carrot/general/post/78ba-40f0-bbb5
     (= 7 split-count)
     {:org org
      :section (nth split-url 4)
      :url-type "post"
      :uuid (nth split-url 6)}

     ;;http://carrot.io/carrot/post/78ba-40f0-bbb5
     (and (= 6 split-count)
          (= (nth split-url 4) "post"))
     {:org org
      :url-type "secure-uuid"
      :uuid (nth split-url 5)}

     :else nil)))

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

                     (= "all-posts" url-type)
                     (storage-request-org-url
                      (:org parsed-link))

                     (= "section" url-type)
                     (storage-request-section-url
                      (:org parsed-link)
                      (:section parsed-link))

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
       (get-data request-url token
                 (fn [data]
                   (update-slack-url slack-token channel message_ts link data)))

       (= "all-posts" url-type)
       (get-data request-url token
                 (fn [org-data]
                   (update-slack-url slack-token channel message_ts link
                                     (assoc org-data :all-posts true :feed-slug (:feed-slug parsed-link)))))

       (= "section" url-type)
       (get-data (storage-request-org-url (:org parsed-link)) token
                 (fn [org-data]
                   (get-data request-url token
                             (fn [data]
                               (when-not (= "private" (:access data))
                                 (update-slack-url slack-token channel message_ts link
                                                   (assoc data :org-data org-data)))))))

       (= "contributions" url-type)
       (get-data request-url token
                  (fn [org-data]
                    (let [contribs (auth-lib/active-users token config/auth-server-url (:team-id org-data))
                          contributions-data (some #(when (= (:user-id %) (:contributions-id parsed-link)) %) (-> contribs :collection :items))]
                      (update-slack-url slack-token channel message_ts link
                                        (assoc org-data :contributions true :contributions-id (:contributions-id parsed-link) :contributions-data contributions-data)))))
        :else
        (get-data (storage-request-section-url (:org parsed-link) (:section parsed-link)) token
                  (fn [section-data]
                    (when-not (= "private" (:access section-data))
                      (get-data request-url token
                                (fn [data]
                                  (update-slack-url slack-token channel message_ts link
                                   (assoc data :board-slug (:section parsed-link))))))))))))

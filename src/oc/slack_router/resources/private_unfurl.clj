(ns oc.slack-router.resources.private-unfurl
  (:require [oc.slack-router.config :as c]
            [taoensso.faraday :as far]
            [schema.core :as schema]
            [clojure.set :as clojure.set]
            ;; [taoensso.timbre :as timbre]
            [oc.lib.schema :as lib-schema]
            [oc.lib.dynamo.common :as ttl]))

(def table-name (keyword (str c/dynamodb-table-prefix "_private_unfurl")))

(defn channel-id-user-id [channel-id user-id]
  (str channel-id "-" user-id))

(schema/defn ^:always-validate store!
  [unfurl-url :- lib-schema/NonBlankStr channel-id :- lib-schema/NonBlankStr user-id :- lib-schema/NonBlankStr unfurl-data :- lib-schema/NonBlankStr]
  (far/put-item c/dynamodb-opts table-name {:unfurl_url unfurl-url
                                            :channel_id_user_id (channel-id-user-id channel-id user-id)
                                            :channel_id channel-id
                                            :user_id user-id
                                            :unfurl_data unfurl-data
                                            :ttl (ttl/ttl-epoch 30)}) ;; 30 days
  true)

(schema/defn ^:always-validate retrieve :- (schema/maybe {(schema/optional-key :unfurl-url) schema/Str
                                                          (schema/optional-key :channel-id) schema/Str
                                                          (schema/optional-key :user-id) schema/Str
                                                          (schema/optional-key :unfurl-data) schema/Str})
  ([unfurl-url :- lib-schema/NonBlankStr channel-id :- lib-schema/NonBlankStr user-id :- lib-schema/NonBlankStr]
   (retrieve unfurl-url (channel-id-user-id channel-id user-id)))
  ([unfurl-url :- lib-schema/NonBlankStr ch-id-user-id :- lib-schema/NonBlankStr]
  (-> (far/get-item c/dynamodb-opts table-name {:unfurl_url unfurl-url
                                                :channel_id_user_id ch-id-user-id})
      (clojure.set/rename-keys {:unfurl_url :unfurl-url :channel_id_user_id :channel-id-user-id :channel_id :channel-id :user_id :user-id :unfurl_data :unfurl-data})
      (select-keys [:unfurl-url :channel-id :user-id :unfurl-data]))))


(schema/defn ^:always-validate delete!
  ([unfurl-url :- lib-schema/NonBlankStr channel-id :- lib-schema/NonBlankStr user-id :- lib-schema/NonBlankStr]
   (delete! unfurl-url (channel-id-user-id channel-id user-id)))
  ([unfurl-url :- lib-schema/NonBlankStr ch-id-user-id :- lib-schema/NonBlankStr]
   (far/delete-item c/dynamodb-opts table-name {:unfurl_url unfurl-url
                                                :channel_id_user_id ch-id-user-id})))


(comment

  (require '[oc.slack-router.resources.private-unfurl :as private-unfurl] :reload)

  (far/list-tables c/dynamodb-opts)

  (far/delete-table c/dynamodb-opts private-unfurl/table-name)
  (aprint
   (far/create-table c/dynamodb-opts
                     private-unfurl/table-name
                     [:unfurl_url :s]
                     {:range-keydef [:channel_id_user_id :s]
                      :billing-mode :pay-per-request
                      :block? true})))
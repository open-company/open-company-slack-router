(ns oc.slack-router.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [oc.lib.sentry.core :refer (map->SentryCapturer)]
            [org.httpkit.server :as httpkit]
            [oc.slack-router.async.usage :as usage]
            [oc.slack-router.async.slack-sns :as slack-sns]))

(defrecord HttpKit [options handler server]
  component/Lifecycle
  (start [component]
    (timbre/info "[http] starting...")
    (let [handler (get-in component [:handler :handler] handler)
          server  (httpkit/run-server handler options)]
      (timbre/info "[http] started")
      (assoc component :server server)))
  (stop [component]
    (if-not server
      component
      (do
        (timbre/info "[http] stopping...")
        (server)
        (timbre/info "[http] stopped")
        (assoc component :server nil)))))

(defrecord SlackSNS [slack-sns]
  component/Lifecycle
  (start [component]
    (timbre/info "[slack-sns] starting...")
    (slack-sns/start)
    (timbre/info "[slack-sns] started")
    (assoc component :slack-sns true))
  (stop [component]
    (timbre/info "[slack-sns] stopping...")
    (slack-sns/stop)
    (timbre/info "[slack-sns] stopped")
    (assoc component :slack-sns nil)))

(defrecord UsageReply [usage-reply]
  component/Lifecycle

  (start [component]
    (timbre/info "[usage-reply] starting...")
    (usage/start)
    (timbre/info "[usage-reply] started")
    (assoc component :usage-reply true))

  (stop [{:keys [usage-reply] :as component}]
    (if usage-reply
      (do
        (timbre/info "[usage-reply] stopping...")
        (usage/stop)
        (timbre/info "[usage-reply] stopped")
        (assoc component :usage-reply nil))
      component)))

(defrecord Handler [handler-fn]
  component/Lifecycle
  (start [component]
    (timbre/info "[handler] started")
    (assoc component :handler (handler-fn component)))
  (stop [component]
    (timbre/info "[handler] stopped")
    (assoc component :handler nil)))

(defn slack-router-system [{:keys [port handler-fn sentry]}]
  (component/system-map
   :sentry-capturer (map->SentryCapturer sentry)
   :slack-sns (component/using
                (map->SlackSNS {})
                [:sentry-capturer])
   :usage-reply (component/using
                  (map->UsageReply {})
                  [:sentry-capturer])
   :handler (component/using
             (map->Handler {:handler-fn handler-fn})
             [:sentry-capturer])
   :server  (component/using
             (map->HttpKit {:options {:port port}})
             [:handler])))
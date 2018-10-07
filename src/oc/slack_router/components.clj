(ns oc.slack-router.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [org.httpkit.server :as httpkit]
            [oc.slack-router.async.slack-sns :as slack-sns]
            [oc.slack-router.async.slack-action :as slack-action]))

(defrecord HttpKit [options handler server]
  component/Lifecycle
  (start [component]
    (let [handler (get-in component [:handler :handler] handler)
          server  (httpkit/run-server handler options)]
      (assoc component :server server)))
  (stop [component]
    (if-not server
      component
      (do
        (server)
        (dissoc component :server)))))

(defrecord SlackSNS [slack-sns]
  component/Lifecycle
  (start [component]
    (timbre/info "[slack-sns] starting")
    (slack-sns/start)
    (assoc component :slack-sns true))
  (stop [component]
    (slack-sns/stop)
    (dissoc component :slack-sns)))

(defrecord SlackAction [slack-action]
  component/Lifecycle
  (start [component]
    (timbre/info "[slack-action] starting")
    (slack-action/start)
    (assoc component :slack-action true))
  (stop [component]
    (slack-action/stop)
    (dissoc component :slack-action)))

(defrecord Handler [handler-fn]
  component/Lifecycle
  (start [component]
    (timbre/info "[handler] starting")
    (assoc component :handler (handler-fn component)))
  (stop [component]
    (dissoc component :handler)))

(defn slack-router-system [{:keys [port handler-fn]}]
  (component/system-map
   :slack-sns (component/using
                (map->SlackSNS {})
                [])
   :slack-action (component/using
                    (map->SlackAction {})
                    [])
   :handler (component/using
             (map->Handler {:handler-fn handler-fn})
             [])
   :server  (component/using
             (map->HttpKit {:options {:port port}})
             [:handler])))
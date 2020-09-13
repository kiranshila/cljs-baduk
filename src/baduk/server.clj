(ns baduk.server
  (:require [baduk.logic :as logic]
            ; Web Server
            [org.httpkit.server :as server]
            ; Ring/Router
            [reitit.core :as r]
            [reitit.ring :as ring]
            ; Content-Type Negotiation and Coercion
            [muuntaja.core :as m]
            ; Middlewares
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.session :as session]
            [ring.middleware.session.memory :as session-memory]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.middleware.not-modified :as not-modified]
            ; Websockets
            [taoensso.sente :as sente]
            [taoensso.sente.packers.transit :as sente-transit]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
            ; Hiccup Rendering
            [hiccup.page :as page]
            [hiccup.core :as hiccup]
            ; Core Async
            [clojure.core.async :as a]))

(def games (atom {}))
(def game-sessions (atom {}))
(def all-the-sessions (atom {}))

(defn random-letter []
  (char (+ (rand-int 26) 65)))

(defn gen-game-code []
  (apply str (repeatedly 6 random-letter)))

(defn init-game! [board-size starting-color client-id]
  (let [game-code (gen-game-code)
        state {:board-size board-size
               :next-player-color :black
               :game-history []
               :game-started? true
               :game-mode :play
               :stone-locations {}
               :last-stone-locations nil
               :captured {:white 0
                          :black 0}}
        multiplayer {:color starting-color
                     :game-code game-code
                     :your-turn? (= starting-color :black)}]
    (swap! game-sessions assoc game-code {client-id starting-color})
    (swap! games assoc game-code state)
    {:state state :multiplayer multiplayer}))

;; WS Stuff
(let [packer (sente-transit/get-transit-packer)
      {:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]} (sente/make-channel-socket-server!
                                                          (get-sch-adapter) {:packer packer
                                                                             :user-id-fn (fn [ring-req]
                                                                                           (:client-id ring-req))})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(defmulti -handle-ws-event :id)

(defmethod -handle-ws-event :default [{:as ev-msg :keys [event ?data ring-req]}]
  (let [session (:session ring-req)
        uid (:uid session)]))

(defmethod -handle-ws-event :baduk.core/place-stone [{:as ev-msg :keys [?data ?reply-fn client-id]}]
  (let [{:keys [location game-code color]} ?data
        [x y] location]
    (println "New Stone Event from: " client-id " at location " location " for game-code " game-code)
    (when ?reply-fn
      (swap! games update game-code logic/handle-new-stone x y)
      (if-let [other-user-id (-> (@game-sessions game-code)
                                 (dissoc client-id)
                                 first
                                 first)]
        (chsk-send! other-user-id [::new-state {:state (@games game-code)}]))
      (?reply-fn (@games game-code)))))

(defmethod -handle-ws-event :baduk.core/join-game [{:as ev-msg :keys [?data ?reply-fn client-id]}]
  (let [{:keys [game-code]} ?data]
    (when ?reply-fn
      (when-let [state (@games game-code)]
        (let [sessions (@game-sessions game-code)
              color (logic/other-color (second (first sessions)))]
          (if (= (count sessions) 1)
            (do
              (swap! game-sessions update game-code conj {client-id color})
              (println "Client: " client-id " joining game as " color)
              (?reply-fn {:state state :multiplayer {:color color
                                                     :game-code game-code
                                                     :your-turn? (= color :black)}}))
            (?reply-fn {})))))))

(defmethod -handle-ws-event :baduk.core/new-game [{:as ev-msg :keys [?data ?reply-fn client-id]}]
  (let [{:keys [starting-color board-size]} ?data]
    (when ?reply-fn
      (?reply-fn (init-game! board-size starting-color client-id)))))

(defmethod -handle-ws-event :baduk.core/pass [{:keys [client-id ?data]}]
  (println "Client: " client-id " passes")
  (let [{:keys [game-code]} ?data]
    (swap! games update-in [game-code :next-player-color] logic/other-color)
    (doseq [[client color] (@game-sessions game-code)]
      (chsk-send! client [::new-state {:state (@games game-code)}]))))

(defn handle-ws-event [{:as ev-msg :keys [id ?data event]}]
  (-handle-ws-event ev-msg))

(defonce ws-router (atom nil))
(defn start-router! []
  (reset! ws-router
          (sente/start-server-chsk-router! ch-chsk handle-ws-event)))

;; Backend

(defn index [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (page/html5
          {:lang "en"}
          [:head
           [:meta {:charset "UTF-8"}]
           [:title "Baduk in ClojureScript"]
           (page/include-css "static/style.css")]
          [:body
           (let [csrf-token (:anti-forgery-token req)]
             [:div#sente-csrf-token {:data-csrf-token csrf-token}])
           [:div {:id "app"}]
           (page/include-js "js/main.js")])})

(def app
  (ring/ring-handler
   (ring/router
    [["/" {:get index}]
     ["/chsk" {:get ring-ajax-get-or-ws-handshake
               :post ring-ajax-post}]]
    {:data {:muuntaja m/instance
            :middleware [muuntaja/format-middleware
                         anti-forgery/wrap-anti-forgery
                         not-modified/wrap-not-modified
                         params/wrap-params
                         keyword-params/wrap-keyword-params]}})
   (ring/routes
    (content-type/wrap-content-type (ring/create-resource-handler {:path "/"}))
    (ring/create-default-handler))
   {:middleware [#(session/wrap-session % {:store
                                           (session-memory/memory-store all-the-sessions)})]}))

(defn start [port]
  (println (str "Server running on port " port))
  (server/run-server #'app {:port port, :join? false}))

(defonce server (start 4000))
(start-router!)

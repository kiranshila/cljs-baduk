(ns baduk.server
  (:require [baduk.logic :as logic]
            ; Web Server
            [org.httpkit.server :as server]
            ; Ring/Router
            [reitit.core :as r]
            [reitit.ring :as ring]
            ; Content-Type Negotiation and Coercion
            [reitit.ring.coercion :as rrc]
            [muuntaja.core :as m]
            [reitit.coercion.spec]
            [reitit.coercion :as coercion]
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
            [hiccup.core :as hiccup]))

(defn random-letter []
  (char (+ (rand-int 26) 65)))

(defn gen-game-code []
  (apply str (repeatedly 6 random-letter)))

(def games (atom {}))
(def all-the-sessions (atom {}))

;; WS Stuff
(let [packer :edn
      {:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]} (sente/make-channel-socket-server!
                                                          (get-sch-adapter) {:packer packer})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

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

(defn init-game [board-size starting-color]
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
        multiplayer {:your-color starting-color
                     :game-code game-code}]
    (swap! games assoc game-code state)
    {:state state :multiplayer multiplayer}))

(def app
  (ring/ring-handler
   (ring/router
    [["/" {:get index}]
     ["/new-game"
      {:post {:parameters {:body {:starting-color keyword?
                                  :board-size integer?}}
              :response {200 {:body {:game map?}}}
              :handler (fn [{{{:keys [board-size starting-color]} :body} :parameters}]
                        {:status 200
                         :body {:game (init-game board-size starting-color)}})}}]
     ["/chsk" {:get ring-ajax-get-or-ws-handshake
               :post ring-ajax-post}]]
    {:data {:muuntaja m/instance
            :coercion reitit.coercion.spec/coercion
            :middleware [muuntaja/format-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware
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

(ns baduk.server
  (:require [baduk.logic :as logic]
            [ring.util.response :as resp]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params]
            [ring.middleware.session :as session]
            [ring.middleware.content-type :as content-type]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.spec]
            [reitit.ring :as ring]))

(defn random-letter []
  (char (+ (rand-int 26) 65)))

(defn gen-conn-code []
  (apply str (repeatedly 6 random-letter)))

(def games (atom {}))

(defn init-game [board-size starting-color]
  (let [game-code (gen-conn-code)
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

(def serve-index
  {:get (fn [request]
          (-> (resp/resource-response "index.html" {:root "public"})
              (resp/content-type "text/html")))})

(def fallback-handler
  (-> (fn [request]
        (or (resp/resource-response (:uri request) {:root "public"})
            {:status 404
             :body "Resource not found"}))
      content-type/wrap-content-type))

(def app
  (session/wrap-session
   (ring/ring-handler
    (ring/router
     [["/api"
       ["new-game"
        {:get {:coercion reitit.coercion.spec/coercion
               :parameters {:query {:board-size int?
                                    :starting-color string?}}
               :handler (fn [{{{:keys [board-size starting-color]} :query} :parameters}]
                          {:status 200
                           :body (init-game board-size (keyword starting-color))})}}]]
      ["/"
       serve-index]]
     {:data {:muuntaja m/instance
             :middleware [params/wrap-params
                          muuntaja/format-middleware
                          coercion/coerce-exceptions-middleware
                          coercion/coerce-request-middleware
                          coercion/coerce-response-middleware]}})
    (ring/routes
     fallback-handler))))

(defn start []
  (jetty/run-jetty #'app {:port 4000, :join? false})
  (println "server running in port 4000"))

(start)

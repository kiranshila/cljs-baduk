(ns baduk.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [baduk.stones :as stones]
            [baduk.logic :as logic]
            [clojure.set :as set]
            [cljs.core.async :as a]
            [taoensso.sente :as sente :refer [cb-success?]]
            [taoensso.sente.packers.transit :as sente-transit])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(defonce multiplayer (r/atom nil))

(defonce state (r/atom {:board-size nil
                        :next-player-color :black
                        :game-history []
                        :game-started? nil
                        :game-mode :play
                        :stone-locations {}
                        :last-stone-locations nil
                        :captured {:white 0
                                   :black 0}}))
(def ?csrf-token
  (when-let [el (.getElementById js/document "sente-csrf-token")]
    (.getAttribute el "data-csrf-token")))

;; WS Setup

(def ws-timeout 5000)

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client!
       "/chsk"
       ?csrf-token
       {:type :auto
        :packer (sente-transit/get-transit-packer)})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv)
  (def chsk-send! send-fn)
  (def chsk-state state))

(defmulti push-event-handler first)
(defmethod push-event-handler :baduk.server/new-state
   [[id payload]]
  (let [{new-state :state} payload]
    (reset! state new-state)))

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (println "Unhandled event: %s" event))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (println "Push event from server: " ?data)
  (push-event-handler ?data))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (println "Handshake: %s" ?data)))

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router!
           ch-chsk event-msg-handler)))

;; Game stuff

(def other-game-mode {:play :place
                      :place :play})

(def color-to-hover {:white "url(#white-stone-image)"
                     :black "url(#black-stone-image)"})


(defn request-new-game! []
  (let [board-size (int (.. js/document
                            (getElementById "board-size")
                            -value))
        starting-color (keyword (.. js/document
                                    (getElementById "start-color")
                                    -value))]
    (chsk-send! [::new-game {:board-size board-size
                             :starting-color starting-color}]
                ws-timeout
                (fn [{mp :multiplayer
                      s :state}]
                  (reset! multiplayer mp)
                  (reset! state s)))))

(defn join-game! []
  (let [game-code (.. js/document
                      (getElementById "game-code")
                      -value)]
    (chsk-send! [::join-game {:game-code game-code}]
                ws-timeout
                (fn [reply]
                  (if (:join? reply)
                    (do
                      (reset! state (:state reply))
                      (reset! multiplayer {:game-code game-code
                                           :color :black}))
                    (js/alert "No valid game code"))))))

(defn new-stone-action! [x y]
  (if-let [mp @multiplayer]
    (chsk-send! [::place-stone (merge mp {:location [x y]})]
                ws-timeout
                (fn [new-state]
                  (print new-state)
                  (reset! state new-state)))
    (swap! state logic/handle-new-stone x y)))

;; Frontend

(defn board-svg []
  (let [{color :next-player-color
         size :board-size
         locations :stone-locations} @state]
    [:svg {:viewBox [0 0 (dec size) (dec size)]
           :overflow "visible"
           :style {"--current-hover-icon" (color-to-hover color)}}
                                        ; Enable stone defs
     stones/defs
                                        ; Draw the board lines
     (for [x (range size)]
       [:line {:x1 x :y1 0
               :x2 x :y2 (dec size)
               :style {:stroke "black"
                       :stroke-width 1
                       :vector-effect "non-scaling-stroke"}}])
     (for [y (range size)]
       [:line {:x1 0 :y1 y
               :x2 (dec size) :y2 y
               :style {:stroke "black"
                       :vector-effect "non-scaling-stroke"
                       :stroke-width 1}}])
     (for [x (range size)
           y (range size)]
       [:rect.intersection {:width 1
                            :height 1
                            :x (- x 0.5)
                            :y (- y 0.5)
                            :on-click #(new-stone-action! x y)}])
     (for [[stone color] locations]
       (stones/stone color stone))]))

(defn goban []
  (let [{:keys [game-started? board-size]} @state]
    (when game-started?
      [:div.Goban {:style {:width "800px"
                           :height "800px"
                           :background-image "url(static/woodgrain.jpg)"
                           :position "relative"
                           :box-shadow "4px 4px 10px #000000"}}
       [:div.Grid {:style {:margin "50px"
                           :position "absolute"
                           :width "700px"}}
        [board-svg board-size]]])))

(defn toggle-game-mode []
  (let [{:keys [game-started? game-mode]} @state]
    (when game-started?
      [:div
       (str "Current game mode- " game-mode " ")
       [:input {:type "button"
                :value "Toggle game mode"
                :on-click #(swap! state update :game-mode other-game-mode)}]])))

(defn reset-board! []
  (swap! state merge {:next-player-color :black
                      :game-history []
                      :last-stone-locations nil
                      :stone-locations {}
                      :captured {:white 0 :black 0}}))

(defn reset-game []
  (let [{:keys [game-started?]} @state]
    (when game-started?
      [:div
       "Reset game "
       [:input {:type "button"
                :value "Reset game"
                :on-click reset-board!}]])))

(defn next-player! []
  (swap! state update :next-player-color logic/other-color))

(defn toggle-next-color []
  (let [{:keys [game-mode next-player-color]} @state]
    (when (= game-mode :place)
      [:div
       (str "Toggle stone color- " next-player-color " ")
       [:input {:type "button"
                :value "Toggle"
                :on-click next-player!}]
       [:br]])))

(defn pass []
  (let [{:keys [game-mode game-started?]} @state]
    (when (and (= game-mode :play)
               game-started?)
      [:div
       "Pass turn"
       [:input {:type "button"
                :value "Pass"
                :on-click next-player!}]
       [:br]])))

(defn start-local-game! []
  (swap! state merge {:board-size (int (.. js/document
                                           (getElementById "board-size")
                                           -value))
                      :next-player-color (keyword (.. js/document
                                                      (getElementById "start-color")
                                                      -value))
                      :game-started? true}))

(defn start-game []
  (let [{:keys [game-started?]} @state]
    (when-not game-started?
      [:div
       [:div "Select board mode: "
        [:select {:name "board-mode" :id "board-mode"}
         [:option "Planar"]]]
       [:br]
       [:div "Select board size: "
        [:select {:name "board-size" :id "board-size"}
         [:option {:value 9} "9x9"]
         [:option {:value 13} "13x13"]
         [:option {:value 19} "19x19"]]]
       [:br]
       [:div "Select your starting color: "
        [:select {:name "start-color" :id "start-color"}
         [:option {:value "black"} "Black"]
         [:option {:value "white"} "White"]]]
       [:br]
       [:div
        [:input {:type "button" :value "Request game code" :on-click request-new-game!}]
        " "
        [:input {:type "button" :value "Start local game" :on-click start-local-game!}]]
       [:br]
       [:br]
       [:br]
       [:div "Join game "
        [:input {:type "text" :id "game-code"}]]
       [:input {:type "button" :value "Join" :on-click join-game!}]])))

(defn app []
  [:div
   [start-game]
   [goban]
   [:br]
   (if-let [mp @multiplayer]
     [:h4 "Game Code: " (:game-code mp)]
     [:div
      [:br]
      [toggle-game-mode]
      [:br]
      [toggle-next-color]
      [reset-game]])
   [pass]])

(defn ^:dev/after-load start []
  (.log js/console "Starting app")
  (start-router!)
  (rdom/render [app] (.getElementById js/document "app")))

(defn ^:export main []
  (start))

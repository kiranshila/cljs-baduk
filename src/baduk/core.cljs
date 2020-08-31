(ns baduk.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [baduk.stones :as stones]
            [baduk.logic :as logic]
            [clojure.set :as set]
            [cljs.core.async :refer [<!]]
            [cljs-http.client :as http])
  (:require-macros
   [cljs.core.async.macros :refer [go]]))

(def other-game-mode {:play :place
                      :place :play})

(def color-to-hover {:white "url(#white-stone-image)"
                     :black "url(#black-stone-image)"})

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

(defn request-new-game! []
  (let [board-size (int (.. js/document
                            (getElementById "board-size")
                            -value))
        starting-color (.. js/document
                           (getElementById "start-color")
                           -value)]
    (go (let [body (:body (<! (http/get "/api/new-game"
                                        {:accept "application/edn"
                                         :query-params {"board-size" board-size
                                                        "starting-color" starting-color}})))]
          (reset! multiplayer (:multiplayer body))
          (reset! state (:state body))))))

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
                            :on-click #(swap! state logic/handle-new-stone x y)}])
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
        [:input {:type "text"}]]
       [:input {:type "button" :value "Join" :id "game-code"}]])))

(defn app []
  [:div
   [start-game]
   [goban]
   [:br]
   [:br]
   [toggle-game-mode]
   [:br]
   [toggle-next-color]
   [pass]
   [reset-game]])

(defn ^:dev/after-load start []
  (.log js/console "Starting app")
  (rdom/render [app] (.getElementById js/document "app")))

(defn ^:export main []
  (start))

(ns baduk.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [baduk.stones :as stones]
            [clojure.set :as set]))

(def board-size 9)

(defonce next-player-color (r/atom :black))

(defonce game-history (r/atom []))

(def other-color {:white :black
                  :black :white})

(def other-game-mode {:play :place
                      :place :play})

(def color-to-hover {:white "url(#white-stone-image)"
                     :black "url(#black-stone-image)"})

(defonce game-mode (r/atom :play))

(defonce stone-locations (r/atom {}))

(defonce last-stone-locations (r/atom nil))

(defonce captured (r/atom {:white 0
                           :black 0}))

(defn get-adjacent-locations [[x y]]
  (->> (for [[dx dy] [[-1 0] [0 1] [1 0] [0 -1]]
             :let [x (+ x dx)
                   y (+ y dy)]
             :when (and (<= 0 x (dec board-size))
                        (<= 0 y (dec board-size)))]
         [x y])
       (into #{})))

(defn empty-location? [locations location]
  (not (locations location)))

(defn has-liberty? [locations location]
  (let [adjacents (get-adjacent-locations location)]
    (some (partial empty-location? locations) adjacents)))

(defn neighbors [locations location]
  (let [adjacents (get-adjacent-locations location)
        color (locations location)]
    (into #{} (filter #(= color (locations %))) adjacents)))

(defn get-group [locations location]
  (if (empty-location? locations location)
    #{}
    (loop [to-visit #{location}
           visited #{}]
      (if-let [[location & _] (seq to-visit)]
        (recur (set/union (disj to-visit location)
                          (set/difference (neighbors locations location)
                                          visited))
               (conj visited location))
        visited))))

(defn alive? [locations group]
  (some (partial has-liberty? locations) group))

(defn remove-dead-groups-around [locations location]
  (loop [locations locations
         adjacents (get-adjacent-locations location)]
    (if-let [[adjacent & adjacents] (seq adjacents)]
      (let [group (get-group locations adjacent)]
        (cond
          (empty? group) (recur locations adjacents)
          (alive? locations group) (recur locations adjacents)
          :finally (recur (into {}
                                (filter #(not (contains? group (first %))))
                                locations)
                          adjacents)))
      locations)))

(defn append-history [history color mode location]
  (conj history {{color mode} location}))

(defn handle-new-stone [x y]
  (let [locations @stone-locations
        location [x y]
        current-color @next-player-color
        locations-plus-stone (assoc locations location current-color)]
    (if (= :play @game-mode)
      (let [adjacents (get-adjacent-locations location)]
        (when (empty-location? locations location)
          (let [removed-locations (remove-dead-groups-around locations-plus-stone location)]
            (when (and (not= @last-stone-locations removed-locations)
                       (alive? removed-locations (get-group removed-locations location)))
              (swap! captured update current-color + (- (count locations-plus-stone)
                                                        (count removed-locations)))
              (swap! game-history append-history current-color :play location)
              (reset! stone-locations removed-locations)
              (swap! next-player-color other-color)
              (reset! last-stone-locations locations)))))
      (do
        (swap! game-history append-history current-color :place location)
        (reset! stone-locations locations-plus-stone)))))

(defn board-svg [board-size]
  [:svg {:viewBox [0 0 (dec board-size) (dec board-size)]
         :overflow "visible"
         :style {"--current-hover-icon" (color-to-hover @next-player-color)}}
   ; Enable stone defs
   stones/defs
   ; Draw the board lines
   (for [x (range board-size)]
     [:line {:x1 x :y1 0 :x2 x :y2 (dec board-size) :style {:stroke "black"
                                                            :stroke-width 1
                                                            :vector-effect "non-scaling-stroke"}}])
   (for [y (range board-size)]
     [:line {:x1 0 :y1 y :x2 (dec board-size) :y2 y :style {:stroke "black"
                                                            :vector-effect "non-scaling-stroke"
                                                            :stroke-width 1}}])
   (for [x (range board-size)
         y (range board-size)]
     [:rect.intersection {:width 1
                          :height 1
                          :x (- x 0.5)
                          :y (- y 0.5)
                          :on-click #(handle-new-stone x y)}])
   ; Draw the current board state stones
   (for [[stone color] @stone-locations]
     (stones/stone color stone))])

(defn goban []
  [:div.Goban {:style {:width "800px"
                       :height "800px"
                       :background-image "url(static/woodgrain.jpg)"
                       :position "relative"
                       :box-shadow "4px 4px 10px #000000"}}
   [:div.Grid {:style {:margin "50px"
                       :position "absolute"
                       :width "700px"}}
    [board-svg board-size]]])

(defn toggle-game-mode []
  [:div
   (str "Current game mode- " @game-mode " ")
   [:input {:type "button"
            :value "Toggle game mode"
            :on-click #(swap! game-mode other-game-mode)}]])

(defn reset []
  (reset! next-player-color :black)
  (reset! game-history [])
  (reset! last-stone-locations nil)
  (reset! stone-locations {})
  (reset! captured {:white 0 :black 0}))

(defn reset-game []
  [:div
   "Reset game "
   [:input {:type "button"
            :value "Reset game"
            :on-click #(reset)}]])

(defn toggle-next-color []
  (when (= @game-mode :place)
    [:div
     (str "Toggle stone color- " @next-player-color " ")
     [:input {:type "button"
              :value "Toggle"
              :on-click #(swap! next-player-color other-color)}]
     [:br]]))

(defn pass []
  (when (= @game-mode :play)
    [:div
     "Pass turn"
     [:input {:type "button"
              :value "Pass"
              :on-click #(swap! next-player-color other-color)}]
     [:br]]))

(defn app []
  [:div
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

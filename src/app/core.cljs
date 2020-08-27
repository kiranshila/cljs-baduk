(ns app.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [app.stones :as stones]
            [clojure.set :as set]))

(def board-size
9)

(def next-player-color (r/atom :black))

(def other-color {:white :black
                  :black :white})

(def color-to-hover {:white "url(#white-stone-image)"
                     :black "url(#black-stone-image)"})

(def stone-locations (r/atom {:white #{}
                              :black #{}}))

(def last-stone-locations (r/atom nil))

(defn get-adjacent-locations [[x y]]
  (->> (for [[dx dy] [[-1 0] [0 1] [1 0] [0 -1]]
             :let [x (+ x dx)
                   y (+ y dy)]
             :when (and (<= 0 x (dec board-size))
                        (<= 0 y (dec board-size)))]
         [x y])
       (into #{})))

(defn empty-location? [locations location]
  (not (or (contains? (:white locations) location)
           (contains? (:black locations) location))))

(defn has-liberty? [locations location]
  (let [adjacents (get-adjacent-locations location)]
    (some (partial empty-location? locations) adjacents)))

(defn neighbors [locations location]
  (let [adjacents (get-adjacent-locations location)]
    (if (contains? (:white locations) location)
      (set/intersection adjacents (:white locations))
      (set/intersection adjacents (:black locations)))))

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
          :finally (-> locations
                       (update :white set/difference group)
                       (update :black set/difference group)
                       (recur adjacents))))
      locations)))

(defn handle-new-stone [x y]
  (let [locations @stone-locations
        location [x y]
        new-locations (update locations @next-player-color conj location)
        adjacents (get-adjacent-locations location)]
    (when (empty-location? locations location)
      (let [new-locations (remove-dead-groups-around new-locations location)]
        (when (and (not= @last-stone-locations new-locations)
                   (alive? new-locations (get-group new-locations location)))
          (reset! stone-locations new-locations)
          (swap! next-player-color other-color)
          (reset! last-stone-locations locations))))))

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
   (for [[color stones] @stone-locations
         stone stones]
     (stones/stone color stone))])

(defn goban []
  [:div.Goban {:style {:width "800px"
                       :height "800px"
                       :background-image "url(static/woodgrain.jpg)"
                       :position "relative"
                       :box-shadow "4px 4px 10px #000000"}}
   [:div.Grid {:style {:margin "50px"
                       :position "absolute"}}
    [board-svg board-size]]])

(defn app []
  (goban))

(defn ^:dev/after-load start []
  (.log js/console "Starting app")
  (rdom/render [app] (.getElementById js/document "app")))

(defn ^:export main []
  (start))

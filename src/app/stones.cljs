(ns app.stones)


(def color-to-url {:black "url(#black-stone-gradient)"
                   :white "url(#white-stone-gradient)"})

(def radius 0.48)

(defn stone [color [cx cy]]
  [:g
   [:circle {:cx cx
             :cy cy
             :r radius
             :fill "#FFFFFF"
             :stroke "#000"
             :stroke-width 0}]
   [:circle {:cx cx
             :cy cy
             :r radius
             :fill (color-to-url color)
             :fill-opacity 1
             :opacity 1
             :stroke "#000"
             :stroke-width 0.01
             :stroke-opacity 0.3
             :style {:fill-opacity 1
                     :opacity 1
                     :stoke-opacity 0.3}}]])

(def defs
  [:defs
   [:radialGradient {:id "black-stone-gradient"
                     :fx 0.75
                     :fy 0.75}
    [:stop {:offset "0%"
            :stop-color "#A0A0A0"}]
    [:stop {:offset "100%"
            :stop-color "#000000"
            :stop-opacity 0.9}]]
   [:radialGradient {:id "white-stone-gradient"
                     :fx 0.75
                     :fy 0.75}
    [:stop {:offset "0%"
            :stop-color "#FFFFFF"}]
    [:stop {:offset "100%"
            :stop-color "#A0A0A0"
            :stop-opacity 0.9}]]
   [:pattern {:id "black-stone-image"
              :x 0
              :y 0
              :height 1
              :width 1}
    (stone :black [0.5 0.5])]
   [:pattern {:id "white-stone-image"
              :x 0
              :y 0
              :height 1
              :width 1}
    (stone :white [0.5 0.5])]])

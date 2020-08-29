(ns baduk.logic
  (:require [clojure.set :as set]))

(def other-color {:white :black
                  :black :white})

(defn get-adjacent-locations [state [x y]]
  (let [size (:board-size state)]
    (->> (for [[dx dy] [[-1 0] [0 1] [1 0] [0 -1]]
               :let [x (+ x dx)
                     y (+ y dy)]
               :when (and (<= 0 x (dec size))
                          (<= 0 y (dec size)))]
           [x y])
         (into #{}))))

(defn empty-location? [state location]
  (not ((:stone-locations state) location)))

(defn has-liberty? [state location]
  (some (partial empty-location? state)
        (get-adjacent-locations state location)))

(defn neighbors [state location]
  (let [adjacents (get-adjacent-locations state location)
        locations (:stone-locations state)]
    (into #{} (filter #(= (locations location)
                          (locations %))) adjacents)))

(defn get-group [state location]
  (if (empty-location? state location)
    #{}
    (loop [to-visit #{location}
           visited #{}]
      (if-let [[location & _] (seq to-visit)]
        (recur (set/union (disj to-visit location)
                          (set/difference (neighbors state location)
                                          visited))
               (conj visited location))
        visited))))

(defn alive? [state group]
  (some (partial has-liberty? state) group))

(defn remove-dead-groups-around [state location]
  (loop [locations (:stone-locations state)
         adjacents (get-adjacent-locations state location)]
    (if-let [[adjacent & adjacents] (seq adjacents)]
      (let [inter-state (assoc state :stone-locations locations)
            group (get-group inter-state adjacent)]
        (if (or (empty? group)
                (alive? inter-state group)
                (contains? group location))
          (recur locations adjacents)
          (recur (into {}
                       (filter #(not (contains? group (first %))))
                       locations)
                 adjacents)))
      locations)))

(defn append-history [history color mode location]
  (conj history {{color mode} location}))

(defn handle-new-stone [state x y]
  (let [locations (:stone-locations state)
        last-locations (:last-stone-locations state)
        location [x y]
        current-color (:next-player-color state)
        locations-plus-stone (assoc locations location current-color)
        state-plus-stone (assoc state :stone-locations locations-plus-stone)]
    (if (= :play (:game-mode state))
      (let [adjacents (get-adjacent-locations state location)]
        (if (empty-location? state location)
          (let [removed-locations (remove-dead-groups-around state-plus-stone location)
                removed-state (assoc state :stone-locations removed-locations)]
            (if (and (not= last-locations removed-locations)
                       (alive? removed-state (get-group removed-state location)))
              (-> removed-state
                  (update-in [:captured current-color] + (- (count locations-plus-stone)
                                                            (count removed-locations)))
                  (update :game-history append-history current-color :play location)
                  (update :next-player-color other-color)
                  (assoc :last-stone-locations locations))
              state))
          state))
      (-> state
          (update :game-history append-history current-color :place location)
          (assoc :stone-locations locations-plus-stone)))))

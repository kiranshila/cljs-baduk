(ns baduk.sgf)

(def sgf-start-char 97)

(defn sgf-position [[x y]]
  (str (char (+ sgf-start-char x))
       (char (+ sgf-start-char y))))

(def sgf-header "")

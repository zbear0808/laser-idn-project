(ns laser-show.views.components.zone-chips
  "Small shared parts of the zone pickers and the two group tabs: the
   groups a Zone Selector offers, the lock on All and the automatic groups,
   and the marks that say how a laser is in a group."
  (:require [clj-font-awesome.core :as fa]
            [clj-font-awesome.fonts :as fa-fonts]))


(defn locked?
  "Is a target group (subs/target-groups) built in: All, an automatic group
   or a laser's own? Those can't be renamed, deleted or ticked."
  [group]
  (contains? #{:all :auto :laser} (:kind group)))

(defn lock-graphic
  "A small lock for a built-in group's list cell or menu item, else nil. A
   plain Text node, not a label, so the cell's own text stays the one a
   lookup of .text finds."
  [group]
  (when (locked? group)
    {:fx/type :text
     :text (fa/unicode! :lock :solid)
     :font (fa-fonts/font :solid 9)
     :fill "#808080"}))

(defn with-lock
  "desc (a list cell's or menu item's props) with the lock as its :graphic
   when group is built in. A nil :graphic is never set: a menu item can't
   take one."
  [desc group]
  (if-let [lock (lock-graphic group)]
    (assoc desc :graphic lock)
    desc))

(def ^:private mark-glyphs
  {:whole "■" :left-half "◧" :right-half "◨" :member "■" :not-member "□" :excluded "×" :off "–" :not-in "·"})

(defn member-mark
  "How a laser is in a group, as both tabs show it (subs/rig-matrix): ■ whole
   (or a member of your group), ◧ its left half, ◨ its right half, □ not in
   your group, × excluded from All, – off, · not in an automatic group."
  [mark]
  (get mark-glyphs mark ""))


(defn zone-options
  "The zone groups a zone picker offers (the Zone Selector, its keyframes):
   the groups as they are, with All first, listed once. A show whose groups
   have no :all still offers it."
  [zone-groups]
  (let [all (or (some #(when (= :all (:id %)) %) zone-groups)
                {:id :all :name "All" :color "#888888"})]
    (into [all] (remove #(= :all (:id %))) zone-groups)))

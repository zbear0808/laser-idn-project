(ns laser-show.ui-test.keys-test
  "laser-show.views.keys: what counts as typing, and focus inside a node
   (keyboard plan 03 §4). It builds real controls, so it needs the toolkit,
   but no app."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.keys :as keys])
  (:import [javafx.scene Node Scene]
           [javafx.scene.control Button CheckBox ComboBox Spinner TextArea TextField]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.layout HBox VBox]
           [javafx.stage Stage]))

(use-fixtures :once fixtures/with-toolkit)

(defn- key-on
  "A KEY_PRESSED event for Delete with target as its target."
  ^KeyEvent [target]
  (KeyEvent. nil target KeyEvent/KEY_PRESSED "" "" KeyCode/DELETE false false false false))

(defn- typing? [make]
  (ui/on-fx-thread #(keys/typing-target? (key-on (make)))))

(deftest typing-target-detects-text-inputs-test
  (testing "text inputs, and editable combos and spinners, take the keys"
    (doseq [[what make] [["a text field" #(TextField.)]
                         ["a text area" #(TextArea.)]
                         ["an editable combo" #(doto (ComboBox.) (.setEditable true))]
                         ["an editable spinner" #(doto (Spinner. 0 10 1) (.setEditable true))]
                         ["an editable combo's editor" #(.getEditor (doto (ComboBox.) (.setEditable true)))]
                         ["an editable spinner's editor" #(.getEditor (doto (Spinner. 0 10 1) (.setEditable true)))]]]
      (is (typing? make) what)))
  (testing "buttons, boxes, a combo or spinner you can't type in, and panes don't"
    (doseq [[what make] [["a button" #(Button.)]
                         ["a check box" #(CheckBox.)]
                         ["a combo" #(ComboBox.)]
                         ["a spinner" #(Spinner. 0 10 1)]
                         ["a pane" #(VBox.)]
                         ["no target" (constantly nil)]]]
      (is (not (typing? make)) what))))

(deftest focus-within-is-true-for-descendants-test
  (let [nodes (ui/on-fx-thread
               (fn []
                 (let [button (Button. "b")
                       field (TextField.)
                       list-node (doto (VBox. (into-array Node [(HBox. (into-array Node [button field]))]))
                                   (.setFocusTraversable true))
                       other (Button. "other")
                       stage (doto (Stage.)
                               (.setScene (Scene. (VBox. (into-array Node [list-node other])) 200 200))
                               (.show)
                               (.requestFocus))]
                   {:stage stage :button button :field field :list-node list-node :other other})))
        {:keys [stage list-node]} nodes
        focus! (fn [k]
                 (let [^Node n (get nodes k)]
                   (ui/on-fx-thread #(.requestFocus n))
                   (ui/wait-for! #(ui/on-fx-thread (fn [] (.isFocused n))))))
        within? #(ui/on-fx-thread (fn [] (keys/focus-within? list-node)))
        list-target? (fn [k] (ui/on-fx-thread #(keys/list-key-target? list-node (key-on (get nodes k)))))]
    (try
      (focus! :button)
      (is (within?) "a button inside")
      (is (not (ui/on-fx-thread #(.isFocused ^Node list-node))) "which isFocused doesn't see")
      (is (list-target? :button))
      (focus! :list-node)
      (is (within?) "the node itself")
      (is (list-target? :list-node))
      (focus! :field)
      (is (within?) "a text field inside")
      (is (not (list-target? :field)) "but its keys are the field's")
      (focus! :other)
      (is (not (within?)) "a node outside")
      (is (not (list-target? :other)))
      (is (not (ui/on-fx-thread #(keys/focus-within? nil))) "no node")
      (finally
        (ui/on-fx-thread #(.hide ^Stage stage))))))

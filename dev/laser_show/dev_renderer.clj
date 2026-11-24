(ns laser-show.dev-renderer
  "The app renderer's dev-mode middleware (reactivity plan 31): cljfx.dev's
   description validation, cljfx component stacks in render errors and its
   F12 inspector, around the app's own type->lifecycle. Only on the :dev
   classpath (dev/ and the io.github.cljfx/dev jar): in dev mode
   app/create-app resolves `middleware` and gives it to fx/create-app as
   :renderer-middleware.

   Loading it loads cljfx.dev, with the help in dev/cljfx/dev/help.clj in
   place of the jar's, and replaces two of cljfx.dev's specs that reject
   descriptions cljfx 1.10.6 renders (below)."
  (:require [cljfx.dev :as dev]
            [cljfx.lifecycle :as lifecycle]
            [clojure.spec.alpha :as s]))

;; Spec corrections for io.github.cljfx/dev 1.10.6.42. Each still rejects
;; what cljfx can't render (laser-show.renderer-wiring-test pins both
;; sides): after a dependency upgrade, drop one and rerun that test to see
;; whether it is still needed.

;; cljfx.dev checks each child of an ext-many as a description on its own,
;; with the :fx/key cljfx uses for the child's identity and takes off before
;; the child gets it (lifecycle/wrap-many), so a keyed child fails as having
;; an unexpected prop. Check a copy without it; the description itself is
;; left as it is.
(s/def :cljfx.ext-many/desc
  (s/coll-of (s/and map? (s/conformer #(dissoc % :fx/key)) :cljfx/desc)))

;; cljfx.dev declares a menu item's :style-class (and a split menu button's)
;; a string. cljfx takes a string or a collection of strings
;; (coerce/style-class), as cljfx.dev's own :cljfx/style-class says.
(s/def :cljfx.composite.menu-item/style-class :cljfx/style-class)
(s/def :cljfx.composite.split-menu-button/style-class :cljfx/style-class)

(defn middleware
  "fx/create-app's :renderer-middleware in dev mode. Returns delegate (the
   lifecycle cljfx puts under the app's desc-fn and context) with each
   call's :fx.opt/type->lifecycle B replaced by D, cljfx.dev's wrapper
   around B (wrap-type->lifecycle, F12 for the inspector): every
   description is validated, a render error names the cljfx component
   stack, F12 on the main window opens the inspector. D still takes each
   type's lifecycle from B, so the views keep their context and
   subscriptions. It is made once for B, so a type keeps its one lifecycle
   and its components advance in place. Everything else is passed through:
   the description, the component, the other options (:fx/context, the
   app's map event handler) and the delegate's result."
  [delegate]
  (let [wrap (memoize #(dev/wrap-type->lifecycle :type->lifecycle % :inspector-shortcut [:f12]))
        with-dev #(update % :fx.opt/type->lifecycle wrap)]
    (reify lifecycle/Lifecycle
      (create [_ desc opts]
        (lifecycle/create delegate desc (with-dev opts)))
      (advance [_ component desc opts]
        (lifecycle/advance delegate component desc (with-dev opts)))
      (delete [_ component opts]
        (lifecycle/delete delegate component (with-dev opts))))))

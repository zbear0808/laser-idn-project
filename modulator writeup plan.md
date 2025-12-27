

effect-chain = composition of fns applied to whole frame




modulator = any arbitrary curve, but the value of that curve needs to depend on something. 
- time (beat based, or seconds based)
- space (xy pos, radial distance or angle)

 mod-config is used to make mod-fn 

 mod-fn-creator takes in only mod-config -> returns mod-fn

 mod-fn only has parameters  relating to points like time and pos 
```clojure
(defn mod-fn [animation-time-elapsed poin-pos point-index]
    "returns a number"
    some-number ; ideally based on calculations requiring time or pos

) ; maybe this could be expanded to include all traits a point could have, like color or brightness
; can also have some modulator fns just read midi or osc data directly
```

should be able to control any numerical effect parameter using a modulator




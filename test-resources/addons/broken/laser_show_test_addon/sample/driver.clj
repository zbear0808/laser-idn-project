(ns laser-show-test-addon.sample.driver
  "A driver namespace that throws while it loads.")

(throw (ex-info "This add-on is broken on purpose" {}))

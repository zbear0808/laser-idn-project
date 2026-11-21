# Add-on loader test fixtures

Sources for the add-on jars that `test/laser_show/output/addons_test.clj`,
`test/laser_show/events/core_test.clj` and
`test/laser_show/ui_test/addons_settings_test.clj` build with
`laser-show.output.jar-builder` (plans/2026-11-19-output-drivers/02 § D3).
They are test fixtures only: never built or shipped as add-ons (OD-8).

**This folder must not be on any classpath** (no alias in `deps.edn` has
`test-resources` in its paths). If it were, the loader would find the
`laser-show-addon.edn` files as classpath descriptors.

| Folder | What the jar is |
|---|---|
| `sample/` | A good add-on, `:test/sample`, with one driver, `:test/sample-stream`, that keeps the frames it is sent in an atom in its own namespace |
| `broken/` | The driver namespace throws while it loads |
| `api2/` | Built for output API 2 |
| `taken-id/` | Two drivers, one of them with the sample's driver id. Its namespace also has drivers with the id `:idn`, without `:send-frame!`, and one that isn't a map, for descriptors the tests write |
| `bundles-clojure/` | Has a `clojure/core__init.class` entry (a fake one) |
| `read-eval/` | A descriptor with `#=(…)`, which must never be evaluated |
| `windows-only/` | `:platforms [:windows]` |
| `udp/` | A second good add-on, `:test/udp`, whose driver `:test/udp-stream` sends one datagram per frame to its `:host` and `:port`: the sending thread's name, the point count and the lit point count, as text. It needs `laser-show.output.api`, as a real add-on does. The UI test `addon-driver-appears-and-streams-from-the-ui-test` streams to it |

**Every variant is the sample with its own files on top**: a jar from `api2/`
has the sample's files, with `api2/laser-show-addon.edn` in place of the
sample's descriptor.

**Namespace names are unique per jar.** Clojure's namespaces are global to the
JVM, and `require` doesn't load a namespace that is already loaded. So the
sources use the placeholder root `laser-show-test-addon` (path
`laser_show_test_addon/`), and the jar builder renames it in every path and
every text file to a root of its own for each jar it builds
(`laser-show-test-addon-7`). A test that builds "the sample" twice gets two
jars with different namespaces, and each one is really loaded from its jar.
A namespace that was never required is never in `(all-ns)`, whatever other
tests loaded.

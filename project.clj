(defproject com.github.danlentz/clj-xref "0.1.0"
  :description "Cross-reference database for Clojure code"
  :url "https://github.com/danlentz/clj-xref"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [clj-kondo/clj-kondo "2026.01.19"]
                 [com.github.danlentz/clj-format "0.1.2"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]
                                  [clj-http "3.13.0"]]}}
  :aliases {"xref-dev" ["trampoline" "run" "-m" "clojure.main" "-e"
                        "(require '[clj-xref.tool :as t]) (t/generate {:paths [\"src\"]})"]
            "bench"    ["trampoline" "run" "-m" "clojure.main" "-e"
                        "(require '[clj-xref.bench :as b]) (b/print-results (b/run-benchmark {:paths [\"src\"]}))"]}
  :signing  {:gpg-key "0CA466A1AB48F0C0264AF55307BAD70176C4B179"}
  :repl-options {:init-ns clj-xref.core})

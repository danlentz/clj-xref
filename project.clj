(defproject com.github.danlentz/clj-xref "0.1.0"
  :description "Cross-reference database for Clojure code"
  :url "https://github.com/danlentz/clj-xref"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [clj-kondo/clj-kondo "2026.01.19"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]]}}
  :signing  {:gpg-key "0CA466A1AB48F0C0264AF55307BAD70176C4B179"}
  :repl-options {:init-ns clj-xref.core})

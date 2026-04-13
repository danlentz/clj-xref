(defproject clj-xref "0.1.0-SNAPSHOT"
  :description "Cross-reference database for Clojure code"
  :url "https://github.com/FIXME/clj-xref"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [clj-kondo/clj-kondo "2024.08.01"]]
  :repl-options {:init-ns clj-xref.core})

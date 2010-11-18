(ns cake.tasks.compile
  (:use cake cake.core cake.ant cake.file
        [cake.project :only [reload!]]
        [bake.core :only [verbose? debug? log]]
        [cake.utils.find-namespaces :only [find-clojure-sources-in-dir read-file-ns-decl]]
        [cake.utils :only [os-name os-arch sudo prompt-read]]
        [cake.utils.useful :only [pluralize]]
	[cake.ant :only [fileset-seq]])
  (:import [org.apache.tools.ant.taskdefs Copy Javac Java]))

(defn compile-java [src]
  (let [start (System/currentTimeMillis)]
    (when (.exists src)
      (ant Javac (merge {:destdir     (file "classes")
                         :classpath   (classpath)
                         :srcdir      (path src)
                         :fork        true
                         :verbose     (verbose?)
                         :debug       true
                         :debug-level "source,lines"
                         :target      "1.5"
                         :failonerror true}
                        (:java-compile *project*))))
    (when (some #(newer? % start) (file-seq (file "classes")))
      (reload!))))

(defn classfile [ns]
  (file "classes"
    (.. (str ns)
        (replace "-" "_")
        (replace "." "/")
        (concat "__init.class"))))

(defn source-dir []
  (let [src (file "src" "clj")]
    (if (.exists src) src (file "src"))))

(defn stale-namespaces [source-path aot]
  (let [compile?
        (if (= :all aot)
          (constantly true)
          (if (= :exclude (first aot))
            (complement (partial contains? (set (rest aot))))
            (let [aot (set aot)]
              (fn [namespace]
                (or (= namespace (:main *project*))
                    (contains? aot namespace))))))]
    (remove nil?
      (for [sourcefile (find-clojure-sources-in-dir source-path)]
        (let [namespace (second (read-file-ns-decl sourcefile))
              classfile (classfile namespace)]
          (when (and (compile? namespace) (newer? sourcefile classfile))
            namespace))))))

(defn compile-clojure [source-path compile-path aot]
  (when-let [stale (seq (stale-namespaces source-path aot))]
    (.mkdirs compile-path)
    (log "Compiling" (pluralize (count stale) "clojure namespace"))
    (bake (:use [bake.core :only [log]])
          [libs stale
           path (.getPath compile-path)]
      (binding [*compile-path* path]
        (doseq [lib libs]
          (log "Compiling namespace" lib)
          (compile lib))))
    (reload!)))

(defn copy-native []
  (ant Copy {:todir (format "native/%s/%s" (os-name) (os-arch))}
       (add-fileset {:dir "build/native/lib"})))

(deftask compile #{deps compile-native}
  "Compile all clojure and java source files. Use 'cake compile force' to recompile."
  (copy-native)
  (compile-java (file "src" "jvm"))
  (compile-clojure (source-dir) (file "classes") (:aot *project*))
  (compile-clojure (file "test") (file "test" "classes") (:aot-test *project*)))

;; add actions to compile-native if you need to compile native libraries
;; see http://github.com/lancepantz/tokyocabinet for an example
(deftask compile-native)

(deftask install-native #{compile-native}
  (copy-native)
  (let [files (vec (map str (fileset-seq {:dir (file "lib" "native")
                                          :includes "*"})))
        default "/usr/lib/java/"
        dest (prompt-read (format "java.library.path [%s]:" default))	
        dest (if (= "" dest) default dest)]
    (apply sudo "cp" (conj files dest))))


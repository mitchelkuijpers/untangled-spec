(ns untangled-spec.reporters.terminal
  (:require [clojure.test :as t]
            [clojure.stacktrace :as stack]
            [untangled-spec.reporters.impl.terminal :as impl]
            [untangled-spec.reporters.impl.diff :as diff]
            [colorize.core :as c]
            [clojure.string :as s]
            [io.aviso.exception :refer [format-exception *traditional*]]
            [clojure.pprint :refer [pprint]]))

(def env (let [COLOR     (System/getenv "US_DIFF_HL")
               DIFF-MODE (System/getenv "US_DIFF_MODE")
               DIFF      (System/getenv "US_DIFF")
               NUM-DIFFS (System/getenv "US_NUM_DIFFS")]
           {:color?          (#{"1" "true"}  COLOR)
            :diff-hl?        (#{"hl" "all"}  DIFF-MODE)
            :diff-list? (not (#{"hl"}        DIFF-MODE))
            :diff?      (not (#{"0" "false"} DIFF))
            :num-diffs  (read-string (or NUM-DIFFS "1"))}))

(defn color-str [status & strings]
  (let [color? (:color? env)
        status->color (cond-> {:passed c/green
                               :failed c/red
                               :error  c/red
                               :diff/impl (fn [[got exp]]
                                            ((comp c/bold c/inverse)
                                              (str exp " != " got)))}
                        color? (merge {:normal (comp c/bold c/yellow)
                                       :diff (comp c/bold c/cyan)
                                       :where (comp c/bold c/white)}))
        color-fn (or (status->color status) c/reset)]
    (apply color-fn strings)))

(defn pad [pad n] (apply str (repeat n pad)))

(defn space-level [level]
  (pad " " (* 2 level)))

(defn print-throwable [e]
  (println (format-exception e {:frame-limit 10}))
  (some-> (.getCause e) print-throwable))

(defmethod print-method Throwable [e w]
  (print-method (c/red e) w))

(defn pretty-str [s n]
  (as-> (with-out-str (pprint s)) s
    (clojure.string/split s #"\n")
    (apply str (interpose (str "\n" (pad " " (inc (* 2 n)))) s))))

(defn print-highligted-diff [diff actual]
  (as-> diff d
    (reduce (fn [out d]
              (let [{:keys [exp got path]} (diff/extract d)]
                (->> [got exp]
                     (color-str :diff/impl)
                     (#(if (empty? path) %
                         (assoc-in out path %))))))
            actual d)
    (pretty-str d 2)
    (println "EXP != ACT:" d)))

(defn print-diff [diff actual print-fn]
  (when (and (env :diff?) (diff/diff-paths? diff))
    (println)
    (when (env :diff-list?)
      (let [num-diffs (env :num-diffs)
            num-diffs (if (number? num-diffs)
                        num-diffs (count diff))]
        (println (color-str :diff "diffs:"))
        (doseq [d (take num-diffs diff)]
          (let [{:keys [exp got path]} (diff/extract d)]
            (when (seq path)
              (println (str "-  at: " path)))
            (println "  exp:" (pretty-str exp 6))
            (println "  got:" (pretty-str got 3))
            (println)))
        (when (< num-diffs (count diff))
          (println "&" (- (count diff) num-diffs) "more..."))))
    (when (and (env :diff-hl?) (coll? actual))
      (print-highligted-diff diff actual))))

(defn ?ellipses [s]
  (binding [*print-level* 3
            *print-length* 2]
    (apply str (drop-last (with-out-str (pprint s))))))

(defn print-message [m print-fn]
  (print-fn (color-str :normal "ASSERTION:")
            (let [?fix #(case %
                          "" "\"\""
                          nil "..nil.."
                          %)
                  arrow (re-find #" =.*?> " m)
                  [act exp] (s/split m #" =(.*?)> ")]
              (str (-> act ?fix read-string ?ellipses)
                   arrow
                   (-> exp ?fix read-string ?ellipses)))))

(defn print-extra [e print-fn]
  (print-fn (color-str :normal "    extra:") e))

(defn print-where [w s print-fn]
  (let [status->str {:error "Error"
                     :failed "Failed"}]
    (->> (str (status->str s) " in " w)
         (color-str :where)
         print-fn)))

(defn print-test-result [{:keys [message where status actual
                                 expected extra throwable diff]}
                         print-fn print-level]
  (print-fn)
  (some-> where (print-where status print-fn))
  (when (and (= status :error)
             (instance? Throwable actual))
    (print-throwable actual))
  (when (and throwable
             (not (instance? Throwable actual)))
    (print-throwable throwable))
  (some-> message (print-message print-fn))
  (when (or (not diff) (not (env :diff?))
            (and (not (env :diff-hl?)) (not (env :diff-list?))))
    (print-fn "   Actual:" (pretty-str actual (+ 5 print-level)))
    (print-fn " Expected:" (pretty-str expected (+ 5 print-level))))
  (some-> extra (print-extra print-fn))
  (some-> diff (print-diff actual print-fn))
  (when true ;TODO: -> env/cfg
    (throw (ex-info "" {::stop? true}))))

(defn print-test-item [test-item print-level]
  (t/with-test-out
    (println (space-level print-level)
             (color-str (:status test-item)
                        (:name test-item)))
    (->> (:test-results test-item)
         (remove #(= (:status %) :passed))
         (mapv #(print-test-result % (->> print-level inc space-level
                                          (partial println))
                                   (inc print-level))))
    (->> (:test-items test-item)
         (mapv #(print-test-item % (inc print-level))))))

(defn print-namespace [make-tests-by-namespace]
  (t/with-test-out
    (println)
    (println (color-str (:status make-tests-by-namespace)
                        "Testing " (:name make-tests-by-namespace)))
    (->> (:test-items make-tests-by-namespace)
         (mapv #(print-test-item % 1)))))

(defn print-report-data
  "Prints the current report data from the report data state and applies colors based on test results"
  []
  (t/with-test-out
    (let [{:keys [namespaces tested passed failed error]} @impl/*test-state*]
      (try (->> namespaces
                (mapv print-namespace))
           (catch Exception e
             (when-not (->> e ex-data ::stop?)
               (print-throwable e))))
      (println "\nRan" tested "tests containing"
               (+ passed failed error) "assertions.")
      (println failed "failures,"
               error "errors."))))

(defmulti ^:dynamic untangled-report :type)

(defmethod untangled-report :default [m])

(defmethod untangled-report :pass [m]
  (t/inc-report-counter :pass)
  (impl/pass))

(defmethod untangled-report :error [m]
  (t/inc-report-counter :error)
  (impl/error (-> m (merge {:where (clojure.test/testing-vars-str m)}))))

(defmethod untangled-report :fail [m]
  (t/inc-report-counter :fail)
  (impl/fail (-> m (merge {:where (clojure.test/testing-vars-str m)}))))

(defmethod untangled-report :begin-test-ns [m]
  (impl/begin-namespace (ns-name (:ns m))))

(defmethod untangled-report :end-test-ns [m]
  (impl/end-namespace))

(defmethod untangled-report :begin-specification [m]
  (impl/begin-specification (:string m)))

(defmethod untangled-report :end-specification [m]
  (impl/end-specification))

(defmethod untangled-report :begin-behavior [m]
  (impl/begin-behavior (:string m)))

(defmethod untangled-report :end-behavior [m]
  (impl/end-behavior))

(defmethod untangled-report :begin-manual [m]
  (impl/begin-behavior (str (:string m) "(MANUAL)")))

(defmethod untangled-report :end-manual [m]
  (impl/end-behavior))

(defmethod untangled-report :begin-provided [m]
  (impl/begin-provided (:string m)))

(defmethod untangled-report :end-provided [m]
  (impl/end-provided))

(defmethod untangled-report :summary [m]
  (let [stats {:tested (:test m) :passed (:pass m)
               :failed (:fail m) :error (:error m)}]
    (impl/summary stats)
    (print-report-data)))

(defmacro with-untangled-output
  "Execute body with modified test reporting functions that produce
  outline output"
  [& body]
  `(binding [t/report untangled-report]
     ~@body))

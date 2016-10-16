(ns untangled-spec.core
  (:require clojure.test
            [clojure.string :as str]

            [untangled-spec.provided :as p]
            [untangled-spec.async :as async]
            [untangled-spec.assertions :as ae]
            [untangled-spec.stub]
            [clojure.spec :as s]))

(defn cljs-env?
  "https://github.com/Prismatic/schema/blob/master/src/clj/schema/macros.clj"
  [env] (boolean (:ns env)))

(defn if-cljs [env cljs clj]
  (if (cljs-env? env) cljs clj))

(defmethod clojure.test/assert-expr 'exec [msg form]
  `(clojure.test/do-report ~(ae/assert-expr 'exec msg form)))

(defmethod clojure.test/assert-expr '= [msg form]
  `(clojure.test/do-report ~(ae/assert-expr 'eq msg form)))

(defmethod clojure.test/assert-expr 'throws? [msg form]
  `(clojure.test/do-report ~(ae/assert-expr 'throws? msg form)))

(defn var-name-from-string [s]
  (symbol (str "__" (str/replace s #"[^\w\d\-\!\#\$\%\&\*\_\<\>\:\?\|]" "-") "__")))

(defmacro specification
  "Defines a specification which is translated into a what a deftest macro produces with report hooks for the
  description. Technically outputs a deftest with additional output reporting.
  When *load-tests* is false, the specification is ignored."
  [description & body]
  (let [var-name (var-name-from-string description)
        prefix (if-cljs &env "cljs.test" "clojure.test")]
    `(~(symbol prefix "deftest") ~(symbol (str var-name (gensym)))
               (~(symbol prefix "do-report")
                         {:type :begin-specification :string ~description})
               ~@body
               (~(symbol prefix "do-report")
                         {:type :end-specification :string ~description}))))

(defmacro behavior
  "Adds a new string to the list of testing contexts.  May be nested,
  but must occur inside a specification. If the behavior is not machine
  testable then include the keyword :manual-test just after the string
  description instead of code.

  (behavior \"blows up when the moon is full\" :manual-test)

  "
  [string & body]
  (let [options (into #{} (take-while keyword? body))
        manual-intervention-required (boolean (options :manual-test))
        startkw (if manual-intervention-required :begin-manual :begin-behavior)
        stopkw (if manual-intervention-required :end-manual :end-behavior)
        body (drop-while keyword? body)
        prefix (if-cljs &env "cljs.test" "clojure.test")]
    `(~(symbol prefix "testing") ~string
               (~(symbol prefix "do-report")
                         {:type ~startkw :string ~string})
               ~@body
               (~(symbol prefix "do-report")
                         {:type ~stopkw :string ~string}))))

(defmacro component
  "An alias for behavior. Makes some specification code easier to read where a given specification is describing subcomponents of a whole."
  [string & body]
  `(behavior ~string ~@body))

(defmacro with-timeline
  "Adds the infrastructure required for doing timeline testing"
  [& forms]
  `(let [~'*async-queue* (async/make-async-queue)]
     ~@forms))

(defmacro async
  "Adds an event to the event queue with the specified time and callback function.
  Must be wrapped by with-timeline.
  "
  [tm cb]
  `(async/schedule-event ~'*async-queue* ~tm (fn [] ~cb)))

(defmacro tick
  "Advances the timer by the specified number of ticks.
  Must be wrapped by with-timeline."
  [tm]
  `(async/advance-clock ~'*async-queue* ~tm))

(defmacro provided
  "A macro for using a Midje-style provided clause within any testing framework. This macro rewrites
  assertion-style mocking statements into code that can do that mocking.
  "
  [string & forms]
  (apply p/provided-fn (cljs-env? &env) string forms))

(defmacro when-mocking
  "A macro that works just like 'provided', but requires no string and outputs no extra text in the test output."
  [& forms]
  (apply p/provided-fn (cljs-env? &env) :skip-output forms))

(s/fdef assertions
        :args ::ae/assertions)
(defmacro assertions [& forms]
  (let [blocks (s/conform ::ae/assertions forms)
        asserts (map (partial ae/block->asserts (cljs-env? &env)) blocks)]
    `(do ~@asserts)))

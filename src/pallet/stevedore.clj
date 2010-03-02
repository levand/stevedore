(ns #^{ :doc "Based on scriptjure"} pallet.stevedore
  (:require [clojure.contrib.str-utils2 :as string])
  (:use clojure.walk
        clojure.contrib.logging
        [pallet.utils :only [underscore]]
        pallet.script))


(defn arg-string [option argument do-underscore]
  (let [opt (if do-underscore (underscore (name option)) (name option))]
    (if argument
      (if (> (.length opt) 1)
        (str "--" opt (if-not (= argument true) (str "=" argument)))
        (str "-" opt (if-not (= argument true) (str " " argument)))))))

(defn map-to-arg-string [m & options]
  (let [opts (apply hash-map options)]
    (println "map-to-arg-string " m)
    (apply str (interpose " " (map #(arg-string (first %) (second %) (opts :underscore)) m)))))

(declare inner-walk outer-walk)



(defmulti emit (fn [ expr ] (do (type expr))))

(defmulti emit-special (fn [ & args] (identity (first args))))

(def statement-separator "\n")

(defn statement [expr]
  (if (not (= statement-separator (string/tail expr (count statement-separator))))
    (str expr statement-separator)
    expr))

(defn comma-list [coll]
  (str "(" (string/join ", " coll) ")"))

(defmethod emit nil [expr]
  "null")

(defmethod emit java.lang.Integer [expr]
  (str expr))

(defmethod emit clojure.lang.Ratio [expr]
  (str (float expr)))

(defmethod emit clojure.lang.Keyword [expr]
  (str (name expr)))

(defmethod emit java.lang.String [expr]
  expr)

(defmethod emit clojure.lang.Symbol [expr]
  (str expr))

(defmethod emit :default [expr]
  (str expr))

(def special-forms (set ['if 'if-not '= 'aget 'fn 'return 'set! 'var 'let 'local 'literally 'deref 'do 'str]))

(def infix-operators (set ['+ '- '/ '* '% '== '< '> '<= '>= '!= '<< '>> '<<< '>>> '!== '& '^ '| '&& '||]))
(def logical-operators (set ['== '< '> '<= '>= '!= '<< '>> '<<< '>>> '!== '& '^ '| '&& '||]))

(def infix-conversions { '&& "-a" })

(defn special-form? [expr]
  (contains? special-forms expr))

(defn compound-form? [expr]
  (= 'do  (first expr)))

(defn infix-operator? [expr]
  (contains? infix-operators expr))

(defn logical-operator? [expr]
  (contains? logical-operators expr))

(defn emit-infix [type [operator & args]]
  (when (< (count args) 2)
    (throw (Exception. "not supported yet")))
  (let [open (if (logical-operator? operator) "\\( " "(")
        close (if (logical-operator? operator) " \\)" ")")]
    (str open (emit (first args)) " "
         (get infix-conversions operator operator)
         " " (emit (second args)) close)))

(defmethod emit-special 'local [type [local name expr]]
  (str "local " (emit name) "=" (emit expr)))

(defmethod emit-special 'var [type [var name expr]]
  (str (emit name) "=" (emit expr)))

(defmethod emit-special 'let [type [let name expr]]
  (str "let " (emit name) "=" (emit expr)))

(defmethod emit-special 'str [str [str & args]]
  (string/map-str emit args))

(defmethod emit-special 'invoke [type [name & args]]
  (debug (str "invoke [" *script-file*
              ":" *script-line* "] "
              name (print-args args)))
  (or (try
       (invoke-target name (map (partial walk inner-walk outer-walk) args))
       (catch java.lang.IllegalArgumentException e
         (throw (java.lang.IllegalArgumentException. (str "Invalid arguments for " name) e))))
      (apply str (emit name) (if (empty? args) "" " ") (interpose " " (map emit args)))))

(defn emit-method [obj method args]
  (str (emit obj) "." (emit method) (comma-list (map emit args))))

(defmethod emit-special 'if [type [if test true-form & false-form]]
  (letfn [(emit-body [form]
                     (if (compound-form? form)
                       (str \newline (emit form))
                       (str " " (emit form) ";")))]
    (str "if " (if (and (sequential? test) (infix-operator? (first test)))
                 (str "[ " (emit test) " ]")
                 (emit test))
         "; then"
         (emit-body true-form)
         (when (first false-form)
           (str "else" (emit-body (first false-form))))
         "fi\n")))

(defmethod emit-special 'if-not [type [if test true-form & false-form]]
  (str "if ! " (emit test) "; then\n"
       (emit true-form)
       "\n"
       (when (first false-form)
	 (str "else\n"
	      (emit (first false-form))))
       "\nfi\n"))

(defmethod emit-special 'dot-method [type [method obj & args]]
  (let [method (symbol (string/drop (str method) 1))]
    (emit-method obj method args)))

(defmethod emit-special 'return [type [return expr]]
  (str "return " (emit expr)))

(defmethod emit-special 'set! [type [set! var val]]
  (str (emit var) " = " (emit val)))

(defmethod emit-special 'new [type [new class & args]]
  (str "new " (emit class) (comma-list (map emit args))))

(defmethod emit-special 'aget [type [aget var idx]]
  (str "${" (emit var) "[" (emit idx) "]}"))

(defmethod emit-special 'deref [type [deref expr]]
  (if (instance? clojure.lang.IPersistentList expr)
    (str "$(" (emit expr) ")")
    (str "${" (emit expr) "}")))

(defn emit-do [exprs]
  (string/join "" (map (comp statement emit) exprs)))

(defmethod emit-special 'do [type [ do & exprs]]
  (emit-do exprs))

(defn emit-function [name sig body]
  (assert (or (symbol? name) (nil? name)))
  (assert (vector? sig))
  (str "function " name (comma-list sig) " {\n" (emit-do body) " }\n"))

(defmethod emit-special 'fn [type [fn & expr]]
  (if (symbol? (first expr))
    (let [name (first expr)
	  signature (second expr)
	  body (rest (rest expr))]
      (emit-function name signature body))
    (let [signature (first expr)
	  body (rest expr)]
      (emit-function nil signature body))))

(defmethod emit clojure.lang.Cons [expr]
  (emit (list* expr)))

(defmethod emit clojure.lang.IPersistentList [expr]
  (if (symbol? (first expr))
    (let [head (symbol (name (first expr)))  ; remove any ns resolution
	  expr (conj (rest expr) head)]
      (debug (str "emit list " (print-args expr)))
      (cond
	(and (= (string/get (str head) 0) \.) (> (count (str head)) 1)) (emit-special 'dot-method expr)
	(special-form? head) (emit-special head expr)
	(infix-operator? head) (emit-infix head expr)
	:else (emit-special 'invoke expr)))
    (throw (new Exception (str "invalid form: " expr)))))

(defmethod emit clojure.lang.IPersistentVector [expr]
  (str "(" (string/join " " (map emit expr)) ")"))

;; (defmethod emit clojure.lang.IPersistentMap [expr]
;;   (map-to-arg-string expr))

;(defmethod emit clojure.lang.LazySeq [expr]
;  (emit (into [] expr)))

(defmethod emit clojure.lang.IPersistentMap [expr]
  (letfn [(subscript-assign [pair] (str "["(emit (key pair)) "]=" (emit (val pair))))]
    (str "(" (string/join " " (map subscript-assign (seq expr))) ")")))

(defn _script [forms]
  (let [code (if (> (count forms) 1)
	       (emit-do forms)
	       (emit (first forms)))]
    code))

(defn- unquote?
  "Tests whether the form is (clj ...)."
  [form]
  (or (and (seq? form) (symbol? (first form)) (= (symbol (name (first form))) 'clj))
      (and (seq? form) (= (first form) `unquote))))

(defn handle-unquote [form]
  (second form))

(defn- inner-walk [form]
  (cond
    (unquote? form) (handle-unquote form)
    :else (walk inner-walk outer-walk form)))

(defn- outer-walk [form]
  (cond
    (symbol? form) (list 'quote form)
    (seq? form) (list* 'list form)
    :else form))

(defmacro quasiquote [form]
  (let [post-form (walk inner-walk outer-walk form)]
    post-form))

(defmacro script
  "Takes one or more forms. Returns a string of the forms translated into javascript"
  [& forms]
  `(with-line-number
     (_script (quasiquote ~forms))))

(defmacro defimpl
  "Define a script fragment implementation for a given set of specialisers"
  [script-name specialisers [& args]  & body]
  #^{:pre [(or (= :default specialisers)
               (vector? specialisers))]}
  `(alter-var-root
    (find-var 'pallet.script/*scripts*)
    (fn [current#]
      (add-to-scripts
       current#
       (keyword ~(name script-name))
       ~specialisers
       (fn [~@args] (script ~@body))))))
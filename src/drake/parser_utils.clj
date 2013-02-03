(ns drake.parser_utils
  (:require [name.choi.joshua.fnparse :as p])
  (:use [slingshot.slingshot :only [throw+]]))

;; The parsing state data structure. The remaining tokens are stored
;; in :remainder, and the current column and line are stored in their
;; respective fields. :vars is a map of additional key/value pairs that
;; can help keep state. As FnParse applies rules to this state and consumes
;; tokens, the state gets modified accordingly.
(defstruct state-s :remainder :vars :methods :column :line)


;; rep+ and rep* return a vector of the products of the rules being repeated.
;; Even if a rule's product is nil, the nil would show up in the vector.
;; The two following rules are helpful for ignoring products from certain
;; rules.
(defn nil-semantics [subrule]
  (p/constant-semantics subrule nil))
(defn semantic-rm-nil [subrule]
  (p/semantics subrule
             (fn [product] (remove #(nil? %) product))))

(def apply-str
  (partial apply str))


;; These functions are given a rule and make it so that it
;; increments the current column (or the current line).
;;non-line-breaks
(defn nb-char [subrule]
  (p/invisi-conc subrule (p/update-info :column inc)))
(def nb-char-lit
  (comp nb-char p/lit)) ; lit is a FnParse function that creates a literal
                      ; rule.
;;line-breaks
(defn b-char [subrule]
  (p/invisi-conc subrule (p/update-info :line inc)
                       (p/set-info :column 1)))     ;; column is 1-based


;; parse errors

(defn throw-parse-error [state message message-args]
  (throw+ {:msg
           (str (when (:file-path state) (str "In " (:file-path state) ", "))
                (format "parse error at line %s, column %s: "
                        (:line state) (:column state))
                (apply format message message-args))}))

(defn first-word [lit-array]
  "Input is array of literals, usually the remaining tokens. It
   identifies the first word, which can be used to present a more helpful
   error message."
  (if (or (nil? (first lit-array))
          (re-matches #"\s" (str (first lit-array))))
    nil
    (str (first lit-array) (first-word (rest lit-array)))))

(defn expectation-error-fn [expectation]
  (fn [remainder state]
    (throw+ {:msg (format "%s expected where \"%s\" is"
                          expectation (or (first-word remainder) "EOF"))})))

(defn illegal-syntax-error-fn [var-type]
  (fn [remainder state]
    (throw+ {:msg (format "illegal syntax starting with \"%s\" for %s"
                          (or (first-word remainder) "EOF") var-type)})))


;; And here are where this parser's rules are defined.

(def string-delimiter
  (nb-char-lit \"))

(def escape-indicator
  (nb-char-lit \\))

(def false-lit
  (p/constant-semantics (p/lit-conc-seq "false" nb-char-lit)
                        false))

(def true-lit
  (p/constant-semantics (p/lit-conc-seq "true" nb-char-lit)
                        true))

(def null-lit
  (p/constant-semantics (p/alt (p/lit-conc-seq "null" nb-char-lit)
                             (p/lit-conc-seq "nil" nb-char-lit))
                        nil))

(def keyword-lit (p/alt false-lit true-lit null-lit))

(def space (nb-char-lit \space))
(def tab (nb-char-lit \tab))
(def newline-lit (p/lit \newline))
(def return-lit (p/lit \return))
(def line-break (b-char (p/alt newline-lit return-lit)))
(def ws (p/constant-semantics (p/rep+ (p/alt space tab line-break)) :ws))
(def inline-ws (p/constant-semantics (p/rep+ (p/alt space tab)) :inline-ws))

(defn opt-inline-ws-wrap [rule]
  (p/complex [_ (p/opt inline-ws)
            prod rule
            _ (p/opt inline-ws)]
           prod))

(def non-line-break (p/except (nb-char p/anything) line-break))

(def zero-digit (nb-char-lit \0))
(def nonzero-decimal-digit (p/lit-alt-seq "123456789" nb-char-lit))
(def decimal-digit (p/alt zero-digit nonzero-decimal-digit))

(def letter
  (p/lit-alt-seq (map char (concat (range (int \A) (inc (int \Z)))
                                   (range (int \a) (inc (int \z)))))
                 nb-char-lit))
(def alphanumeric
  (p/alt letter decimal-digit))
(def period (nb-char-lit \.))
(def comma (nb-char-lit \,))
(def underscore (nb-char-lit \_))
(def hyphen (nb-char-lit \-))
(def forward-slash (nb-char-lit \/))
(def colon (nb-char-lit \:))
(def semicolon (nb-char-lit \;))
(def exclamation-mark (nb-char-lit \!))
(def question-mark (nb-char-lit \?))
(def open-bracket (nb-char-lit \[))
(def close-bracket (nb-char-lit \]))
(def open-paren (nb-char-lit \())
(def close-paren (nb-char-lit \)))
(def minus-sign (nb-char-lit \-))
(def plus-sign (nb-char-lit \+))
(def decimal-point (nb-char-lit \.))
(def exponential-sign (p/lit-alt-seq "eE" nb-char-lit))
(def equal-sign (nb-char-lit \=))
(def lt-sign (nb-char-lit \<))
(def caret (nb-char-lit \^))
(def dollar-sign (nb-char-lit \$))
(def hashtag-sign (nb-char-lit \#))
(def percent-sign (nb-char-lit \%))

(def fractional-part (p/conc decimal-point (p/rep* decimal-digit)))

(def exponential-part
  (p/conc exponential-sign (p/opt (p/alt plus-sign minus-sign))
        (p/failpoint (p/rep+ decimal-digit)
          (expectation-error-fn
            (str "in number literal, after an exponent sign, decimal"
                 "digit")))))

(def number-lit
  (p/complex [minus (p/opt minus-sign)
            above-one (p/alt zero-digit (p/rep+ nonzero-decimal-digit))
            below-one (p/opt fractional-part)
            power (p/opt exponential-part)]
    (-> [minus above-one below-one power] flatten apply-str
      Double/parseDouble
      ((if (or below-one power) identity int)))))

(def hexadecimal-digit
  (p/alt decimal-digit (p/lit-alt-seq "ABCDEF" nb-char-lit)))

(def any-char
  (p/alt line-break (nb-char p/anything)))

(def unescaped-char
  (p/except any-char (p/alt escape-indicator string-delimiter)))

(def unicode-char-sequence
  (p/complex [_ (nb-char-lit \u)
              digits (p/factor= 4
                       (p/failpoint hexadecimal-digit
                         (expectation-error-fn "hexadecimal digit")))]
    (-> digits apply-str (Integer/parseInt 16) char)))
(def escaped-characters
  {\\ \\, \/ \/, \b \backspace, \f \formfeed, \n \newline, \r \return,
   \t \tab, \" \", \$ \$, \) \)})

(def normal-escape-sequence
  (p/semantics (p/lit-alt-seq (keys escaped-characters) nb-char-lit)
    escaped-characters))

(def escape-sequence
  (p/complex [_ escape-indicator
            character (p/alt unicode-char-sequence
                           normal-escape-sequence)]
    character))

(def string-char
  (p/alt escape-sequence unescaped-char))

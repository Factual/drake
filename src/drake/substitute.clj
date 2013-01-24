(ns drake.substitute
  (:use [slingshot.slingshot :only [throw+]]))

(defn- tokenize
  "Split a string into a sequence of plain strings and
   interpolation wrappers. For example, it turns
     \"This is a $[X] for your $[Y].\"
     into
     (\"This is a \" \"$[X]\" \" for your \" \"$[Y]\" \".\")"
  [s]
  (let [unquote-re
        (re-pattern
         (str "\\$\\[[^\\]]*\\]" ; a $ followed by a paren
              "|"
              "\\$[^\\[\\$]+"    ; a $ not followed by an open paren...
              "|"
              "[^\\$]+"          ; anything that is not a $
              "|"
              "\\$$"))]          ; a $ at the end
    (re-seq unquote-re s)))

(defn- varname
  "Pulls var name from an interpolation wrapper, e.g. \"$[A]\" => \"A\"
   s must be only the var within $[], nothing more nor less."
  [s]
  (subs s 2 (- (count s) 1)))

(defn- intfn [m s]
  (if-not (.startsWith s "$[")
    s
    (let [var (varname s)]
      (or
       (m var)
       (throw+ {:msg (str "Could not find var " var)})))))

;; TODO(aaron): "$[SUB" will produce "[SUB". instead, it should error out
(defn substitute
  "Substitutes vars in String s, denoted like $[VARNAME],
   with values pulled from m,
   where m must contain \"VARNAME\"s as keys.

   Vars with no mapping in m will cause an Exception to be thrown."
  [m s]
  (apply str
         (map #(intfn m %) (tokenize s))))

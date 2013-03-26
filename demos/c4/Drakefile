;
; Run from project root:
;
;   lein run --auto --workflow demos/c4
;
; Output will be created in demos/c4
;
; Or, if you have Drake installed, you can cd to demos/c4 and just run drake.
;


;
; Adds a FullName attribute to each row, using FirstName and LastName.
; Automagically converts the JSON formatted input to CSV.
;
out.csv <- in.json [c4row]
  (assoc row "FullName"
    (format "%s %s" (row "FirstName") (row "LastName")))

;
; Filters out rows with short last names.
; Adds a FullName attribute to each row, using FirstName and LastName.
;
long-lastnames.json <- in.json [c4rows]
  (map
    (fn [row]
      (assoc row "FullName"
        (format "%s %s" (row "FirstName") (row "LastName"))))
    (filter #(> (count (% "LastName")) 5) rows))

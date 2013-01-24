;
; Run from project root:
;
;   lein run --auto --workflow demos/McIlroy
;
; Output will be created in demos/McIlroy
;
; Or, if you have Drake installed, you can cd to demos/McIlroy and just run drake.
;

;
; Reads book.txt, determines the 12 most frequently used words, and
; saves a sorted list of those words along with their frequencies.
;
word-freqs.txt <- book.txt
  cat $INPUT |
  tr -cs A-Za-z '\n' |
  tr A-Z a-z |
  sort |
  uniq -c |
  sort -rn |
  sed 12q > $OUTPUT

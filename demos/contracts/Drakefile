;
; Assumes the .gov file is still available online.
;
; Run from project root:
;
;   lein run --auto --workflow demos/contracts
;
; Output will be created in demos/contracts
;
; Or, if you have Drake installed, you can cd to demos/contracts and just run drake.
;


;
; Grabs us some data from the Internets
;
contracts.csv <-
  curl http://www.ferc.gov/docs-filing/eqr/soft-tools/sample-csv/contract.txt > $OUTPUT

;
; Filters out all but the evergreen contracts
;
evergreens.csv <- contracts.csv
  grep Evergreen $INPUT > $OUTPUT

;
; Saves a super fancy report
;
report.txt <- evergreens.csv [python]
  linecount = len(file("$[INPUT]").readlines())
  with open("$[OUTPUT]", "w") as f:
    f.write("File $[INPUT] has {0} lines.\n".format(linecount))

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


; Fitlers out all but the service agreements 
service_agreements.csv <- contracts.csv
  grep "Service Agreement" $INPUT > $OUTPUT

;
; Saves a super fancy report
;
report.txt, last_gt_first.txt <- evergreens.csv, service_agreements.csv [python]
  linecount0 = len(file("$[INPUT0]").readlines())
  linecount1 = len(file("$[INPUT1]").readlines())
  with open("$[OUTPUT0]", "w") as f:
    f.write("File $[INPUT0] has {0} lines.\n".format(linecount0))
    f.write("File $[INPUT1] has {0} lines.\n".format(linecount1))


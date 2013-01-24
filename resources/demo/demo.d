; our base data directory
BASE=./resources/demo

; Merge data from all sources
combined.csv <- amazon.csv, walmart.csv
  cat $[INPUTS] > $OUTPUT

; Filter out some bad stuff
filtered.csv <- combined.csv
  grep -v "bad stuff" $INPUT > $OUTPUT

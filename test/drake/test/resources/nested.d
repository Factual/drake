; our base data directory
BASE=$[BASE]/nest/
NESTEDVAR=/foo


; Merge data from all sources
b.csv <- a.csv
  cat $[INPUTS]
  echo $[BASE]

sample_method() [eval]
  echo "method"

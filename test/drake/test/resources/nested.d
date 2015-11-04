; our base data directory
BASE=$[BASE]/nest/
NESTEDVAR=/foo
PWD=$(pwd)

; Merge data from all sources
b.csv <- a.csv
  cat $[INPUTS]
  echo $[BASE]

sample_method() [eval]
  echo "method"

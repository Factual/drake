STDOUT=$(dirname $0)/stdout.log
STDERR=$(dirname $0)/stderr.log

check() {
  if [ "$1" != "$2" ]; then
    echo "FAIL"
    echo "\"$1\" != \"$2\""
    exit -1
  fi
  echo "PASS"
}

check_not_equal() {
  if [ "$1" == "$2" ]; then
    echo "FAIL"
    echo "\"$1\" == \"$2\""
    exit -1
  fi
  echo "PASS"
}

check_grep() {
  if ! grep "$2" "$1" >/dev/null; then
    echo "FAIL"
    if [ -z "$3" ]; then
      echo "\"$2\" not found in $1."
    else
      echo $3
    fi
    exit -1
  fi
  echo "PASS"
}

run_d() {
  WORKFLOW_FILE=$1
  shift
  $(dirname $0)/../../bin/drake -w $(dirname $0)/$WORKFLOW_FILE $@
}

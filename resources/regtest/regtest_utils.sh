STDOUT=$(dirname $0)/stdout.log
STDERR=$(dirname $0)/stderr.log

check_exists() {
  for file in "$@"
  do
    echo "Making sure $file exists"
    if [ ! -f $file ]; then
      echo "FAIL"
      echo "file $file does not exist"
      exit -1
    fi
  done
  echo "PASS"
}

check_does_not_exist() {
  for file in "$@"
  do
    echo "Making sure $file does not exist"
    if [ -f $file ]; then
      echo "FAIL"
      echo "file $file exists"
      exit -1
    fi
  done
  echo "PASS"
}

check() {
  if [ "$1" != "$2" ]; then
    echo "FAIL"
    echo "\"$1\" != \"$2\""
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
  $(dirname $0)/../../bin/run.sh -w $(dirname $0)/$WORKFLOW_FILE $@
}

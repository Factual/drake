; regtest_stdout.d is used as a dummy input while no input steps are not
; supported
; TODO(artem) change it to a no input step
test1 <- regtest_stdout.d
  echo output: $OUTPUT
  echo STDOUT IS HERE
  echo STDERR IS HERE>/dev/stderr
  echo ANOTHER STDOUT

; A single line of input (stdin doesn't have to be closed)
test2 <- regtest_stdout.d
  read input
  sleep 1
  echo "User input: $input"

; Copy all of the stdin until closed
test3 <- regtest_stdout.d
  while read input; do echo "[$input]"; done


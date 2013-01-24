; Each interpreter just copies input file to output file
; adding an identifying prefix

shell.out <- test
  exec<$INPUT>$OUTPUT
  while read line; do
    echo shell_processed:$line
  done

python.out <- test [python]
  copy = open("$[OUTPUT]", "w")
  for line in open("$[INPUT]"):
    copy.write("python_processed:" + line)

ruby.out <- test [ruby]
  out = File.open("$[OUTPUT]", "w")
  File.open("$[INPUT]").each do |line|
    out.puts "ruby_processed:#{line}"
  end

R.out <- test [R]
  out <- file("$[OUTPUT]", "w")
  for (line in readLines(file("$[INPUT]"))) {
    writeLines(paste(c("R_processed:", line), collapse=""), out)
  }
  close(out)

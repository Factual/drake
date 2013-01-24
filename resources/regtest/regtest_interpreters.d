; Each interpreter just copies input file to output file
; adding an identifying prefix

python.out <- test [python]
  copy = open("$[OUTPUT]", "w")
  for line in open("$[INPUT]"):
    copy.write("python_processed:" + line)

ruby.out <- test [ruby]
  out = File.open("$[OUTPUT]", "w")
  File.open("$[INPUT]").each do |line|
    out.puts "ruby_processed:#{line}"
  end


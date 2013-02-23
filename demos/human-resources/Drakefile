;
; This is a demo workflow to show HDFS integration, multiple protocols,
; multiple inputs, and multiple outputs.
;
; Since you probably won't have the initial data in HDFS, nor the
; black/magic/getskills script, you'll want to exclude those steps
; and just use the sample data already provided. Like so, from
; project root:
;
;   lein run --auto --workflow demos/human-resources/workflow.d ... -people -skills
;
; Or, if you have Drake installed, you can cd to this directory and do:
;
;   drake ... -people -skills
;


;
; Fetches sorted people from HDFS, keeping only a select few, and
; sorting by name.
;
; Output is like:
;   Artem Boytsov,310-100-0000
;   Vinnie Pepi,+86-310-400-0000
;   Will Lao,+86-310-600-0000
;
people <- hdfs://peeps/everyone
  grep 310 $INPUT | sort > $OUTPUT

;
; Calls a legacy script that knows how to download a
; skills file, sorted by name, from our central db.
; Output is like:
;   Aaron Crow,java clojure gcal
;   Alvin Chyan,java ruby clojure
;   Maverick Lou,java clojure jenkins
;
skills <-
  ~/bin/black/magic/getskills --sortby name > $OUTPUT

;
; Joins people to their skills. Output is like:
;   Aaron Crow,java ruby clojure, 310-300-0000
;   Artem Boytsov,flying southwest, 310-100-0000
;   Maverick Lou,java clojure jenkins, +86-310-200-0000
;
; Includes a bit of debug output.
;
people.skills <- skills, people
  echo Number of inputs: $INPUTN
  echo All inputs: $INPUTS
  echo Joining ...
  join -t, $INPUTS > $OUTPUT

;
; Adds UUIDs and formalize to JSON.
; Uses Drake's python protocol, for inline Python.
;
people.json <- people.skills [python]
  import csv
  import json
  import uuid
  outfile = open('$[OUTPUT]', 'w')
  with open('$[INPUT]', 'rb') as csvfile:
    for row in csv.reader(csvfile):
      jsn = {'name': row[0], 'skills': row[1], 'tel': row[2]}
      jsn['uuid'] = str(uuid.uuid1())
      outfile.write("{0}\n".format(json.dumps(jsn)))

;
; Generates 2 reports:
;   1) All people whose last name is longer than their first
;   2) All people whose first name is longer than their last
;
; Uses Drake's ruby protocol, for inline Ruby.
;
last_gt_first.txt, first_gt_last.txt <- people.json [ruby]
  require 'json'
  lGf = File.open("$[OUTPUT0]", "w")
  fGl = File.open("$[OUTPUT1]", "w")
  File.open("$[INPUT]").each do |line|
    rec = JSON.parse(line)
    first, last = rec['name'].split(" ")
    lGf.puts(rec['name']) if last.length > first.length
    fGl.puts(rec['name']) if first.length > last.length
  end
  nil.str

;
; No JSON files outside of Engineering; only CSV is acceptable!
; Let's translate to CSV and suggest usernames while we're at it.
;
; This uses Drake's experimental Clojure-based c4 protocol, which
; knows how to automagically travel between TSV, CSV, JSON formats.
;
for_HR.csv <- people.json [c4row]
  (let [[fname lname] (str/split (row "name") #" ")]
    (assoc row :uname (str (first fname) lname)))

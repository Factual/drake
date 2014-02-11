DATE1:=$(sleep 1; python -c "import datetime; print datetime.datetime.now()")
DATE2:=$(sleep 1; python -c "import datetime; print datetime.datetime.now()")

date1 <-
  echo $DATE1 > date1

date2 <-
  echo $DATE2 > date2

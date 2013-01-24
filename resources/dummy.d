; our base data directory
BASE=./all/your/base

A.txt <- static0.txt
  cmdA

B.txt <- static1.txt
  cmdB

C.txt <- A.txt, B.txt
  cmdC

D.txt <- C.txt
  cmdD

E.txt <- B.txt
  cmdE

F.txt <- E.txt
  cmdF

G.txt <- E.txt
  cmdG

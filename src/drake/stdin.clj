(ns drake.stdin
  "WARNING
  ------------------------------------------------------------------------
  It is important that all of Drake code reads stdin only through the function
  read-line-stdin/process-line-stdin provided below.
  ------------------------------------------------------------------------

  Java's runtime launches child processes with streams attached to its
  stdin, stdout and stderr. While even convenient for our purposes in case
  of stdout and stderr, it turns out to be a bit nightmarish when it comes
  to stdin. It seems to be impossible to start processes that inherit our
  own stdin from Java (at least without JNI and an exec syscall).

  We need to copy our own stdin to the child process', but Java's InputStreams
  are non-interruptible, e.g. when they block on user's input, they block
  forever, and there's no way to get out of it when the child process exits.
  Java's new IO (java.nio) provides channels which are interruptable, but
  interrupting a read from a channel is only possible by closing it which means
  that if Drake has two steps both of which read from stdin, by the time the
  second step starts, Drake's stdin will be closed and nobody (nor the step, nor us)
  will be able to read from it. What we need is to be able to interrupt a
  read from stdin (when the child process exits) without closing it.

  The solution below is to decouple reading from stdin from consuming the data.
  When Drake starts, it launches an agent which reads one line from stdin. When
  a line is queried (whether by Drake itself or during relaying D's stdin to the
  child process), it waits for the agent readiness, gets the line, and sends
  another message to the agent to resume reading. It allows the thread that
  waits for the agent readiness (using await) to be interrupted from the outside
  without affecting stdin since the actual reading is going on in another
  thread.

  Even though with all that trickery, things might get problematic when Drake
  gets its input redirected from a file and runs several steps. The problem
  is that after a child process dies, some writes to its stdin could still
  go through and there's no way to know for sure. If a child process inherited
  our own stdin, there would be no problem since it wouldn't read more than
  it needs. But as we are reading (and relating) input for it, we will read
  as long as we can write, and that might happen even after the process is
  dead. As a consequence, if you feed a multi-line file to Drake's stdin,
  the first step might get all of it (even if it actually reads only one line).
"
  (:import (java.util.concurrent Exchanger)))

;; when two threads call this function, each is returned the object that the other passed in.
;; the first thread blocks until the second is ready.
(let [exchanger (Exchanger.)]
  (defn- exchange [x]
    (.exchange exchanger x)))

;; start a thread reading stdin forever, and make it a daemon so that it stops when no other
;; threads are running (and thus nobody will ever want its results).
(def ^:private reader (delay (doto (Thread. #(while true
                                               (exchange (read-line))))
                               (.setDaemon true)
                               (.start))))

(defn process-line-stdin
  "Reads one line from stdin, calls func on it and returns what func
   returned."
  [func]
  @reader ;; to make sure the thread is started
  (func (exchange nil)))

(defn ^String read-line-stdin
  "Reads one line from stdin."
  []
  (process-line-stdin identity))

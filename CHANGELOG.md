## 0.1.3

 * Default workflow filename changed from workflow.d to Drakefile
 * Significant parsing speed up for large files (>x10)
 * ```BASE``` variable:
  * bugfix: wasn't being picked up if set through environment
  * bugfix: wasn't correctly working with ```:=``` ([#14](https://github.com/Factual/drake/issues/14))
  * ```--base``` command-line flag supported
 * Added preference for getting Hadoop config location from HADOOP_HOME environment variable. Addresses [#35](https://github.com/Factual/drake/issues/35)
 * Added support for S3 (thanks howech) ([#60](https://github.com/Factual/drake/pull/60))

## 0.1.2

 * ```=``` are now allowed in filenames
 * ```:``` are now allowed in filenames (Drake will default to local file system instead of issuing "invalid filesystem" error, i.e. ```bad:name``` -> ```file:bad:name```)
 * CLI changes:
  * CLI is now getopt-compliant (i.e. one could do ```drake -aw my-workflow.d```)
  * -d doesn't work any more for debugging info: only --debug (-d reserved for future use)
  * --debug prints much less info now, --trace added for more verbose output
  * Added checking for conflicting options (i.e. --preview vs --print).
  * Help is now printed nicely
 * Added --step-delay to specify the amount of time, in milliseconds, to wait after each step. Should help with [desynchronized filesystems](https://github.com/Factual/drake/issues/15).
 * bugfixes: [#34](https://github.com/Factual/drake/issues/34)

## 0.1.1

 * Drake now works with [Drip](https://github.com/Factual/drake/wiki/Faster-startup:-Drake-with-Drip) which allows to bring down startup time essentially to zero in most cases.
 * Added support for R ("R" protocol). See [resources/regtest/regtest_interpreters.d](https://github.com/Factual/drake/blob/develop/resources/regtest/regtest_interpreters.d) for usage example.
 * Added ```--preview``` command-line option, which prints the same report of targets predicted to be built, but immediately exits afterwards instead of asking for user confirmation.
 * You can now [use Drake from Clojure REPL](https://github.com/Factual/drake/wiki/Drake-on-the-REPL) by calling ```run-workflow``` function.

## 0.1.0

 * Initial release!

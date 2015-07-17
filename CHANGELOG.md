## 1.0.1
### New Features
* Optional input files [#181](https://github.com/Factual/drake/pull/181)

### Bug fixes
* drake.log was sometimes put in the wrong directory [#183](https://github.com/Factual/drake/issues/183)

## 1.0.0
### New Features
* Support symbolic link even if DRAKE_HOME is not defined
* Overhauled `drake` script to support install and upgrades

### Maintenance / Generic Improvements
* Trimmed dependencies: Removed Factual API features; Upgraded to c4 version 0.2.1 which dropped 3rd party API dependencies.
* Upgraded to Clojure 1.6.0

## 0.1.7
### New Features
* Created core/DEFAULT-TARGETV constant; use in clj_frontend
* Added `with-ns` macro to support c4 runtime dependency resolution
* Added support for quoting filenames, to permit filenames that otherwise look like drake rules
* Added `--var x=y --var a=b` syntax, as better version of `--vars x=y,a=b`
* Added `--graph` option to help visualize a workflow
* Added [handy drake script](https://github.com/Factual/drake/blob/master/bin/drake) for convenience and better customized hadoop client version.

### Bug Fixes
* Allow unindented blank lines in step definitions, as per [#72](https://github.com/Factual/drake/issues/129)
* BASE var default is now the workflow directory rather than empty string, as per [#148](https://github.com/Factual/drake/issues/148)
* Prefixed (HDFS, S3) paths are now treated as absolute/off-base, as per [#157](https://github.com/Factual/drake/issues/157)
* Fix some bugs related to parsing, and to over-optimistically running shell expansions

### Maintenance / Generic Improvements
* Made it easier to run drake from inside a clojure repl/nrepl session (removing System/exit calls)
* Generally improved error-message output, esp. removing repetitive or useless spam and adding line/column number to parse errors
* Upgraded to Clojure 1.6
* Numerous performance improvements, which may actually be noticeable on large workflows

## 0.1.6

* Add [a Clojure frontend](https://github.com/Factual/drake/wiki/A-Clojure-Frontend-to-Drake) (thanks morrifeldman)
* bugfix: [#129](https://github.com/Factual/drake/issues/129) (thanks calfzhou)

## 0.1.5

* bugfix: [#98](https://github.com/Factual/drake/issues/98) --help now doesn't run workflow (thanks marshallshen)
* Upgrade to c4 0.2.0, which no longer bundles the Facebook API
* Basic functionality working for Windows sytems, specifically, Windows 8/command shell.
* bugfix: [#11](https://github.com/Factual/drake/issues/111) FileSystem plugins get wired up properly (thanks derenrich)
* Initial fix for [#118](https://github.com/Factual/drake/issues/118) to handle quotes better in shell commands  (thanks myronahn)
* Fixes and documentation for core.run-workflow
* Add node (Javascript) protocol (thanks arowla)
* Upgrade c4 to version 0.2.0, which drops bundling of Facebook Places API support
* Supprot for command line var regex

## 0.1.4

 * Added support for async execution of steps via --jobs (thanks guillaume and myronahn). See [Async Execution of Steps](https://github.com/Factual/drake/wiki/Async-Execution-of-Steps)
 * Added support for plugins via --plugins. See [Plugins wiki page](https://github.com/Factual/drake/wiki/Plugins)
 * Internal cleanup of drake.fs design (thanks stanistan)

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

## 0.1.0

 * Initial release!

## 0.1.1

 * Drake now works with [Drip](https://github.com/Factual/drake/wiki/Faster-startup:-Drake-with-Drip) which allows to bring down startup time essentially to zero in most cases. 
 * Added support for R ("R" protocol). See [resources/regtest/regtest_interpreters.d](https://github.com/Factual/drake/blob/develop/resources/regtest/regtest_interpreters.d) for usage example.
 * Added ```--preview``` command-line option, which prints the same report of targets predicted to be built, but immediately exits afterwards instead of asking for user confirmation.
 * You can now [use Drake from Clojure REPL](https://github.com/Factual/drake/wiki/Drake-on-the-REPL) by calling ```run-workflow``` function.
 * You can download a pre-compiled JAR (see [README](https://github.com/Factual/drake/blob/develop/README.md)). But we're not promising to update it frequently, so if you want to use the latest version of Drake, please pull it from Git.

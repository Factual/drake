# Drake

<img src="https://lh6.googleusercontent.com/-ambIXyQ9iK8/UPj3E2_eqpI/AAAAAAAAACE/Ssf_jhok7fk/s800/drake-text-alpha-scaled-left-space.png" align="right"/>

Drake is a simple-to-use, extensible, text-based data workflow tool that organizes command execution around data and its dependencies. Data processing steps are defined along with their inputs and outputs and Drake automatically resolves their dependencies and calculates:

 * which commands to execute (based on file timestamps)
 * in what order to execute the commands (based on dependencies)

Drake is similar to _GNU Make_, but designed especially for data workflow management. It has HDFS support, allows multiple inputs and outputs, and includes a host of features designed to help you bring sanity to your otherwise chaotic data processing workflows.

## Installation

Drake is a Clojure project, so to build Drake you will need to have [leiningen](https://github.com/technomancy/leiningen).

Note that Drake has been tested under Linux and Mac OS X. We've not tested it on Windows.

### Clone the project:

```bash
$ git clone git@github.com:Factual/drake.git
$ cd drake
```

### Build the uberjar:

```bash
$ lein uberjar
```

### Run Drake from the jar

Once you've built the uberjar, you can run Drake like this:

```bash
$ java -jar drake.jar
```

You can pass in arguments and options to Drake by putting them at the end of the above command, e.g.:

```bash
$ java -jar drake.jar --version
```

### A nicer way to run Drake

We recommend you "install" Drake in your environment so that you can run it by just typing "drake". For example, you could have an executable script called `drake`, like this on your path:

```bash
#!/bin/bash
java -cp $(dirname $0)/drake.jar drake.core $@
```

Drake documentation refers to running Drake as "drake". If you are instead running the uberjar, just replace "drake" with "java -jar drake.jar" in the examples.

## Basic Usage

The [wiki](https://github.com/Factual/drake/wiki) is the home for Drake's documentation, but here are simple notes on usage:

To build a specific target (and any out-of-date dependencies, if necessary):

```bash
$ drake mytarget
```

To build a target and everything that depends on it (a.k.a. "down-tree" mode):

```bash
$ drake ^mytarget
```

To build a specific target only, without any dependencies, up or down the tree:

```bash
$ drake =mytarget
```

To force build a target:

```bash
$ drake +mytarget
```

To force build a target and all its downtree dependencies:

```bash
$ drake +^mytarget
```

To force build the entire workflow:

```bash
$ drake +...
```

To exclude targets:

```bash
$ drake ... -sometarget -anothertarget
```

By default, Drake will look for `./workflow.d`. The simplest way to run your workflow is to name your workflow file `workflow.d`, and make sure you're in the same directory. Then, simply:

```bash
$ drake
```

To specify the workflow file explicitly, use `-w` or `--workflow`. E.g.:

```bash
$ drake -w /myworkflow/my-fav-workflow.d
```

Use `drake --help` for the full list of options.

## Documentation, etc.

The [wiki](https://github.com/Factual/drake/wiki) is the home for Drake's documentation.

A lot of work went into designing and specifying Drake. To prove it, here's [the 60 page specification document](https://docs.google.com/document/d/1bF-OKNLIG10v_lMes_m4yyaJtAaJKtdK0Jizvi_MNsg/edit). It can be downloaded as a PDF and treated like a user manual.

There are annotated workflow examples in the demos directory.

There's a [Google Group for Drake](https://groups.google.com/forum/?fromgroups#!forum/drake-workflow)

If you like screencasts, check out this [Drake walk-through video](http://www.youtube.com/watch?v=BUgxmvpuKAs) recorded by Artem, Drake's primary designer:

<a href="http://www.youtube.com/watch?v=BUgxmvpuKAs">
  <img src="https://lh6.googleusercontent.com/-wOmqvTkHHk0/UQBnQaVcXJI/AAAAAAAAAC4/apFtmcPXCPQ/s800/Screen%2520Shot%25202013-01-23%2520at%25202.41.43%2520PM.png" width="320" height="195"/>
</a>

## HDFS Compatibility

Drake provides HDFS support by allowing you to specify inputs and outputs like `hdfs://my/big_file.txt`.

If you plan to use Drake with HDFS, please see the [wiki doc on HDFS Compatibility](https://github.com/Factual/drake/wiki/HDFS-Compatibility).

## License

Source Copyright © 2012-2013 Factual, Inc.

Distributed under the Eclipse Public License, the same as Clojure uses. See the file COPYING.

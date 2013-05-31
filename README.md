# Drake

<img src="https://lh6.googleusercontent.com/-ambIXyQ9iK8/UPj3E2_eqpI/AAAAAAAAACE/Ssf_jhok7fk/s800/drake-text-alpha-scaled-left-space.png" align="right"/>

Drake is a simple-to-use, extensible, text-based data workflow tool that organizes command execution around data and its dependencies. Data processing steps are defined along with their inputs and outputs and Drake automatically resolves their dependencies and calculates:

 * which commands to execute (based on file timestamps)
 * in what order to execute the commands (based on dependencies)

Drake is similar to _GNU Make_, but designed especially for data workflow management. It has HDFS support, allows multiple inputs and outputs, and includes a host of features designed to help you bring sanity to your otherwise chaotic data processing workflows.

## Drake walk-through



If you like screencasts, check out this [Drake walk-through video](http://www.youtube.com/watch?v=BUgxmvpuKAs) recorded by Artem Boytsov, Drake's primary designer:

<a href="http://www.youtube.com/watch?v=BUgxmvpuKAs">
  <img src="https://lh6.googleusercontent.com/-wOmqvTkHHk0/UQBnQaVcXJI/AAAAAAAAAC4/apFtmcPXCPQ/s800/Screen%2520Shot%25202013-01-23%2520at%25202.41.43%2520PM.png" width="320" height="195"/>
</a>


## Installation

You will need to have a JVM installed. Drake has been tested under Linux and Mac OS X. We've not tested it on Windows.

### Download or build the uberjar

You can build Drake from source, which is the preferred way to run the most up-to-date version, or you can [download a prebuilt uberjar](https://docs.google.com/uc?export=download&confirm=no_antivirus&id=0B2xtKcFEL6wwQndXRldiakZKUjg), which may not be the most recent version of Drake.

Following are instructions for building from source. Drake is a Clojure project, so you will need to have [leiningen](https://github.com/technomancy/leiningen).

#### Clone the project:

```bash
$ git clone git@github.com:Factual/drake.git
$ cd drake
```

#### Build the uberjar:

```bash
$ lein uberjar
```

### Run Drake from the uberjar

Once you've built or downloaded the uberjar, you can run Drake like this:

```bash
$ java -jar drake.jar
```

You can pass in arguments and options to Drake by putting them at the end of the above command, e.g.:

```bash
$ java -jar drake.jar --version
```

### A nicer way to run Drake

We recommend you "install" Drake in your environment so that you can run it by just typing "drake". Here's a convenience script you can put on your path:

```bash
#!/bin/bash
java -cp $(dirname $0)/drake.jar drake.core "$@"
```

Save that as `drake`, then do `chmod 755 drake`. Move the uberjar to be in the same directory. Now you can just type `drake` to run Drake from anywhere.

### Faster startup time

The JVM startup time can be a nuisance. To reduce startup time, we recommend using the way cool [Drip](https://github.com/flatland/drip). Please see the [Drake with Drip](https://github.com/Factual/drake/wiki/Faster-startup:-Drake-with-Drip) wiki page.

## Basic Usage

Drake documentation refers to running Drake as "drake". If you are instead running the uberjar, just replace "drake" with "java -jar drake.jar" in the examples.

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

By default, Drake will look for `./Drakefile`. The simplest way to run your workflow is to name your workflow file `Drakefile`, and make sure you're in the same directory. Then, simply:

```bash
$ drake
```

To specify the workflow file explicitly, use `-w` or `--workflow`. E.g.:

```bash
$ drake -w /myworkflow/my-workflow.drake
```

Use `drake --help` for the full list of options.

## Documentation, etc.

The [wiki](https://github.com/Factual/drake/wiki) is the home for Drake's documentation.

A lot of work went into designing and specifying Drake. To prove it, here's [the 60 page specification and user manual](https://docs.google.com/document/d/1bF-OKNLIG10v_lMes_m4yyaJtAaJKtdK0Jizvi_MNsg/edit). It's stored in Google Docs, and we encourage everyone to use its superb commenting feature to provide feedback. Just select the text you want to comment on, and click Insert -> Comment (Ctrl + Alt + M on Windows, Cmd + Option + M on Mac). It can also be downloaded as a PDF.

There are annotated workflow examples in the demos directory.

There's a [Google Group for Drake](https://groups.google.com/forum/?fromgroups#!forum/drake-workflow) where you can ask questions. And if you found a bug or want to submit a feature request, go to [Drake's GitHub issues page](https://github.com/Factual/drake/issues?sort=created&state=open).

## HDFS Compatibility

Drake provides HDFS support by allowing you to specify inputs and outputs like `hdfs:/my/big_file.txt`.

If you plan to use Drake with HDFS, please see the [wiki doc on HDFS Compatibility](https://github.com/Factual/drake/wiki/HDFS-Compatibility).

## Amazon S3 Compatibility

Thanks to [Chris Howe](https://github.com/howech), Drake now has basic compatibility with Amazon S3 by allowing you to specify
inputs and outputs like `s3://bucket/path/to/object`.

If you plan to use Drake with S3, please see the [wiki doc on S3 Compatibility](https://github.com/Factual/drake/wiki/S3-Compatibility).

## Drake on the REPL

You can use Drake from your Clojure REPL, via `drake.core/run-workflow`. Please see the [Drake on the REPL wiki page](https://github.com/Factual/drake/wiki/Drake-on-the-REPL) for more details.

## Stuff outside this repo

Thanks to [Lars Yencken](https://github.com/larsyencken), we now have [Vim syntax support](https://bitbucket.org/larsyencken/vim-drake-syntax) for Drake:

<img src="https://lh3.googleusercontent.com/-mqNpFqf7P0k/UQoXkpAqr1I/AAAAAAAAADU/U5zrvozVmzE/s400/image.png"/>

Courtesy of [@daguar](https://gist.github.com/daguar), an [alternative approach to installing Drake on Mac OS X](https://gist.github.com/daguar/5368778).

## License

Source Copyright © 2012-2013 Factual, Inc.

Distributed under the Eclipse Public License, the same as Clojure uses. See the file COPYING.

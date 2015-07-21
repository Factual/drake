# Drake

<img src="http://gdurl.com/2jhD" align="right"/>

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

Drake has been tested under Linux, Mac OS X and Windows 8. We've not tested it on other operating systems.

Drake installs itself on the first run of the `drake` shell script; there is no
separate install script.  Follow these instructions to install drake manually:

1. Make sure you have [Java](https://www.java.com) version 6 or later.
2. [Download the `drake` script from the `master` branch](https://raw.githubusercontent.com/Factual/drake/master/bin/drake)
 of this project.
3. Place the `drake` script on your `$PATH`. (`~/bin` is a good choice if it is on your path.)
4. Set it to be executable. (`chmod 755 ~/bin/drake`)
5. Run it (`drake`) 

### Homebrew

If you're on a Mac you can alternatively use [Homebrew](http://brew.sh/) to install Drake:
```
brew install drake
```

### Upgrade Drake

Starting with Drake version 1.0.0, once you have Drake installed you can easily upgrade your version of Drake by running `drake --upgrade`. The latest version of Drake will be downloaded and installed for you.

### Download or build the uberjar

You can build Drake from source or run from a prebuilt jar. [Detailed instructions](https://github.com/Factual/drake/wiki/Download-or-build-the-uberjar)


### Use Drake as a Clojure library

You can programmatically use Drake from your Clojure project by using [Drake's Clojure front end](https://github.com/Factual/drake/wiki/A-Clojure-Frontend-to-Drake). Your project.clj dependencies should include the latest Drake library, e.g.:

```clojure
[factual/drake "1.0.1"]
```

### Faster startup time

The JVM startup time can be a nuisance. To reduce startup time, we recommend using the way cool [Drip](https://github.com/flatland/drip). Please see [the Drake with Drip](https://github.com/Factual/drake/wiki/Faster-startup:-Drake-with-Drip) wiki page.

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

## Visualize your workflow
See more [detail](https://github.com/Factual/drake/wiki/Visualize-your-workflow)

<img src="https://cloud.githubusercontent.com/assets/855457/7533038/509e37f8-f5a0-11e4-8c2e-8951272811af.png"/>

## Asynchronous Execution of Steps

Please see [the wiki page on async](https://github.com/Factual/drake/wiki/Async-Execution-of-Steps).

## Plugins

Drake has a plugin mechanism, allowing developers to publish and use custom plugins that extend Drake. See the [Plugin wiki page](https://github.com/Factual/drake/wiki/Plugins) for details.

## HDFS Compatibility

Drake provides HDFS support by allowing you to specify inputs and outputs like `hdfs:/my/big_file.txt`.

If you plan to use Drake with HDFS, please see [the wiki page on HDFS Compatibility](https://github.com/Factual/drake/wiki/HDFS-Compatibility).

## Amazon S3 Compatibility

Thanks to [Chris Howe](https://github.com/howech), Drake now has basic compatibility with Amazon S3 by allowing you to specify
inputs and outputs like `s3://bucket/path/to/object`.

If you plan to use Drake with S3, please see [the wiki doc on S3 Compatibility](https://github.com/Factual/drake/wiki/S3-Compatibility).

## Drake on the REPL

You can use Drake from your Clojure REPL, via `drake.core/run-workflow`. Please see [the Drake on the REPL wiki page](https://github.com/Factual/drake/wiki/Drake-on-the-REPL) for more details.

## Stuff outside this repo

Thanks to [Lars Yencken](https://github.com/larsyencken), we now have [Vim syntax support](https://bitbucket.org/larsyencken/vim-drake-syntax) for Drake:

<img src="https://lh3.googleusercontent.com/-mqNpFqf7P0k/UQoXkpAqr1I/AAAAAAAAADU/U5zrvozVmzE/s400/image.png"/>

Also thanks to [Lars Yencken](https://github.com/larsyencken), [utilities for making life easier in Python with Drake workflows](https://pypi.python.org/pypi/drakeutil).

Courtesy of [@daguar](https://gist.github.com/daguar), an [alternative approach to installing Drake on Mac OS X](https://gist.github.com/daguar/5368778).

[Original blog post](http://blog.factual.com/introducing-drake-a-kind-of-make-for-data) announcing Drake's open source release

[An epic knock-down-drag-out set of threads on Hacker News](https://news.ycombinator.com/item?id=5110921) discussing the design merits of Drake



## License

Source Copyright © 2012-2015 Factual, Inc.

Distributed under the Eclipse Public License, the same as Clojure uses. See the file COPYING.

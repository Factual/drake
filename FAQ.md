# FAQ

### How is Drake different than my favorite tool, X?

We may not know X, but if you're using it we bet it's a great tool! What we
can share with you is that we put a lot of thought and work into building
features in Drake that are specialized for today's modern data workflow
processing problems. In our experience these features are generally hard
to achieve, or downright lacking, in many popular tools:

* multiple outputs
* no-input and no-output steps
* HDFS support
* S3 support
* precise control over step execution
* target exclusions
* flexible interpreter abstractions - e.g., inline Python, R, Ruby, Clojure
* tags
* branching
* methods

Please see [the full Drake specification](https://docs.google.com/document/d/1bF-OKNLIG10v_lMes_m4yyaJtAaJKtdK0Jizvi_MNsg/edit) for a better understanding of the
features we've chosen to focus on.

All that said, if you've found a tool that is more ideal for your purposes,
far be it from us to try to make you use Drake. Keep on rockin'!

### How is Drake different than Make?

Drake has some basic similarities to Make, since Drake's design was largely
inspired by Make's basic approach to dependency resolution. However,
Drake is targeted squarely at the domain of data workflow management,
as opposed to software project build management. Drake has specific features
related to today's data processing challenges that you won't find in Make:

* HDFS support
* S3 support
* Easy handling of multiple outputs from a single step
* Mulitple language support inline, e.g. Ruby, Python, Clojure
* Integration with common data analysis tools, e.g. R
* Detailed reporting

Of course, if you find Make to be the best solution for your problem, that's
great! As always, YMMV.

### Doesn't the slow JVM startup time drive Drake users crazy?

It did, before we [learned to use Drip](https://github.com/Factual/drake/wiki/Faster-startup:-Drake-with-Drip)!

### Can I pass variables into a Drake workflow?

Yes, Drake lets you define workflow variables which can be used just about
anywhere in your workflow, such as to dynamically define target names, etc.

Default values can be defined in your workflow, and you can override them on the
command line when you call Drake. You can also specify values via the command line environment.

See the [Variables wiki page](https://github.com/Factual/drake/wiki/Variables) for more details.

### How can I use Drake as a Clojure library or on my Clojure REPL?

Check out the [Drake on the REPL wiki page](https://github.com/Factual/drake/wiki/Drake-on-the-REPL),
and have fun in Clojure-land!

### How can I use Drake as a library from Java?

You can use the `run-opts` function from drake.core, which is exposed as a public static method. But please be aware that _you're going the wrong way_.

### Why do I get an `ERROR java.io.IOException` when I try using Drake with HDFS?

It could be that your build of Drake is not using a Hadoop client library that
is compatible with your cluster. Please see [the wiki entry on HDFS compatibility](https://github.com/Factual/drake/wiki/HDFS-Compatibility).

### Why doesn't Drake recognize I have up-to-date targets in HDFS?

Drake looks for Hadoop configuration in `/etc/hadoop/conf/core-site.xml`. It
may be that in your environment, you keep your Hadoop configuration elsewhere.
Try copying or symlinking your Hadoop configuration to `/etc/hadoop/conf/core-site.xml`.

### Why is Drake written in Clojure?

We love Clojure at Factual. Lisp is an extremely powerful language, and
Clojure brings this power to the practical JVM world.

Clojure is quite good when working with lists and graphs -- a huge part of Drake's requirements.

Clojure has full Java interop, making all Java libraries available to us. Plus the Clojure community spits out libraries
like crazy. For example, take a look at [fnparse](https://github.com/joshua-choi/fnparse),
which we use in Drake for parsing.

However, we don't expect **you** to love Clojure, or Lisp, or want to
work in it. Drake will happily support any language you can dream of, as long
your scripts can be called via the shell. And Drake includes inline support
for a variety of languages besides Clojure, including Ruby, Python, and R.

### How can I contribute to the Drake project?

Thanks for asking! We are actively interested in code submissions, feature suggestions, bug reports,
documentation, and any other kind of help whatsoever. Please feel free to send us pull requests or
file a ticket through the main github repo.

And if you have a Drake success story to tell, please by all means share it with us!

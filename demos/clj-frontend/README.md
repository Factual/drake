# clj-frontend

A demonstration leiningen project showing how to use drake.clj-frontend.

## Usage

Run `lein repl` from within the root directory of this project to start a
repl and interact with the workflows found in the `clj.frontend.demo`
namespace in `/src/clj_frontend/demo.clj`.

For example, try these commands at the repl.

```clojure
(run-workflow minimal-workflow :preview true)

(run-workflow minimal-workflow)

(run-workflow advanced-workflow :preview true)

(run-workflow advanced-workflow)

(run-workflow reduce-workflow :preview true)
```

## License

Copyright © 2014

Distributed under the Eclipse Public License either version 1.0.

# Contributing

## Why Clojure?

This project uses [Clojure](https://clojure.org/) for several reasons:

- **REPL-driven development** - Allows for faster reloading of the app after making changes during development 
- **JVM based** - for the most part Java networking libraries are fast enough for UDP/IDN protocol streaming
- **Concurrency primitives** - The clojure `(atom)` makes managing state easier in multithreaded stuff bc you don't have to think about locking your state while you edit it  
- **Lack of syntactic sugar** - Clojure being less verbose than other languages makes it easier for most people to read. Also nowadays alot of code is LLM assisted, and a language that is less verbose is easier for an LLM to read too. 

- **pure data vs Objects** - Probably the most important bc it makes many things less complicated. 
    - App configuration data can be stored on disk the exact same way it's represented in memory, this can make testing easier to configure
    - Allows for simpler logic for copy pasting things within the app, since we don't have to deal with handling objects and references, no serializing objects, 
    - No need for a complex translation layer when exporting data outside of the app


## Learning Clojure

If you're new to Clojure, here are some helpful resources:

- [Clojure Official Guide](https://clojure.org/guides/getting_started) - Getting started guide
- [Clojure for the Brave and True](https://www.braveclojure.com/) - Free online book, great for beginners
- [ClojureDocs](https://clojuredocs.org/) - Community-powered documentation with examples
- [Clojure Style Guide](https://guide.clojure.style/) - Idiomatic Clojure patterns


## Development Setup

See [QUICKSTART.md](QUICKSTART.md) for project-specific setup instructions.

## Editor Setup

We recommend [VS Code](https://code.visualstudio.com/) with the [Calva](https://calva.io/) extension for Clojure development. Calva provides:

- There are already some default calva settings for vscode added for this project

# S2I build plugin for Jenkins

We would love to have this.

# Plugin development

## Environment

The following build environment is required to build this plugin

* `java-1.6` and `maven-3.0.5`

## Build

To build the plugin locally:

    mvn clean package

## Release

To release the plugin:

    mvn release:prepare release:perform -B

## Test local instance

To test in a local Jenkins instance

    mvn hpi:run

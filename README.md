# S2I build plugin for Jenkins

Recently we have enjoyed using [source-to-image](https://github.com/openshift/source-to-image) to build our Ruby on Rails Docker images. We wanted something that would allow Jenkins to build the images as well.

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

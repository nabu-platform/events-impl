# What is it

This package implements a simple dispatcher that fires everything synchronously.

Note: if the "source" object is a Class, you subscribe instead to all the sources of exactly that type.

# How to use

- For SPI: simply drop on the classpath
- For OSGi: registered with a service ranking of 0

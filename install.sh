#!/usr/bin/env bash

"/Applications/IntelliJ IDEA.app/Contents/MacOS/idea" \
ideScript \
"$(pwd)/install_plugin.groovy" \
"$(pwd)/$1"

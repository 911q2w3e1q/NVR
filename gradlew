#!/bin/sh
APP_HOME=$(cd "$(dirname "$0")" && pwd)
exec /root/.sdkman/candidates/gradle/9.3.0/bin/gradle -p "$APP_HOME" "$@"

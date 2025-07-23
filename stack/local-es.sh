#!/bin/sh
# Run with cwd being where the state needs to be.
# Pass one argument - the path to the ES launch script.

mkdir -p es-logs es-data

if [[ -z ${ES_JAVA_HOME} && -z ${JAVA_HOME} ]]; then
  export ES_JAVA_HOME=$(readlink -f $(dirname $(readlink -f $(which java)))/..)
  echo "Configured ES_JAVA_HOME: ${ES_JAVA_HOME}"
fi

$1\
 -Epath.data=$(pwd)/es-data\
 -Epath.logs=$(pwd)/es-logs\
 -Expack.security.enabled=false\
 -Expack.security.transport.ssl.enabled=false\
 -Ediscovery.type=single-node\
 -Eingest.geoip.downloader.enabled=false\
 -Ecluster.routing.allocation.disk.threshold_enabled=false
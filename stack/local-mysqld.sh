#!/bin/sh

# Run in a directory that has been initialized as the data directory.
# The recommendation is to run once, load TZ data, then stop, and pack the datadir to be able to start from scratch.
# --gdb to react to signals
# --default-time-zone could be passed to the script if MySQL is started with TZ data present.

mysqld --defaults-file=<(sed -e "s%DATA_DIR%$(pwd)%g" $(dirname ${0})/local-my.cnf) --gdb $*
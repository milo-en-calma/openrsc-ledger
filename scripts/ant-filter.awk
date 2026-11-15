# Filters what Ant prints while it runs the OpenRSC game server, for data/game-server.log.
#
#   awk -v default_log=<path> -v world_log=<path> -f ant-filter.awk
#
# Keeps every line until the server's own logging begins (the first "[java] <date> <time> "
# line), then a note naming the server's log files, then only Ant's own lines: those without
# "[java]", such as BUILD FAILED, and "[java] Java Result: <code>", the server's exit code,
# which Ant prints under the server's prefix. What the server prints from that point on is
# also in its log files, so it isn't kept twice. It reads to the end either way, so the
# server never writes into a closed pipe, and flushes each line so the file is always current.

!logging && /^ *\[java\] [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9] / {
    logging = 1
    print "ledger: the server has begun its own logging, in " default_log " and " world_log "; only Ant lines are kept here from now on."
    fflush()
}
logging && /^ *\[java\] / && !/^ *\[java\] Java Result: / { next }
{ print; fflush() }

# enhance CLI `doctor`

```shell
browser4-cli doctor                  # Show installation status and auto-clean stale daemon files
browser4-cli doctor --fix            # Also run destructive repairs (reinstall Chrome, purge old state, ...)
browser4-cli doctor log              # Show all log files (pulsar.log, pulsar.m.log, ...)
browser4-cli doctor log list         # Show all log files (pulsar.log, pulsar.m.log, ...)
browser4-cli doctor log <name>       # Show a specific log file (pulsar.log)
browser4-cli doctor log <name> --tail       # Show the last few lines of the log files (pulsar.log)
browser4-cli doctor log <name> grep <pattern>  # Show lines matching a pattern in the log files (pulsar.log)
```

The syntax of `grep` subcommand should be compatible with `snapshot grep`, `htmlsnapshot grep`.
It is good to extract a common grep function.

#auto-approve

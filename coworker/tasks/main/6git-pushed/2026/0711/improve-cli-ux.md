# Improve CLI ux

Start backend server only when it is really necessary to run the command.

For example:

1. do not start backend if the command is malformed/illegal which can be determined on the frontend.
2. do not start backend if the command depends on a web page but the server did not start, only print message to ask the user run `open` first
3. do not start backend if the command can be executed without it

```
PS D:\codebase\firecrawl> browser4-cli htmlsnapshot grep
Starting Browser4 server...
Using installed Browser4 runtime v4.11.19 from C:\Users\pereg\AppData\Roaming\browser4\runtime\v4.11.19.
Starting server from Browser4 runtime at C:\Users\pereg\AppData\Roaming\browser4\runtime\v4.11.19 using C:\Users\pereg\AppData\Roaming\browser4\runtime\v4.11.19\runtime\bin\java.exe on port 8182...
Browser4 startup log: C:\Users\pereg\AppData\Local\Temp\browser4\browser4-cli\tmp\cli\browser4-server-jar-port8182-20260711T174658.876Z.log
Waiting for Browser4 server at http://localhost:8182 (7s elapsed, ~113s remaining): TCP port not open yet, JVM may still be loading...
Waiting for Browser4 server at http://localhost:8182 (17s elapsed, ~103s remaining): TCP port not open yet, JVM may still be loading...
Server is up and running in 28.5s. Startup log: C:\Users\pereg\AppData\Local\Temp\browser4\browser4-cli\tmp\cli\browser4-server-jar-port8182-20260711T174658.876Z.log
Error: Pattern is required. Provide a positional pattern, or use -e PATTERN (repeatable) for multiple patterns.
PS D:\codebase\firecrawl> browser4-cli htmlsnapshot grep --help
```

#auto-approve

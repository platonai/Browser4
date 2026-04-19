# Daily Memory - 2026-03-02

## Tasks

### Finish script delete-copilot-branches
- **Goal**: Complete the script `bin/git/delete-copilot-branches.ps1` to delete branches starting with `copilot/`.
- **Outcome**: Successfully implemented the script to list and delete matching branches using `git branch -D`. Tested with a dummy branch.
- **Lessons**: When parsing `git branch` output, whitespace and current branch indicator (*) need to be handled carefully.

### Improve run-agent-examples
- **Goal**: Configure `browser4-examples` to be executable via `java -jar` and simplify `run-agent-examples.ps1`.
- **Outcome**: Added `maven-shade-plugin` to `examples/browser4-examples/pom.xml`. Removed failing `browser4-tests-common` dependency and excluded dependent source files (renamed to `.kt.bak`) to fix build. Successfully built the executable JAR. Updated `bin/run-agent-examples.ps1` to run the JAR using `java -jar`. Verified execution.
### Improve run-agent-examples (Redo/Fix)
- **Goal**: Reconfigure `browser4-examples` to use `spring-boot-maven-plugin` and properly include `browser4-tests-common` dependency.
- **Outcome**: Switched from `maven-shade-plugin` to `spring-boot-maven-plugin`. Successfully built and installed `browser4-tests-common` locally, resolving compilation errors without needing to exclude source files. Updated `bin/run-agent-examples.ps1` and created `bin/run-agent-examples.sh` to reliably find and run the generated executable JAR. Cleaned up `.kt.bak` files from previous attempts.
- **Lessons**: When dependencies are missing from the reactor or local repo, installing them (`mvn install`) is often necessary before dependent modules can build. `spring-boot-maven-plugin` provides robust executable JAR creation even for non-Spring Boot applications.

### Improve check_links (from 1.md)
- **Goal**: Add filtering capabilities to `bin/tools/python/check_links.py` to selectively check files and links.
- **Outcome**: Modified `check_links.py` to add `--files`, `--ignore-files`, `--links`, and `--ignore-links` arguments. Implemented glob matching for file filtering and prefix matching for link filtering. Verified with test cases covering inclusion/exclusion of files and links.
- **Lessons**: Python's `fnmatch` module is useful for glob pattern matching. Adding filtering options to utility scripts significantly improves their usability in CI/CD pipelines.

### Safe Script Mover
- **Goal**: Implement `bin/tools/python/move-scripts.py` to safely move `.ps1` and `.sh` scripts and update references.
- **Outcome**: Created the python script. It moves files from source to destination, appending a timestamp if a file already exists in the destination. It then scans the repository and updates references (relative paths) to the moved scripts, handling both forward and backward slashes.
- **Lessons**: When refactoring file locations, updating references requires careful handling of relative paths and path separators to ensure cross-platform compatibility. String replacement of relative paths is a safe heuristic for updating references in scripts and configuration files.


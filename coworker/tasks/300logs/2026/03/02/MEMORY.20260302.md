# Daily Memory - 2026-03-02

## Tasks

### Finish script delete-copilot-branches
- **Goal**: Complete `bin/git/delete-copilot-branches.ps1` to delete `copilot/*` branches.
- **Outcome**: Implemented branch deletion using `git branch -D`. Handled whitespace and current branch parsing.

### Improve run-agent-examples
- **Goal**: Configure `browser4-examples` as executable JAR and update run scripts.
- **Outcome**: Switched to `spring-boot-maven-plugin`. Resolved `browser4-tests-common` dependency issues by installing locally. Cleaned up `.kt.bak` files. Updated `bin/run-agent-examples.ps1` and created `bin/run-agent-examples.sh` to execute the JAR.
- **Lessons**: Local installation (`mvn install`) is crucial for missing reactor dependencies.

### Improve check_links
- **Goal**: Add filtering to `bin/tools/python/check_links.py`.
- **Outcome**: Added `--files`, `--ignore-files`, `--links`, `--ignore-links` args using `fnmatch`. Validated with test cases.
- **Lessons**: Filtering improves utility for CI/CD.

### Safe Script Mover
- **Goal**: Create `bin/tools/python/move-scripts.py` to move scripts and update refs.
- **Outcome**: Implemented script to move `.ps1`/`.sh` files, handle collisions (timestamp), and update relative path references in repo.
- **Lessons**: Relative path updates need careful separator handling for cross-platform support.

### Review count-total-token-usage
- **Goal**: Review and improve `coworker/scripts/workers/count-total-token-usage.py`.
- **Outcome**: Added `--detail` flag to show per-file token usage and cost breakdown. Improved regex robustness for log parsing.
- **Lessons**: Python regex for multi-line log parsing needs careful handling of whitespace.



### Review AI Software Factory Design Draft
- **Goal**: Evaluate the design draft for Clarity, Feasibility, and Completeness.
- **Outcome**: Reviewed 200plan/features/ai-architect/ai-software-factory-design.md. Created coworker/tasks/2working/1.feedback.md with detailed feedback. Rated Clarity and Feasibility as High. Identified missing details on code modification strategy and context gathering.
- **Lessons**: Directory-based state machines are robust for AI workflows but need explicit data flow definitions.

### Implement AI Software Factory
- **Goal**: Implement the directory structure, templates, and orchestrator script for the AI Software Factory design.
- **Outcome**: Created `coworker/scripts/orchestrator.ps1` (implements story pipeline), `coworker/tasks/100templates` (analysis, plan, implementation prompts), and `coworker/tasks/400artifacts`. Validated with a test story which successfully moved through the pipeline with mocked AI responses.
- **Lessons**: PowerShell function scoping (calling before definition or scope issues) needs care. Mocking `gh copilot` allows development of AI-driven scripts without API access.

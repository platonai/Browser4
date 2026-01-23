# Skill System Auto-Loading Feature

## Overview

The skill system has been optimized to automatically load skills when the application starts up. This eliminates the need for manual skill registration and ensures that all configured skills are available immediately when the system is ready.

## What Gets Loaded

When the system starts, the `SkillBootstrap` component automatically loads:

1. **Example Skills** (from code):
   - `WebScrapingSkill` - Extract data from web pages using CSS selectors
   - `FormFillingSkill` - Automatically fill web forms with provided data
   - `DataValidationSkill` - Validate data against specified rules

2. **Custom Skills** (from filesystem):
   - All skills defined in `AgentPaths.SKILLS_DIR` directory
   - Each skill must have a `SKILL.md` file with proper metadata

## Architecture

### SkillBootstrap Component

The `SkillBootstrap` class is a Spring `@Component` that uses `@PostConstruct` to initialize skills automatically on application startup.

**Location**: `pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/skills/SkillBootstrap.kt`

**Key Features**:
- Loads example skills with dependency resolution
- Attempts to load skills from the configured directory
- Gracefully handles errors and missing dependencies
- Provides detailed logging for troubleshooting

### Spring Configuration

The component is automatically discovered via Spring component scanning configured in:
`pulsar-agentic/src/main/resources/agentic-beans/app-context.xml`

```xml
<context:component-scan base-package="ai.platon.pulsar.agentic.skills"/>
```

## Usage

### For Application Developers

No action needed! Skills are automatically loaded when you create an `AgenticContext`:

```kotlin
val context = AgenticContexts.create()
// Skills are already loaded and ready to use
```

### For Skill Developers

To add a new example skill that loads automatically:

1. Create your skill class implementing the `Skill` interface or extending `AbstractSkill`
2. Add it to the `exampleSkills` list in `SkillBootstrap.loadExampleSkills()`:

```kotlin
private suspend fun loadExampleSkills(context: SkillContext) {
    val exampleSkills = listOf(
        WebScrapingSkill(),
        FormFillingSkill(),
        DataValidationSkill(),
        YourNewSkill()  // Add here
    )
    // ...
}
```

### For Custom Directory Skills

Place your skill definition in the skills directory:

```
$AGENT_BASE_DIR/agent/skills/
├── your-skill/
│   ├── SKILL.md          # Required: Skill metadata
│   ├── scripts/          # Optional: Executable scripts
│   ├── references/       # Optional: Documentation
│   └── assets/           # Optional: Templates/configs
```

The skill will be automatically discovered and loaded on next startup.

## Logging

The skill loading process produces detailed logs:

```
INFO  a.p.p.a.s.SkillBootstrap - Initializing skills system...
INFO  a.p.p.a.s.SkillBootstrap - Loading example skills...
INFO  a.p.p.a.s.SkillRegistry - ✓ Registered skill: Web Scraping (version 1.0.0)
INFO  a.p.p.a.s.SkillRegistry - ✓ Registered skill: Form Filling (version 1.0.0)
INFO  a.p.p.a.s.SkillRegistry - ✓ Registered skill: Data Validation (version 1.0.0)
INFO  a.p.p.a.s.SkillBootstrap - ✓ Loaded 3 example skills (3 succeeded, 0 failed)
INFO  a.p.p.a.s.SkillBootstrap - Loading skills from directory: /path/to/skills
INFO  a.p.p.a.s.SkillBootstrap - ✓ Skills system initialized successfully. Total skills: 3
```

## Error Handling

The `SkillBootstrap` gracefully handles various error conditions:

- **Dependency resolution failures**: Skills with unmet dependencies are logged but don't stop startup
- **Directory access issues**: If `AgentPaths.SKILLS_DIR` is not accessible, logs a warning and continues
- **Individual skill failures**: A failing skill doesn't prevent other skills from loading

## Testing

Unit tests for the auto-loading feature:
- `SkillBootstrapTest` - Tests the initialization and loading logic
- All existing skill tests continue to work with the new system

Run tests:
```bash
./mvnw -pl pulsar-agentic test -Dtest=SkillBootstrapTest
```

## Implementation Details

### Dependency Resolution

Skills are loaded in dependency order:
1. Skills with no dependencies are loaded first
2. Skills that depend on already-loaded skills are loaded next
3. Skills with unsatisfied dependencies are logged as failures

### Thread Safety

- `SkillRegistry` uses concurrent data structures and mutex locks
- Multiple skills can be loaded in parallel (when dependencies allow)
- The registry is thread-safe for concurrent execution

### Performance

- Skill loading happens once at startup
- Example skills load in < 100ms typically
- Directory scanning is lazy and only happens if the directory exists

## Future Enhancements

The current implementation logs directory-based skills but doesn't instantiate them. Future work:

1. **Skill Factory**: Dynamic instantiation of skills from definitions
2. **Hot Reload**: Watch for changes in the skills directory and reload
3. **Skill Marketplace**: Download and install skills from a registry
4. **Versioning**: Handle multiple versions of the same skill

## See Also

- [Skills Framework Documentation](skills-framework.md)
- [Creating Custom Skills Guide](custom-skills-guide.md)
- [Skill Definition Format](skill-definition-format.md)

# new CLI: `skills`

Manage skills in ./skills directory which currently contains only `browser4-cli` skills.

```shell
browser4-cli skills                  # List available skills
browser4-cli skills list             # Same as above
browser4-cli skills get <name>       # Output a skill's full content
browser4-cli skills get <name> --full  # Include references and templates
browser4-cli skills get --all        # Output every skill
browser4-cli skills path [name]      # Print skill directory path
```

Serves bundled skill content that always matches the installed CLI version. AI agents use this to get current instructions
rather than relying on cached copies. Set BROWSER4_SKILLS_DIR to override the skills directory path.

Skill files should be bundled into browser4-cli binary.

When call `browser4-cli install`, unpack the skill files to the versioned installation directory.

Update documents when you finish the coding and testing.

#auto-approve

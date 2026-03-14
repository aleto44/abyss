# Copilot Instructions

This document provides guidelines and instructions for using GitHub Copilot in the Abyss project.

## Purpose
- Keep Copilot suggestions aligned with this project's coding style, tooling, and workflow.

## Best Practices
- Modular Code: Write small, focused functions and split out classes to allow Copilot to generate more accurate suggestions.
- Always use explicit curly brackets for control flow statements (`if`, `else`, `for`, `while`, etc.). Do not use implied/optional curly brackets. For example, use `if (future != null) { future.cancel(false); }` instead of `if (future != null) future.cancel(false);`
- Default terminal commands to Git Bash syntax on Windows unless the user explicitly asks for a different shell. Use Git Bash-style paths and command chaining. For example: `cd '/c/Users/alexj/IdeaProjects/abyss' && ./gradlew.bat test`

## Code Readability & Boolean Clarity
- **Avoid short-circuit returns/guards by default**: Prefer not using early-return short-circuits unless doing so makes the overall method significantly easier to read as a whole. Favour explicit, structured control flow that reveals the full intent of a method.
- **Name your booleans**: Instead of embedding conditions directly in `if` statements or return expressions, extract them into explicitly named local boolean variables or boolean methods that describe what the condition *means*. This makes the intent self-documenting.
  ```java
  // Prefer:
  boolean isReadyToFire = cooldown <= 0 && hasAmmo && !isReloading;
  if (isReadyToFire) { fire(); }

  // Avoid:
  if (cooldown <= 0 && hasAmmo && !isReloading) fire();
  ```
- **Prefer named boolean methods over inline predicates**: When a condition is reused or complex, extract it into a clearly named method rather than repeating or inlining the logic.
  ```java
  // Prefer:
  boolean canAttack() { return cooldown <= 0 && hasAmmo && !isReloading; }
  if (canAttack()) { fire(); }

  // Avoid:
  if (cooldown <= 0 && hasAmmo && !isReloading) { fire(); }
  ```
- **Avoid large parameter lists (8+ parameters)**: Methods with 8 or more parameters are a strong signal that the method is doing too much or that related parameters should be grouped. Refactor by:
  - Grouping related data into a context object, record, or builder.
  - Splitting the method into smaller, more focused methods.
  - Moving parameters that belong to an object onto that object as fields.
  ```java
  // Avoid:
  void spawnEntity(float x, float y, float z, int health, int attack, int defense,
                   float speed, boolean hostile, EntityType type)

  // Prefer:
  record EntitySpawnParams(float x, float y, float z,
                           int health, int attack, int defense,
                           float speed, boolean hostile, EntityType type) {}
  void spawnEntity(EntitySpawnParams params)
  ```
---
*Update this document as project requirements evolve.*
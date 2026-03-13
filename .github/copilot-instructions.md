# Copilot Instructions

This document provides guidelines and instructions for using GitHub Copilot in the Abyss project.

## Purpose
- Keep Copilot suggestions aligned with this project's coding style, tooling, and workflow.

## Best Practices
- Modular Code: Write small, focused functions and split out classes to allow Copilot to generate more accurate suggestions.
- Always use explicit curly brackets for control flow statements (`if`, `else`, `for`, `while`, etc.). Do not use implied/optional curly brackets. For example, use `if (future != null) { future.cancel(false); }` instead of `if (future != null) future.cancel(false);`
- Default terminal commands to Git Bash syntax on Windows unless the user explicitly asks for a different shell. Use Git Bash-style paths and command chaining. For example: `cd '/c/Users/alexj/IdeaProjects/abyss' && ./gradlew.bat test`
---
*Update this document as project requirements evolve.*
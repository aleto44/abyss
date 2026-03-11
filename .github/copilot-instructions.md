# Copilot Instructions

This document provides guidelines and instructions for using GitHub Copilot in the Abyss project.

## Purpose
- Create a

## Best Practices
- Modular Code: Write small, focused functions and split out classes to allow Copilot to generate more accurate suggestions.
- Always use explicit curly brackets for control flow statements (`if`, `else`, `for`, `while`, etc.). Do not use implied/optional curly brackets. For example, use `if (future != null) { future.cancel(false); }` instead of `if (future != null) future.cancel(false);`

---
*Update this document as project requirements evolve.*

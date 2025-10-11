# Namespace Documentation Specification

This document defines the standard format for documenting Clojure
namespaces in the project.

## Overview

All namespaces should include structured documentation that helps
developers understand:
- What the namespace does (brief summary)
- What responsibilities it has in the system
- Its public interface design
- How it works internally (optional)
- What it depends on (optional)

## Template Structure

### Required Sections

Every namespace must include:

```clojure
(ns example.namespace
  "Brief one-line description of the namespace purpose.

  ## Responsibilities
  What this namespace is responsible for within the system. This should
  explain the namespace's role in the larger architecture."
  (:require ...))
```

### Optional Sections

For more complex namespaces, include additional sections as needed:

```clojure
(ns example.complex-namespace
  "Brief one-line description of the namespace purpose.

  ## Responsibilities
  What this namespace is responsible for within the system.

  ## API
  High-level description of the public interface. Focus on the conceptual
  API design rather than listing individual functions.

  ## Implementation Notes
  High-level specification of how the namespace works. Include important
  algorithms, data flow, or architectural decisions. Avoid low-level
  code details.

  ## Dependencies
  - `other.namespace` - Brief explanation of why this dependency exists
  - External Service X - Reason for external dependency
  - Database Y - What data this namespace manages"
  (:require ...))
```

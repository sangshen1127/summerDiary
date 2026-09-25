---
name: architect-core
description: Core architect orchestration skill for deciding system architecture, technology composition, and structural standards.
license: Apache-2.0
---

# architect-core

## Purpose

Use this skill whenever the user requests **system design**, **project architecture**, or explicitly asks for an **architect role**.

This skill acts as the **architecture decision maker and skill orchestrator**.
It does not implement concrete technologies, but decides *what* to use and *in what order*.

---

## Scope & Constraints

- ❌ Does NOT bind to any language, framework, or vendor
- ❌ Does NOT generate detailed code
- ✅ Makes high-level architectural decisions
- ✅ Selects and orchestrates downstream skills
- ✅ Defines structural and architectural constraints

---

## Inputs (Implicit)

This skill MUST extract or infer the following from user input:

- Project type (backend-only / frontend-backend split / full-stack)
- Architecture style (monolith / modular / microservices)
- Backend language or preference (if specified)
- Frontend framework or preference (if specified)
- Deployment expectations (local / containerized / cloud-ready)

If information is missing, make **reasonable defaults** and state them explicitly.

---

## Responsibilities

### 1️⃣ Architecture Decisions

- Decide architecture style:
  - Monolith
  - Modular Monolith
  - Microservices
- Decide frontend-backend relationship:
  - Backend only
  - Frontend-backend separation
  - BFF (Backend for Frontend)
- Enforce API-first and contract-driven design
- Define service boundaries (if microservices)

---

### 2️⃣ Skill Selection Rules

Based on decisions, select skills according to the following dimensions:

#### Backend
- `backend-python-fastapi`
- `backend-java-springboot`
- `backend-go-gin`
- `backend-node-nestjs`

#### Frontend
- `frontend-vue3`
- `frontend-react`
- `frontend-nextjs`

#### Infrastructure
- `infrastructure-docker`
- `infrastructure-microservice`
- `infrastructure-gateway`

---

### 3️⃣ Orchestration Order

When orchestrating, this skill MUST follow this order:

1. Architecture decision summary
2. Backend skill invocation
3. Frontend skill invocation (if applicable)
4. Infrastructure skill invocation (if applicable)
5. Optional CRUD / feature template delegation

---

## Architecture Principles

- Separation of Concerns
- API-First Design
- Layered / Clean Architecture
- Evolvability > Premature Optimization

---

## Output Expectations

This skill MUST output:

1. **Architecture Decision Summary**
   - Architecture style
   - Frontend-backend strategy
   - Key constraints

2. **Skill Invocation Plan**
   - Explicit list of downstream skills
   - Invocation order
   - Reason for each selection

3. **High-level Project Layout**
   - Top-level directory structure
   - Service boundaries (if any)

---

## Example Invocation

### Example 1 — Python Full-Stack Project

User:  
> “Design a Python frontend-backend separation project.”

Architect-core output:

- Architecture: Frontend-backend separation
- Backend: Python
- Frontend: Vue 3
- Infrastructure: Docker

Invoke skills in order:

1. `backend-python-fastapi`
2. `frontend-vue3`
3. `infrastructure-docker`

---

### Example 2 — Microservice System

User:  
> “Design a microservice backend system.”

Architect-core output:

- Architecture: Microservices
- Backend: To be selected per service
- Infrastructure: Required

Invoke skills:

1. `infrastructure-microservice`
2. Backend skill per service (language-specific)
3. Optional gateway skill

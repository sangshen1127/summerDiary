---
name: backend-java-springboot
description: Standard Spring Boot backend architecture with layered design, RESTful API-first principles, and enterprise-grade conventions.
---

# backend-java-springboot

## Purpose

Use this skill when implementing a **Java backend** using **Spring Boot** with clean / layered architecture principles.

This skill provides guidance for building **RESTful APIs** with:

* Clear layer boundaries
* Repository pattern
* DTO-based API contracts
* Production-ready configuration

It is suitable for **medium to large-scale systems**, and can evolve from **monolith → modular monolith → microservices**.

---

## Tech Stack

* **Language**: Java 17+
* **Framework**: Spring Boot 3.x
* **Web**: Spring Web (Spring MVC)
* **Persistence**: MyBatis-Plus
* **Validation**: Jakarta Validation (Hibernate Validator)
* **Database**: PostgreSQL (recommended) / MySQL
* **Migrations**: MyBatis-Plus migrations or Flyway
* **Security**: Spring Security (JWT-based)
* **Build Tool**: Maven (recommended) or Gradle
* **API Spec**: OpenAPI 3.0 (springdoc-openapi)

---

## Code Quality Standards

* **Java Version**: Java 17+
* **Formatting**: Google Java Style or Spotless
* **Static Analysis**: Checkstyle / SpotBugs / SonarLint
* **DTO Mandatory**: Controllers MUST NOT expose domain entities
* **Layer Isolation**: No cross-layer shortcuts
* **Testing**: JUnit 5 + Mockito + Spring Boot Test

---

## Standard Architecture

```text
backend/
├── src/main/java/com/example/app/
│   ├── Application.java          # Spring Boot entry point
│   ├── config/                   # Global configuration
│   │   ├── OpenApiConfig.java
│   │   ├── SecurityConfig.java
│   │   └── WebConfig.java
│   ├── api/                      # API layer (REST controllers)
│   │   └── v1/
│   │       ├── controller/        # REST controllers
│   │       │   └── {Entity}Controller.java
│   │       └── dto/               # Request / Response DTOs
│   │           ├── {Entity}Request.java
│   │           └── {Entity}Response.java
│   ├── domain/                   # Domain models (MyBatis entities)
│   │   └── {Entity}.java
│   ├── repository/               # Data access layer (MyBatis mappers)
│   │   └── {Entity}Mapper.java
│   ├── service/                  # Business logic layer
│   │   ├── {Entity}Service.java
│   │   └── impl/
│   │       └── {Entity}ServiceImpl.java
│   ├── exception/                # Custom exceptions & handlers
│   │   ├── BusinessException.java
│   │   └── GlobalExceptionHandler.java
│   └── util/                     # Utility classes
│       └── PageUtils.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   └── mapper/                   # MyBatis mapper XML files
├── src/test/java/
│   └── com/example/app/
│       ├── controller/
│       └── service/
├── pom.xml
└── Dockerfile
```

---

## Layer Responsibilities

### Controller (API Layer)

* Handle HTTP requests / responses
* Validate input using DTOs
* Call Service layer ONLY
* MUST NOT contain business logic

### Service (Business Layer)

* Implement business rules
* Coordinate multiple repositories
* Handle transactions
* Throw domain-specific exceptions

### Repository (Data Access Layer)

* Extend `BaseMapper` (MyBatis-Plus)
* Perform database CRUD and custom SQL queries
* MUST NOT contain business logic

### Domain (Entity Layer)

* MyBatis entities with annotations
* Database mapping only
* Minimal logic (no service calls)

---

## API Design Principles

* RESTful resource naming (`/users`, `/orders/{id}`)
* Versioned APIs (`/api/v1`)
* DTO-based request/response
* Consistent error response format

---

## Error Handling

Standard HTTP status mapping:

* `400 Bad Request` – validation failure
* `401 Unauthorized` – authentication required
* `403 Forbidden` – permission denied
* `404 Not Found` – resource missing
* `409 Conflict` – business constraint violation
* `500 Internal Server Error` – unexpected error

All exceptions MUST be handled in `GlobalExceptionHandler`.

---

## Templates

This skill provides reusable **CRUD scaffolding templates**.

```text
assets/templates/crud/
├── Entity.java.tmpl
├── Repository.java.tmpl
├── Mapper.xml.tmpl
├── Service.java.tmpl
├── ServiceImpl.java.tmpl
├── Controller.java.tmpl
├── RequestDTO.java.tmpl
└── ResponseDTO.java.tmpl
```

### Template Placeholders

* `{{Entity}}` – Entity name (PascalCase)
* `{{entity}}` – Variable name (camelCase)
* `{{table}}` – Database table name
* `{{fields}}` – Entity fields
* `{{package}}` – Base Java package

Templates are **optional** and intended for scaffolding only.

---

## Inputs

This skill expects the following context from `architect-core` or user:

* **Entity Name** (e.g. User, Order)
* **Database Type** (PostgreSQL / MySQL)
* **Authentication** (JWT / None)
* **Field Definitions** (name, type, constraints)
* **Relationships** (OneToMany, ManyToOne, etc.)

---

## Template Usage

### Available Templates

Located in `assets/templates/crud/`:

* `Entity.java.tmpl` – MyBatis entity with annotations
* `Repository.java.tmpl` – MyBatis mapper interface
* `Mapper.xml.tmpl` – MyBatis SQL mapping file
* `Service.java.tmpl` – Service interface
* `ServiceImpl.java.tmpl` – Service implementation
* `Controller.java.tmpl` – REST controller with OpenAPI docs
* `RequestDTO.java.tmpl` – Request DTO with validation
* `ResponseDTO.java.tmpl` – Response DTO with timestamps
* `deps.py` – Configuration and placeholder mapping

### Template Placeholders

| Placeholder | Description | Example |
|-------------|-------------|---------|
| `{{Entity}}` | PascalCase entity name | `User`, `Order` |
| `{{entity}}` | camelCase entity name | `user`, `order` |
| `{{entities}}` | Lowercase plural name | `users`, `orders` |
| `{{table}}` | Snake case table name | `user_table`, `order_table` |
| `{{package}}` | Base Java package | `com.example.app` |
| `{{fields}}` | Entity field definitions | `@TableField private String name;` |

### Quick Start Example

```bash
# Replace placeholders:
{{package}} → com.myapp
{{Entity}} → User
{{entity}} → user
{{entities}} → users
{{table}} → users
{{fields}} → @TableField private String email; @TableField private String name;
```

---

## Outputs

This skill produces:

1. Layered Spring Boot project structure
2. DTO-based REST controllers
3. Repository + Service implementation
4. CRUD-ready code templates
5. Configuration examples (YAML, security, OpenAPI)

---

## Usage Modes

### 1. Skeleton Mode (Default)

* Generate project structure only
* No CRUD generation
* Minimal configuration

### 2. CRUD Mode

* Generate full CRUD for specified entities
* Use templates under `templates/crud`

### 3. Production Mode

* Enable security configuration
* Enable migrations
* Add logging and exception handling

---

## Best Practices

1. **Controllers must stay thin** – Move all logic to services
2. **Never expose domain entities directly** – Always use DTOs
3. **Use DTOs for all APIs** – Request/Response separation
4. **Keep services transaction-bound** – Use `@Transactional` wisely
5. **Prefer constructor injection** – Use Lombok `@RequiredArgsConstructor`
6. **Version APIs from day one** – `/api/v1`, `/api/v2`, etc.
7. **Use Flyway for schema evolution** – Keep migrations in git
8. **Centralize exception handling** – Use `GlobalExceptionHandler`
9. **Audit entities** – Include `createdAt`, `updatedAt` timestamps
10. **Document APIs** – Use OpenAPI/Swagger annotations

---

## Common Patterns

### Pagination

```java
IPage<UserResponse> users = userService.getAll(new Page<>(1, 20));
```

### Validation

```java
@NotBlank(message = "Email is required")
@Email(message = "Invalid email format")
private String email;
```

### Exception Handling

```java
if (mapper.selectById(id) == null) {
    throw new BusinessException("User not found with id: " + id);
}
```

### Pagination Response

Use `IPage<DTO>` directly in responses – MyBatis-Plus automatically handles pagination metadata.

---

## Dependencies (Maven)

```xml
<!-- Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- MyBatis-Plus -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-boot-starter</artifactId>
    <version>3.5.4</version>
</dependency>

<!-- Validation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- Lombok -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <scope>provided</scope>
</dependency>

<!-- OpenAPI/Swagger -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.0.2</version>
</dependency>

<!-- PostgreSQL -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

---

## Notes

* This skill is framework-specific but **architecture-driven**
* Suitable for enterprise and long-lived systems
* Can be combined with `infrastructure-microservice` skill
* All templates are production-ready and follow Spring Boot conventions
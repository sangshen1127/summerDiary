---
name: infrastructure-docker
description: Standard Docker and containerization practices for backend and frontend services with development and production parity.
---

# infrastructure-docker

## Purpose

Use this skill when **containerizing applications** or **defining Docker-based development/production environments**.

This skill provides **opinionated but flexible Docker standards** to ensure:

* Consistent local development
* Predictable CI/CD behavior
* Production-ready container images

It applies to **backend services**, **frontend applications**, and **multi-service systems**.

---

## Scope

This skill covers:

* Dockerfile design
* Multi-stage builds
* Development vs production images
* docker-compose orchestration (local/dev)

This skill does **NOT** cover:

* Kubernetes (handled by a separate skill)
* Cloud-provider-specific deployment

---

## Design Philosophy

* Build once, run everywhere
* Small images, explicit dependencies
* One process per container
* Configuration via environment variables

---

## Base Images Policy

### Backend Services

| Language | Base Image                                       |
| -------- | ------------------------------------------------ |
| Python   | python:3.x-slim                                  |
| Java     | eclipse-temurin:17-jre                           |
| Go       | golang:1.x-alpine (build) + scratch/alpine (run) |

### Frontend Applications

* Build stage: node:18+
* Runtime stage: nginx:alpine

---

## Standard Dockerfile Patterns

### 1. Multi-Stage Build (Mandatory for Production)

* Separate build and runtime stages
* No compilers or build tools in runtime image
* Copy only required artifacts

---

### 2. Backend Dockerfile Template (Generic)

```
FROM build-image AS builder
WORKDIR /app
COPY . .
RUN build-command

FROM runtime-image
WORKDIR /app
COPY --from=builder /app/build /app
EXPOSE 8080
CMD ["./app"]
```

---

### 3. Frontend Dockerfile Template (SPA)

```
FROM node:18 AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

---

## Environment Configuration

* All configuration via environment variables
* No hard-coded secrets
* `.env` files allowed for local development only

---

## docker-compose Usage

Use docker-compose for:

* Local development
* Integration testing
* Service dependency simulation

### Recommended Services

* Application service
* Database (Postgres / MySQL)
* Cache (Redis) when required

---

### docker-compose.yml Structure

```
services:
  app:
    build: .
    ports:
      - "8080:8080"
    env_file:
      - .env
    depends_on:
      - db

  db:
    image: postgres:15
    environment:
      POSTGRES_DB: app
      POSTGRES_USER: app
      POSTGRES_PASSWORD: secret
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

---

## Dependency Rules

* One container = one responsibility
* No shared volumes between unrelated services
* App containers must not depend on local machine state

---

## Inputs

This skill expects:

* **Service Type** (backend / frontend)
* **Language/Framework** (Python / Java / Go for backend; Vue/React for frontend)
* **Runtime Port** (e.g., 8080, 3000)
* **External Dependencies** (PostgreSQL, Redis, etc.)
* **Base Images** (optional, uses defaults)

---

## Outputs

* **Dockerfile** (optimized for target runtime)
* **docker-compose.yml** (local development setup)
* **.dockerignore** (optimized build context)
* **Nginx configuration** (for frontend)
* **Health check configuration**

---

## Templates

Located in `assets/templates/docker/`:

### Backend Dockerfiles
| Template | Purpose |
|----------|---------|
| `Dockerfile.backend.python.tmpl` | Python FastAPI/Django (multi-stage, <200MB) |
| `Dockerfile.backend.java.tmpl` | Java Spring Boot (multi-stage, Maven) |
| `Dockerfile.backend.go.tmpl` | Go services (scratch, <50MB) |

### Frontend Templates
| Template | Purpose |
|----------|---------|
| `Dockerfile.frontend.tmpl` | Vue/React SPA with Nginx |
| `nginx.conf.tmpl` | Nginx main configuration |
| `default.conf.tmpl` | Nginx server configuration + SPA routing |

### Common Templates
| Template | Purpose |
|----------|---------|
| `docker-compose.yml.tmpl` | Complete local dev environment |
| `.dockerignore.tmpl` | Build context optimization |
| `deps.py` | Configuration and versions |

---

## Quick Start

### Backend Service (Python)
```bash
# Replace placeholders:
{{service_name}} → my-api
{{port}} → 8080
{{python_version}} → 3.11
{{db_name}} → myapp
{{db_user}} → appuser
{{db_password}} → (use .env)

# Build image
docker build -t my-api:latest .

# Run with compose
docker-compose up
```

### Frontend Application (Vue 3)
```bash
# Replace placeholders:
{{service_name}} → my-frontend
{{port}} → 3000 (dev) / 80 (prod)
{{node_version}} → 20
{{api_url}} → http://localhost:8080

# Build image
docker build -t my-frontend:latest .

# Run container
docker run -p 3000:3000 my-frontend:latest
```

---

## Image Size Targets

| Service Type | Target Size | Status |
|--------------|------------|--------|
| Python backend | < 200MB | ✓ Multi-stage |
| Java backend | < 300MB | ✓ JRE only |
| Go backend | < 50MB | ✓ Scratch/Alpine |
| Frontend SPA | < 100MB | ✓ Nginx Alpine |

---

## Best Practices

1. **Use multi-stage builds** – Separate build and runtime stages
2. **Keep images small** – No compilers or build tools in runtime
3. **Pin base image versions** – Never use `latest` in production
4. **Run as non-root user** – Always create app user
5. **Include health checks** – Let orchestrators detect failures
6. **Optimize layer caching** – Order Dockerfile commands strategically
7. **No secrets in images** – Use environment variables
8. **Match dev/prod behavior** – docker-compose mirrors production
9. **Minimal .dockerignore** – Only exclude necessary files
10. **One process per container** – Single responsibility

---

## Common Patterns

### Pattern 1: Multi-Stage Python Build
```dockerfile
FROM python:3.11-slim as builder
WORKDIR /app
COPY requirements.txt .
RUN pip install --user -r requirements.txt

FROM python:3.11-slim
COPY --from=builder /root/.local /root/.local
COPY . .
ENV PATH=/root/.local/bin:$PATH
CMD ["python", "main.py"]
```

### Pattern 2: Multi-Stage Java Build
```dockerfile
FROM maven:3.9-openjdk-17 as builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
COPY --from=builder /build/target/*.jar app.jar
CMD ["java", "-jar", "app.jar"]
```

### Pattern 3: Minimal Go Build
```dockerfile
FROM golang:1.21-alpine as builder
WORKDIR /build
COPY . .
RUN go build -o app .

FROM scratch
COPY --from=builder /build/app .
ENTRYPOINT ["./app"]
```

### Pattern 4: Frontend SPA
```dockerfile
FROM node:20 as builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
CMD ["nginx", "-g", "daemon off;"]
```

---

## Docker Compose Configuration

### Development Environment
```yaml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - DATABASE_URL=postgresql://user:pass@db:5432/myapp
    depends_on:
      - db
    volumes:
      - ./src:/app/src
    
  db:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: myapp
      POSTGRES_USER: user
      POSTGRES_PASSWORD: pass
```

### Running Compose
```bash
# Start all services
docker-compose up

# Run in background
docker-compose up -d

# View logs
docker-compose logs -f app

# Stop all services
docker-compose down

# Rebuild images
docker-compose build --no-cache
```

---

## Health Checks

### HTTP Health Check
```dockerfile
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:8080/health
```

### Database Health Check
```dockerfile
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
    CMD pg_isready -U postgres
```

### Custom Script
```dockerfile
COPY healthcheck.sh /
HEALTHCHECK CMD /healthcheck.sh
```

---

## Layer Caching Optimization

```dockerfile
# BAD: Copies everything, rebuilds on any change
COPY . .
RUN npm install

# GOOD: Dependencies cached separately
COPY package*.json ./
RUN npm install
COPY . .
```

---

## Non-Root User Setup

### Linux/Alpine
```dockerfile
RUN adduser -D -u 1000 appuser
USER appuser
```

### Debian/Ubuntu
```dockerfile
RUN useradd -m -u 1000 appuser
USER appuser
```

---

## Environment Variables

### Configuration via .env
```bash
# .env
DB_HOST=db
DB_USER=appuser
DB_PASSWORD=secret123
LOG_LEVEL=info
```

### Using in docker-compose
```yaml
services:
  app:
    env_file:
      - .env
    environment:
      NODE_ENV: development
```

### Using in Dockerfile
```dockerfile
ENV PYTHONUNBUFFERED=1
ENV LOG_LEVEL=info
```

---

## Networking

### Service Discovery
```yaml
services:
  app:
    depends_on:
      - db
    environment:
      DATABASE_URL: postgresql://user:pass@db:5432/myapp
```

Docker Compose automatically resolves `db` to the container's IP.

---

## Volumes

### Development Volume Mounting
```yaml
services:
  app:
    volumes:
      - ./src:/app/src
      - /app/node_modules
```

### Named Volumes for Persistence
```yaml
volumes:
  pgdata:

services:
  db:
    volumes:
      - pgdata:/var/lib/postgresql/data
```

---

## Security Practices

1. **Run as non-root** – Always use a non-root user
2. **No hardcoded secrets** – Use environment variables
3. **Use specific base image versions** – Never `latest`
4. **Keep images updated** – Rebuild regularly
5. **Minimize attack surface** – Use `-alpine` variants
6. **Security headers** – Add in Nginx for frontend

---

## Common Issues & Solutions

### Issue 1: Image size too large
**Solution:** Use multi-stage builds, remove build tools from runtime

### Issue 2: Slow image builds
**Solution:** Reorder Dockerfile commands to maximize cache

### Issue 3: Container exits immediately
**Solution:** Check logs with `docker logs <container>`, add health checks

### Issue 4: Port already in use
**Solution:** Change port mapping or stop conflicting container

### Issue 5: Database connection fails
**Solution:** Ensure `depends_on` and use container name as host

---

## Anti-Patterns to Avoid

1. ❌ Running multiple processes per container
2. ❌ Using `latest` tag (use specific versions)
3. ❌ Large images with build tools included
4. ❌ Hardcoding secrets into images
5. ❌ Running as root user
6. ❌ No health checks
7. ❌ Single-stage builds for production
8. ❌ Not using .dockerignore
9. ❌ Large build context
10. ❌ No resource limits

---

## Template Placeholders Reference

| Placeholder | Description | Example |
|-------------|-------------|---------|
| `{{service_name}}` | Container/service name | `my-api` |
| `{{port}}` | Exposed port | `8080` |
| `{{python_version}}` | Python version | `3.11` |
| `{{java_version}}` | Java version | `17` |
| `{{go_version}}` | Go version | `1.21` |
| `{{node_version}}` | Node version | `20` |
| `{{nginx_version}}` | Nginx version | `latest`, `1.25` |
| `{{postgres_version}}` | PostgreSQL version | `15` |
| `{{redis_version}}` | Redis version | `7` |
| `{{db_name}}` | Database name | `myapp` |
| `{{db_user}}` | Database user | `appuser` |
| `{{db_password}}` | Database password | Use .env file |
| `{{api_url}}` | Backend API URL | `http://api:8080` |
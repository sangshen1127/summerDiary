# Docker configuration and dependencies

SUPPORTED_BACKENDS = [
    "python",
    "java",
    "go",
]

SUPPORTED_FRONTENDS = [
    "vue3",
    "react",
]

BASE_IMAGES = {
    "python": {
        "slim": "python:{version}-slim",
        "alpine": "python:{version}-alpine",
    },
    "java": {
        "jdk": "eclipse-temurin:{version}-jdk",
        "jre": "eclipse-temurin:{version}-jre",
    },
    "go": {
        "alpine": "golang:{version}-alpine",
        "bookworm": "golang:{version}",
    },
    "node": {
        "alpine": "node:{version}-alpine",
        "slim": "node:{version}-slim",
    },
    "nginx": {
        "alpine": "nginx:{version}-alpine",
    },
}

DEFAULT_VERSIONS = {
    "python": "3.11",
    "java": "17",
    "go": "1.21",
    "node": "20",
    "nginx": "latest",
    "postgres": "15",
    "redis": "7",
    "maven": "3.9",
    "alpine": "3.18",
}

TEMPLATE_PLACEHOLDERS = {
    "{{service_name}}": "Service/container name (e.g., my-app)",
    "{{port}}": "Exposed port (e.g., 8080, 3000)",
    "{{python_version}}": "Python version (e.g., 3.11)",
    "{{java_version}}": "Java version (e.g., 17)",
    "{{go_version}}": "Go version (e.g., 1.21)",
    "{{node_version}}": "Node version (e.g., 20)",
    "{{nginx_version}}": "Nginx version (e.g., latest, 1.25)",
    "{{postgres_version}}": "PostgreSQL version (e.g., 15)",
    "{{redis_version}}": "Redis version (e.g., 7)",
    "{{alpine_version}}": "Alpine Linux version (e.g., 3.18)",
    "{{maven_version}}": "Maven version (e.g., 3.9)",
    "{{db_name}}": "Database name (e.g., myapp)",
    "{{db_user}}": "Database user (e.g., appuser)",
    "{{db_password}}": "Database password (NEVER hardcode in compose)",
    "{{api_url}}": "API backend URL (e.g., http://localhost:8080)",
}

SCAFFOLD_STRUCTURE_BACKEND_PYTHON = [
    "Dockerfile.backend.python.tmpl",
    "docker-compose.yml.tmpl",
    ".dockerignore.tmpl",
]

SCAFFOLD_STRUCTURE_BACKEND_JAVA = [
    "Dockerfile.backend.java.tmpl",
    "docker-compose.yml.tmpl",
    ".dockerignore.tmpl",
]

SCAFFOLD_STRUCTURE_BACKEND_GO = [
    "Dockerfile.backend.go.tmpl",
    "docker-compose.yml.tmpl",
    ".dockerignore.tmpl",
]

SCAFFOLD_STRUCTURE_FRONTEND = [
    "Dockerfile.frontend.tmpl",
    "nginx.conf.tmpl",
    "default.conf.tmpl",
    ".dockerignore.tmpl",
]

FILE_LOCATIONS = {
    "dockerfile": "Dockerfile",
    "docker_compose": "docker-compose.yml",
    "dockerignore": ".dockerignore",
    "nginx_conf": "docker/nginx.conf",
    "nginx_default": "docker/default.conf",
}

BEST_PRACTICES = {
    "image_size": {
        "backend_python": "< 200MB",
        "backend_java": "< 300MB",
        "backend_go": "< 50MB",
        "frontend": "< 100MB",
    },
    "health_checks": True,
    "non_root_user": True,
    "multi_stage_build": True,
    "layer_caching": True,
}

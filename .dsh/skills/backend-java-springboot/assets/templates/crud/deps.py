# Template dependencies and configuration for CRUD code generation

# This file defines the dependencies and configurations needed for CRUD scaffolding

MAVEN_DEPENDENCIES = {
    "web": "org.springframework.boot:spring-boot-starter-web",
    "mybatis_plus": "com.baomidou:mybatis-plus-boot-starter:3.5.4",
    "validation": "org.springframework.boot:spring-boot-starter-validation",
    "lombok": "org.projectlombok:lombok",
    "swagger": "org.springdoc:springdoc-openapi-starter-webmvc-ui:2.0.2",
    "database": "org.postgresql:postgresql",
}

TEMPLATE_PLACEHOLDERS = {
    "{{Entity}}": "Entity name in PascalCase (e.g., User, Order)",
    "{{entity}}": "Entity name in camelCase (e.g., user, order)",
    "{{entities}}": "Entity name in lowercase plural (e.g., users, orders)",
    "{{table}}": "Database table name in snake_case (e.g., user_table, order_table)",
    "{{package}}": "Base Java package (e.g., com.example.app)",
    "{{fields}}": "Entity field definitions with annotations",
    "{{result_mappings}}": "MyBatis result mappings for entity fields",
    "{{column_list}}": "Comma-separated column names for SQL",
    "{{insert_values}}": "INSERT values placeholders (#{field,jdbcType=TYPE})",
    "{{update_set}}": "UPDATE SET clauses for entity fields",
}

SCAFFOLD_STRUCTURE = [
    "Entity.java.tmpl",
    "Repository.java.tmpl",
    "Mapper.xml.tmpl",
    "Service.java.tmpl",
    "ServiceImpl.java.tmpl",
    "Controller.java.tmpl",
    "RequestDTO.java.tmpl",
    "ResponseDTO.java.tmpl",
]

NAMING_CONVENTIONS = {
    "controller_class": "{{Entity}}Controller",
    "service_interface": "{{Entity}}Service",
    "service_impl": "{{Entity}}ServiceImpl",
    "repository": "{{Entity}}Mapper",
    "mapper_xml": "{{Entity}}Mapper.xml",
    "request_dto": "{{Entity}}Request",
    "response_dto": "{{Entity}}Response",
    "entity": "{{Entity}}",
}

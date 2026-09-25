# Template dependencies and configuration for Vue 3 CRUD code generation

# NPM Dependencies for package.json
NPM_DEPENDENCIES = {
    "vue": "^3.4.0",
    "pinia": "^2.1.0",
    "vue-router": "^4.2.0",
    "axios": "^1.6.0",
    "zod": "^3.22.0",
}

# Element Plus specific dependencies
NPM_ELEMENT_PLUS_DEPENDENCIES = {
    "element-plus": "^2.4.0",
    "@element-plus/icons-vue": "^2.1.0",
}

# Tailwind CSS dependencies
NPM_TAILWIND_DEPENDENCIES = {
    "tailwindcss": "^3.3.0",
    "postcss": "^8.4.0",
    "autoprefixer": "^10.4.0",
}

NPM_DEV_DEPENDENCIES = {
    "typescript": "^5.0.0",
    "vite": "^5.0.0",
    "@vitejs/plugin-vue": "^5.0.0",
    "vitest": "^0.34.0",
    "@testing-library/vue": "^8.0.0",
    "@vue/test-utils": "^2.4.0",
}

TEMPLATE_PLACEHOLDERS = {
    "{{Entity}}": "Entity name in PascalCase (e.g., User, Product)",
    "{{entity}}": "Entity name in camelCase (e.g., user, product)",
    "{{entities}}": "Entity name in lowercase plural (e.g., users, products)",
    "{{package}}": "Base import path (e.g., @/)",
    "{{fields}}": "Entity field definitions (e.g., name: string; email: string;)",
    "{{table_columns}}": "Element Plus table columns (for Element Plus template)",
    "{{table_rows}}": "Tailwind table row cells (for Tailwind template)",
    "{{form_fields}}": "Form input fields for create/update",
}

SCAFFOLD_STRUCTURE_ELEMENT = [
    "types.ts.tmpl",
    "api.ts.tmpl",
    "store.ts.tmpl",
    "ElementTable.vue.tmpl",
    "ElementForm.vue.tmpl",
    "ElementView.vue.tmpl"
]

SCAFFOLD_STRUCTURE_TAILWIND = [
    "types.ts.tmpl",
    "api.ts.tmpl",
    "store.ts.tmpl",
    "TailwindTable.vue.tmpl",
    "TailwindForm.vue.tmpl",
    "TailwindView.vue.tmpl"
]

NAMING_CONVENTIONS = {
    "type_file": "{{entity}}.ts",
    "api_file": "{{entity}}.api.ts",
    "store_file": "{{entity}}.store.ts",
    "store_hook": "use{{Entity}}Store",
    "table_component": "{{Entity}}Table.vue",
    "form_component": "{{Entity}}Form.vue",
    "view_component": "{{Entity}}View.vue",
}

FILE_LOCATIONS = {
    "types": "src/types/{{entity}}.ts",
    "api": "src/api/{{entity}}.api.ts",
    "store": "src/stores/{{entity}}.store.ts",
    "table_component": "src/views/{{entity}}/components/{{Entity}}Table.vue",
    "form_component": "src/views/{{entity}}/components/{{Entity}}Form.vue",
    "view_component": "src/views/{{entity}}/{{Entity}}View.vue",
}

NAMING_CONVENTIONS = {
    "type_file": "{{entity}}.ts",
    "api_file": "{{entity}}.api.ts",
    "store_file": "{{entity}}.store.ts",
    "store_hook": "use{{Entity}}Store",
    "table_component": "{{Entity}}Table.vue",
    "form_component": "{{Entity}}Form.vue",
    "view_component": "{{Entity}}View.vue",
}

FILE_LOCATIONS = {
    "types": "src/types/{{entity}}.ts",
    "api": "src/api/{{entity}}.api.ts",
    "store": "src/stores/{{entity}}.store.ts",
    "table_component": "src/views/{{entity}}/components/{{Entity}}Table.vue",
    "form_component": "src/views/{{entity}}/components/{{Entity}}Form.vue",
    "view_component": "src/views/{{entity}}/{{Entity}}View.vue",
}

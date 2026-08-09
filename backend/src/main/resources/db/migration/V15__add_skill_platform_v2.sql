CREATE TABLE skill_definitions (
    id VARCHAR(128) PRIMARY KEY,
    owner_id CHAR(36) NULL,
    source VARCHAR(32) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    invocation_policy VARCHAR(32) NOT NULL,
    scene_types JSON NOT NULL,
    scope_types JSON NOT NULL,
    draft_version_id CHAR(36) NULL,
    published_version_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_skill_definition_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    KEY ix_skill_definition_catalog (owner_id, source, status),
    KEY ix_skill_definition_updated (owner_id, updated_at)
);

CREATE TABLE skill_versions (
    id CHAR(36) PRIMARY KEY,
    skill_definition_id VARCHAR(128) NOT NULL,
    version_number INT NOT NULL,
    version_name VARCHAR(64) NOT NULL,
    instructions MEDIUMTEXT NOT NULL,
    allowed_tools JSON NOT NULL,
    output_blocks JSON NOT NULL,
    should_trigger JSON NOT NULL,
    should_not_trigger JSON NOT NULL,
    default_prompt VARCHAR(1000) NULL,
    content_hash CHAR(64) NOT NULL,
    trigger_preview_passed BOOLEAN NOT NULL DEFAULT FALSE,
    trigger_preview_hash CHAR(64) NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_skill_version_definition FOREIGN KEY (skill_definition_id) REFERENCES skill_definitions(id),
    UNIQUE KEY uk_skill_version_number (skill_definition_id, version_number),
    UNIQUE KEY uk_skill_version_name (skill_definition_id, version_name),
    KEY ix_skill_version_definition (skill_definition_id, published_at)
);

CREATE TABLE skill_resources (
    id CHAR(36) PRIMARY KEY,
    skill_version_id CHAR(36) NOT NULL,
    resource_key VARCHAR(160) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    name VARCHAR(160) NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    markdown_content MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_skill_resource_version FOREIGN KEY (skill_version_id) REFERENCES skill_versions(id),
    UNIQUE KEY uk_skill_resource_key (skill_version_id, resource_key),
    KEY ix_skill_resource_version (skill_version_id, sort_order)
);

ALTER TABLE knowledge_runs
    ADD COLUMN skill_version_id CHAR(36) NULL AFTER skill_version,
    ADD KEY ix_knowledge_run_skill_version (skill_version_id);

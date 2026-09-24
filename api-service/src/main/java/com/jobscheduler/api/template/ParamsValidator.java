package com.jobscheduler.api.template;

import com.jobscheduler.api.exception.FieldViolation;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ParamsValidator {

    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<Integer, Schema> schemasByTemplateId = new ConcurrentHashMap<>();
    private final JsonMapper jsonMapper;

    public ParamsValidator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public List<FieldViolation> validate(int templateId, String paramsSchema, Map<String, Object> params) {
        // Safe to cache forever: a template version's schema never changes.
        Schema schema = schemasByTemplateId.computeIfAbsent(
                templateId, id -> schemaRegistry.getSchema(paramsSchema, InputFormat.JSON));

        JsonNode input = jsonMapper.valueToTree(params);
        return schema.validate(input, context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
                .stream()
                .map(ParamsValidator::toViolation)
                .toList();
    }

    private static FieldViolation toViolation(com.networknt.schema.Error error) {
        String location = error.getInstanceLocation().toString().replace('/', '.');
        String property = error.getProperty() == null ? "" : "." + error.getProperty();
        return new FieldViolation("params" + location + property, error.getMessage());
    }
}
package com.jobscheduler.worker.template;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TemplateRenderer {

    // Both compilers throw on a missing {{placeholder}} (JMustache's default).
    private final Mustache.Compiler htmlCompiler = Mustache.compiler();
    private final Mustache.Compiler plainTextCompiler = Mustache.compiler().escapeHTML(false);
    private final Map<Integer, CompiledTemplate> templatesById = new ConcurrentHashMap<>();

    public RenderedTemplate render(int templateId, String subject, String body, Map<String, Object> params) {
        CompiledTemplate compiled = templatesById.computeIfAbsent(templateId,
                id -> new CompiledTemplate(plainTextCompiler.compile(subject), htmlCompiler.compile(body)));
        return new RenderedTemplate(compiled.subject().execute(params), compiled.body().execute(params));
    }

    private record CompiledTemplate(Template subject, Template body) {}
}
package dev.museframework.it;

import dev.museframework.babelfish.api.BabelfishRegistry;
import dev.museframework.babelfish.api.ElementMapper;
import dev.museframework.babelfish.emitter.KotlinCodeEmitter;
import dev.museframework.babelfish.mapper.MappingContext;
import dev.museframework.babelfish.model.RawElement;
import dev.museframework.babelfish.spi.BabelfishMapperProvider;
import dev.museframework.dsl.api.model.component.PipelineElement;

public final class DuplicatePayloadProvider implements BabelfishMapperProvider {
    @Override
    public String getId() {
        return "duplicate-payload";
    }

    @Override
    public void registerMappings(BabelfishRegistry registry) {
        registry.registerElement("", "set-payload", new RejectingMapper());
    }

    @Override
    public void registerEmitters(KotlinCodeEmitter emitter, boolean nativeBuild) {
        throw new AssertionError("Duplicate registration must fail before registering emitters");
    }

    private static final class RejectingMapper implements ElementMapper<PipelineElement> {
        @Override
        public PipelineElement map(RawElement element, MappingContext context) {
            throw new AssertionError("Duplicate registration must fail before invoking the mapper");
        }
    }
}

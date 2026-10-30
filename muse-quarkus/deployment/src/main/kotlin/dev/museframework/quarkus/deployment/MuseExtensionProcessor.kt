package dev.museframework.quarkus.deployment

import io.quarkus.arc.deployment.AdditionalBeanBuildItem
import io.quarkus.deployment.annotations.BuildStep
import io.quarkus.deployment.builditem.FeatureBuildItem
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem

class MuseExtensionProcessor {
    @BuildStep
    fun feature(): FeatureBuildItem = FeatureBuildItem("muse")

    @BuildStep
    fun additionalBeans(): AdditionalBeanBuildItem =
        AdditionalBeanBuildItem.builder()
            .addBeanClasses(
                "dev.museframework.quarkus.MuseApplicationProducer",
                "dev.museframework.quarkus.MuseComponentFactoryProducer",
                "dev.museframework.quarkus.MuseLifecycleManager",
            )
            .setUnremovable()
            .build()

    @BuildStep
    fun serviceProviders(): List<ServiceProviderBuildItem> =
        listOf(
            ServiceProviderBuildItem(
                "dev.museframework.engine.api.spi.MuseComponentRegistrar",
                "dev.museframework.engine.factory.MuseEngineRegistrar",
            ),
        ) + DATAWEAVE_SERVICE_TYPES.map(ServiceProviderBuildItem::allProvidersFromClassPath)

    @BuildStep
    fun dataWeaveResources(): NativeImageResourcePatternsBuildItem =
        NativeImageResourcePatternsBuildItem.builder()
            .includeGlob("dw/**/*.dwl")
            .build()

    companion object {
        private val DATAWEAVE_SERVICE_TYPES =
            listOf(
                "org.mule.weave.v2.cache.service.registry.CacheRegistry",
                "org.mule.weave.v2.inspector.CodeInspectorProvider",
                "org.mule.weave.v2.model.ServiceRegistration",
                "org.mule.weave.v2.model.values.NativeValueProvider",
                "org.mule.weave.v2.module.DataFormat",
                "org.mule.weave.v2.module.commons.java.JavaHeavyValueDiscover",
                "org.mule.weave.v2.parser.phase.ModuleLoader",
            )
    }
}

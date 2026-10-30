package dev.museframework.connectors.http.quarkus.deployment

import io.quarkus.arc.deployment.AdditionalBeanBuildItem
import io.quarkus.deployment.annotations.BuildStep
import io.quarkus.deployment.builditem.FeatureBuildItem

class HttpConnectorQuarkusProcessor {
    @BuildStep
    fun feature(): FeatureBuildItem = FeatureBuildItem("muse-http-connector")

    @BuildStep
    fun additionalBeans(): AdditionalBeanBuildItem =
        AdditionalBeanBuildItem.builder()
            .addBeanClasses(
                "dev.museframework.connectors.http.quarkus.QuarkusHttpServer",
                "dev.museframework.connectors.http.quarkus.QuarkusHttpListenerServerInitializer",
                "dev.museframework.connectors.http.quarkus.QuarkusHttpClientInitializer",
                "dev.museframework.connectors.http.quarkus.QuarkusHttpConnectorRegistrar",
            )
            .setUnremovable()
            .build()
}

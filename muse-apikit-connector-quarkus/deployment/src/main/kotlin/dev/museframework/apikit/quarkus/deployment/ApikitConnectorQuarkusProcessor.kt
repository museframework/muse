package dev.museframework.apikit.quarkus.deployment

import io.quarkus.arc.deployment.AdditionalBeanBuildItem
import io.quarkus.deployment.annotations.BuildStep
import io.quarkus.deployment.builditem.FeatureBuildItem
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem

class ApikitConnectorQuarkusProcessor {

    @BuildStep
    fun feature(): FeatureBuildItem = FeatureBuildItem("muse-apikit-connector")

    @BuildStep
    fun additionalBeans(): AdditionalBeanBuildItem =
        AdditionalBeanBuildItem.builder()
            .addBeanClasses("dev.museframework.apikit.connector.ApikitConnectorRegistrar")
            .setUnremovable()
            .build()

    @BuildStep
    fun nativeResources(): NativeImageResourcePatternsBuildItem =
        NativeImageResourcePatternsBuildItem.builder()
            .includeGlob("**/*.raml")
            .includeGlob("**/*.yaml")
            .includeGlob("**/*.yml")
            .build()
}

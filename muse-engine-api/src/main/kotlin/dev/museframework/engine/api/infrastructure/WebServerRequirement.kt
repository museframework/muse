package dev.museframework.engine.api.infrastructure

object WebServerRequirement : InfrastructureRequirement {
    override val name: String = "web-server"
    override val priority: Int = 100
}

object JmsBrokerRequirement : InfrastructureRequirement {
    override val name: String = "jms-broker"
    override val priority: Int = 90
}
